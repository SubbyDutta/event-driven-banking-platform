"""Tests for the result publisher's use-case routing + payload shape.

The publisher must:
  - route loan apps to findoc-loan-report-ready
  - route kyc apps to findoc-kyc-report-ready
  - include callerOrg + full compliance/cross/fraud arrays
  - surface an `override` block + swap `recommendation` when republishing
"""
from __future__ import annotations

import uuid
from datetime import datetime, timezone
from unittest.mock import AsyncMock, MagicMock

import pytest

from src.db.models import (
    ApiKey,
    Application,
    ApplicationStatus,
    CheckStatus,
    ComplianceCheck,
    CrossDocValidation,
    DecisionOverride,
    FraudResult,
    FraudSeverity,
    Recommendation,
    UseCase,
    VerificationReport,
)
from src.messaging.topics import (
    EVT_KYC_REPORT_READY,
    EVT_LOAN_REPORT_READY,
    TOPIC_KYC_REPORT_READY,
    TOPIC_LOAN_REPORT_READY,
)
from src.workers import result_publisher as rp


def _make_app(use_case: UseCase, api_key_id: uuid.UUID | None) -> Application:
    return Application(
        id=uuid.uuid4(),
        external_id="corr-123",
        use_case=use_case,
        applicant_name="N",
        email="n@x",
        phone="9",
        status=ApplicationStatus.approved if use_case == UseCase.loan else ApplicationStatus.approved,
        submitted_by_api_key_id=api_key_id,
    )


def _make_report(app_id: uuid.UUID, rec: Recommendation) -> VerificationReport:
    return VerificationReport(
        id=uuid.uuid4(),
        application_id=app_id,
        recommendation=rec,
        overall_score=12.5,
        report_json={"schemaVersion": 1, "useCase": "loan", "recommendation": rec.value},
    )


class _SessionCtx:
    """Fake `async with SessionLocal() as session:` context.

    The publisher opens a single session and runs N SELECTs on it in a fixed
    order. We supply the results as a list, one per `execute` call.
    """

    def __init__(self, results: list):
        self._results = list(results)
        self.execute = AsyncMock(side_effect=self._next)

    async def _next(self, *_a, **_kw):
        return self._results.pop(0)

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_exc):
        return False


def _result(scalar=None, scalars=None):
    r = MagicMock()
    r.scalar_one_or_none = MagicMock(return_value=scalar)

    class _Scalars:
        def __init__(self, items): self._items = list(items or [])
        def __iter__(self): return iter(self._items)

    r.scalars = MagicMock(return_value=_Scalars(scalars or []))
    return r


@pytest.mark.asyncio
async def test_loan_publishes_to_loan_topic(monkeypatch):
    app = _make_app(UseCase.loan, api_key_id=uuid.uuid4())
    report = _make_report(app.id, Recommendation.approve)
    api_key = ApiKey(id=app.submitted_by_api_key_id, key_hash="h", label="l", org_name="subby")

    session = _SessionCtx([
        _result(scalar=app),
        _result(scalar=report),
        _result(scalar=api_key),
        _result(scalars=[]),
        _result(scalars=[]),
        _result(scalars=[]),
    ])
    monkeypatch.setattr(rp, "SessionLocal", lambda: session)

    published: dict = {}

    class _FakePublisher:
        def publish(self, topic_name, event_type, payload, deduplication_id=None):
            published["topic"] = topic_name
            published["event"] = event_type
            published["payload"] = payload
            published["dedup"] = deduplication_id

    monkeypatch.setattr(rp, "get_publisher", lambda: _FakePublisher())

    ok = await rp.publish_report(app.id)
    assert ok is True
    assert published["topic"] == TOPIC_LOAN_REPORT_READY
    assert published["event"] == EVT_LOAN_REPORT_READY
    p = published["payload"]
    assert p["useCase"] == "loan"
    assert p["recommendation"] == "approve"
    assert p["correlationId"] == "corr-123"
    assert p["callerOrg"] == "subby"
    assert p["complianceChecks"] == []
    assert p["fraudSignals"] == []


@pytest.mark.asyncio
async def test_override_republish_swaps_recommendation(monkeypatch):
    app = _make_app(UseCase.loan, api_key_id=uuid.uuid4())
    report = _make_report(app.id, Recommendation.reject)
    override = DecisionOverride(
        id=uuid.uuid4(),
        application_id=app.id,
        previous_recommendation=Recommendation.reject,
        new_recommendation=Recommendation.approve,
        reason="proof of stable income reviewed offline",
        actor_org="subby-admin",
        created_at=datetime.now(timezone.utc),
    )

    session = _SessionCtx([
        _result(scalar=app),
        _result(scalar=report),
        _result(scalar=None),
        _result(scalars=[]),
        _result(scalars=[]),
        _result(scalars=[]),
    ])
    monkeypatch.setattr(rp, "SessionLocal", lambda: session)

    published: dict = {}

    class _FakePublisher:
        def publish(self, topic_name, event_type, payload, deduplication_id=None):
            published["payload"] = payload
            published["dedup"] = deduplication_id

    monkeypatch.setattr(rp, "get_publisher", lambda: _FakePublisher())

    ok = await rp.publish_report(app.id, override=override)
    assert ok is True
    p = published["payload"]
    assert p["recommendation"] == "approve"
    assert p["override"]["previousRecommendation"] == "reject"
    assert p["override"]["newRecommendation"] == "approve"
    assert p["override"]["actorOrg"] == "subby-admin"
    final_uuid = str(uuid.uuid5(uuid.NAMESPACE_URL, f"findoc/result/{app.id}/final"))
    assert published["dedup"] != final_uuid
