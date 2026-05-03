"""Tests for AggregateWorker.handle."""
from __future__ import annotations

import uuid
from contextlib import asynccontextmanager
from unittest.mock import MagicMock, patch

import pytest

from src.workers import aggregate_worker as aw


def _event(application_id: uuid.UUID) -> dict:
    return {
        "eventId": str(uuid.uuid4()),
        "eventType": "doc.extracted",
        "payload": {
            "applicationId": str(application_id),
            "documentId": str(uuid.uuid4()),
        },
    }


class _SessionCtx:
    def __init__(self, scalar=None):
        self._scalar = scalar
        self.executed: list = []
        self.committed = False

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_exc):
        return False

    async def execute(self, stmt, *_a, **_kw):
        self.executed.append(stmt)
        r = MagicMock()
        r.scalar = MagicMock(return_value=self._scalar)
        return r

    async def commit(self):
        self.committed = True


def _track_step_noop():
    @asynccontextmanager
    async def _ts(*_a, **_kw):
        yield
    return _ts


def _make_worker():
    with patch("boto3.client"), patch.object(aw.AggregateWorker, "__init__", lambda self: None):
        return aw.AggregateWorker()


@pytest.mark.asyncio
async def test_view_incomplete_short_circuits(monkeypatch):
    app_id = uuid.uuid4()

    async def _none(_app_id):
        return None

    monkeypatch.setattr(aw, "build_application_view", _none)
    monkeypatch.setattr(aw, "track_step", _track_step_noop())

    publishes: list = []
    monkeypatch.setattr(aw, "get_publisher",
                        lambda: type("P", (), {"publish":
                            lambda self, **kw: publishes.append(kw) or None})())

    sessions = []

    def _factory():
        s = _SessionCtx()
        sessions.append(s)
        return s

    monkeypatch.setattr(aw, "SessionLocal", _factory)

    worker = _make_worker()
    await worker.handle(_event(app_id))

    assert publishes == []
    assert sessions == [], "no DB write should occur when view is incomplete"


@pytest.mark.asyncio
async def test_happy_path_publishes_aggregated(monkeypatch):
    app_id = uuid.uuid4()

    view = {
        "application_id": str(app_id),
        "external_id": "ext-001",
        "use_case": "loan",
        "documents": {
            "aadhaar": {"document_id": str(uuid.uuid4()), "fields": {}},
            "pan": {"document_id": str(uuid.uuid4()), "fields": {}},
            "bank_statements": [{"document_id": str(uuid.uuid4()), "fields": {}}],
            "payslips": [],
            "employment_letter": None,
            "itr": None,
            "credit_report": None,
        },
    }

    async def _view(_app_id):
        return view

    monkeypatch.setattr(aw, "build_application_view", _view)
    monkeypatch.setattr(aw, "track_step", _track_step_noop())

    write_ctx = _SessionCtx(scalar=2)
    monkeypatch.setattr(aw, "SessionLocal", lambda: write_ctx)

    published: dict = {}
    monkeypatch.setattr(aw, "get_publisher",
                        lambda: type("P", (), {"publish":
                            lambda self, **kw: published.update(kw)})())

    worker = _make_worker()
    await worker.handle(_event(app_id))

    assert write_ctx.committed is True
    assert published["topic_name"] == aw.TOPIC_APPLICATION_AGGREGATED
    assert published["event_type"] == aw.EVT_APPLICATION_AGGREGATED
    p = published["payload"]
    assert p["applicationId"] == str(app_id)
    assert p["externalId"] == "ext-001"
    assert p["useCase"] == "loan"
    assert p["documentCount"] == 3
    assert published["deduplication_id"], "dedup id required for aggregator"


@pytest.mark.asyncio
async def test_idempotent_replay_uses_same_dedup_id(monkeypatch):
    app_id = uuid.uuid4()

    view = {
        "application_id": str(app_id),
        "external_id": "ext-dup",
        "use_case": "loan",
        "documents": {"aadhaar": {"document_id": str(uuid.uuid4()), "fields": {}},
                      "pan": None, "bank_statements": [], "payslips": [],
                      "employment_letter": None, "itr": None, "credit_report": None},
    }

    async def _view(_app_id):
        return view

    monkeypatch.setattr(aw, "build_application_view", _view)
    monkeypatch.setattr(aw, "track_step", _track_step_noop())

    sessions = [_SessionCtx(scalar=1), _SessionCtx(scalar=1)]
    factory_calls = {"i": 0}

    def _factory():
        s = sessions[factory_calls["i"]]
        factory_calls["i"] += 1
        return s

    monkeypatch.setattr(aw, "SessionLocal", _factory)

    publishes: list = []
    monkeypatch.setattr(aw, "get_publisher",
                        lambda: type("P", (), {"publish":
                            lambda self, **kw: publishes.append(kw)})())

    worker = _make_worker()
    await worker.handle(_event(app_id))
    await worker.handle(_event(app_id))

    assert len(publishes) == 2
    assert publishes[0]["deduplication_id"] == publishes[1]["deduplication_id"]


@pytest.mark.asyncio
async def test_missing_applicationId_raises(monkeypatch):
    monkeypatch.setattr(aw, "track_step", _track_step_noop())
    worker = _make_worker()
    with pytest.raises(KeyError):
        await worker.handle({"eventId": str(uuid.uuid4()), "payload": {}})


@pytest.mark.asyncio
async def test_publisher_failure_propagates(monkeypatch):
    app_id = uuid.uuid4()
    view = {
        "application_id": str(app_id), "external_id": "x", "use_case": "loan",
        "documents": {"aadhaar": None, "pan": None, "bank_statements": [],
                      "payslips": [], "employment_letter": None, "itr": None,
                      "credit_report": None},
    }

    async def _view(_app_id):
        return view

    monkeypatch.setattr(aw, "build_application_view", _view)
    monkeypatch.setattr(aw, "track_step", _track_step_noop())
    monkeypatch.setattr(aw, "SessionLocal", lambda: _SessionCtx(scalar=1))

    class _Boom:
        def publish(self, **_kw):
            raise RuntimeError("SNS down")

    monkeypatch.setattr(aw, "get_publisher", lambda: _Boom())

    worker = _make_worker()
    with pytest.raises(RuntimeError, match="SNS down"):
        await worker.handle(_event(app_id))
