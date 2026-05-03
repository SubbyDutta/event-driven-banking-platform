"""Tests for SubbyPythonLoan RiskWorker.handle."""
from __future__ import annotations

import uuid
from unittest.mock import MagicMock, patch

import pytest

from src.messaging.schemas import NonRetriableError
from src.worker import risk_worker as rw_mod


def _request_event(loan_app_id: str = "loan-001",
                   amount: float = 500_000.0,
                   features: dict | None = None,
                   correlation_id: str | None = None) -> dict:
    return {
        "eventId": str(uuid.uuid4()),
        "occurredAt": "2026-04-24T10:00:00Z",
        "schemaVersion": 1,
        "eventType": "LoanRiskRequested",
        "correlationId": correlation_id or loan_app_id,
        "payload": {
            "loanAppId": loan_app_id,
            "amountRequested": amount,
            "tenureMonths": 12,
            "features": features if features is not None else {
                "monthly_income": 75000,
                "credit_score": 742,
                "bank_avg_balance": 120000,
                "existing_emi": 8000,
            },
        },
    }


def _make_predictor_result(decision: str = "approve",
                           band: str = "B",
                           pod: float = 0.12) -> dict:
    return {
        "probability_of_default": pod,
        "decision": decision,
        "risk_band": band,
        "reason": "low_probability_of_default",
        "featuresUsed": ["income", "balance", "avg_transaction",
                         "credit_score", "requested_amount"],
        "modelVersion": "v1.0.0-test",
    }


def _make_worker(predictor, publisher):
    with patch.object(rw_mod.SqsConsumer, "__init__", lambda self: None):
        worker = rw_mod.RiskWorker(predictor=predictor, publisher=publisher)
    return worker


@pytest.mark.asyncio
async def test_happy_path_predicts_and_publishes():
    predictor = MagicMock()
    predictor.predict = MagicMock(return_value=_make_predictor_result(
        decision="approve", band="A", pod=0.05,
    ))

    published: list = []
    publisher = MagicMock()
    publisher.publish = MagicMock(side_effect=lambda topic, env: published.append((topic, env)))

    worker = _make_worker(predictor, publisher)
    await worker.handle(_request_event(loan_app_id="L-100"))

    assert predictor.predict.call_count == 1
    inputs = predictor.predict.call_args.args[0]
    assert inputs["amountRequested"] == 500_000.0
    assert inputs["monthly_income"] == 75000

    assert len(published) == 1
    topic, envelope = published[0]
    assert envelope["eventType"] == "LoanRiskResult"
    assert envelope["correlationId"] == "L-100"
    p = envelope["payload"]
    assert p["loanAppId"] == "L-100"
    assert p["decision"] == "approve"
    assert p["risk_band"] == "A"
    assert p["probability_of_default"] == 0.05
    assert p["modelVersion"] == "v1.0.0-test"


@pytest.mark.asyncio
async def test_correlation_id_falls_back_to_loanAppId():
    predictor = MagicMock()
    predictor.predict = MagicMock(return_value=_make_predictor_result())
    publisher = MagicMock()
    publishes: list = []
    publisher.publish = MagicMock(side_effect=lambda t, e: publishes.append(e))

    event = _request_event(loan_app_id="L-no-corr")
    event.pop("correlationId", None)

    worker = _make_worker(predictor, publisher)
    await worker.handle(event)

    assert publishes[0]["correlationId"] == "L-no-corr"


@pytest.mark.asyncio
async def test_idempotent_replay_predicts_each_call():
    predictor = MagicMock()
    predictor.predict = MagicMock(return_value=_make_predictor_result())
    publisher = MagicMock()
    publishes: list = []
    publisher.publish = MagicMock(side_effect=lambda t, e: publishes.append(e))

    event = _request_event(loan_app_id="dup-1")

    worker = _make_worker(predictor, publisher)
    await worker.handle(event)
    await worker.handle(event)

    assert predictor.predict.call_count == 2
    assert len(publishes) == 2
    p1, p2 = publishes
    assert p1["correlationId"] == p2["correlationId"]
    assert p1["payload"]["loanAppId"] == p2["payload"]["loanAppId"] == "dup-1"


@pytest.mark.asyncio
async def test_unexpected_event_type_raises_nonretriable():
    worker = _make_worker(MagicMock(), MagicMock())
    bad = _request_event()
    bad["eventType"] = "SomeOtherEvent"

    with pytest.raises(NonRetriableError, match="unexpected_event_type"):
        await worker.handle(bad)


@pytest.mark.asyncio
async def test_missing_payload_raises_nonretriable():
    worker = _make_worker(MagicMock(), MagicMock())

    with pytest.raises(NonRetriableError, match="missing_payload"):
        await worker.handle({"eventId": str(uuid.uuid4()),
                             "eventType": "LoanRiskRequested",
                             "correlationId": "x"})


@pytest.mark.asyncio
async def test_missing_loanAppId_raises_nonretriable():
    worker = _make_worker(MagicMock(), MagicMock())
    event = _request_event()
    event["payload"].pop("loanAppId")

    with pytest.raises(NonRetriableError, match="missing_payload_field:loanAppId"):
        await worker.handle(event)


@pytest.mark.asyncio
async def test_missing_amountRequested_raises_nonretriable():
    worker = _make_worker(MagicMock(), MagicMock())
    event = _request_event()
    event["payload"].pop("amountRequested")

    with pytest.raises(NonRetriableError, match="missing_payload_field:amountRequested"):
        await worker.handle(event)


@pytest.mark.asyncio
async def test_missing_features_raises_nonretriable():
    worker = _make_worker(MagicMock(), MagicMock())
    event = _request_event()
    event["payload"]["features"] = None

    with pytest.raises(NonRetriableError, match="missing_features"):
        await worker.handle(event)


@pytest.mark.asyncio
async def test_flat_payload_accepted_when_required_fields_present():
    predictor = MagicMock()
    predictor.predict = MagicMock(return_value=_make_predictor_result())
    publisher = MagicMock()
    publishes: list = []
    publisher.publish = MagicMock(side_effect=lambda t, e: publishes.append(e))

    flat = {
        "eventId": str(uuid.uuid4()),
        "eventType": "LoanRiskRequested",
        "correlationId": "flat-1",
        "loanAppId": "flat-1",
        "amountRequested": 200_000,
        "tenureMonths": 12,
        "features": {"monthly_income": 60000, "credit_score": 700, "bank_avg_balance": 50000},
    }

    worker = _make_worker(predictor, publisher)
    await worker.handle(flat)

    assert predictor.predict.call_count == 1
    assert publishes[0]["payload"]["loanAppId"] == "flat-1"


@pytest.mark.asyncio
async def test_predictor_failure_propagates():
    predictor = MagicMock()
    predictor.predict = MagicMock(side_effect=RuntimeError("xgboost broken"))
    publisher = MagicMock()
    publishes: list = []
    publisher.publish = MagicMock(side_effect=lambda *_a: publishes.append(_a))

    worker = _make_worker(predictor, publisher)
    with pytest.raises(RuntimeError, match="xgboost broken"):
        await worker.handle(_request_event())

    assert publishes == [], "predictor failure must not produce a LoanRiskResult"


@pytest.mark.asyncio
async def test_predictor_validation_error_routes_nonretriable():
    predictor = MagicMock()
    predictor.predict = MagicMock(side_effect=NonRetriableError("invalid_features"))
    publisher = MagicMock()
    publishes: list = []
    publisher.publish = MagicMock(side_effect=lambda *_a: publishes.append(_a))

    worker = _make_worker(predictor, publisher)
    with pytest.raises(NonRetriableError, match="invalid_features"):
        await worker.handle(_request_event())

    assert publishes == []


@pytest.mark.asyncio
async def test_topic_resolved_from_settings(monkeypatch):
    predictor = MagicMock()
    predictor.predict = MagicMock(return_value=_make_predictor_result())
    publisher = MagicMock()
    seen: dict = {}
    publisher.publish = MagicMock(side_effect=lambda topic, env: seen.update(topic=topic))

    fake_settings = MagicMock()
    fake_settings.sns_topic_result = "custom-result-topic"
    monkeypatch.setattr(rw_mod, "get_settings", lambda: fake_settings)

    worker = _make_worker(predictor, publisher)
    await worker.handle(_request_event())

    assert seen["topic"] == "custom-result-topic"
