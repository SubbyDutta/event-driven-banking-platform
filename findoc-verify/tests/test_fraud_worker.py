"""Tests for FraudWorker.handle."""
from __future__ import annotations

import uuid
from contextlib import asynccontextmanager
from unittest.mock import MagicMock, patch

import pytest

from src.db.models import CheckStatus, FraudSeverity
from src.workers import fraud_worker as fw


def _event(application_id: uuid.UUID, run_number: int = 1) -> dict:
    return {
        "eventId": str(uuid.uuid4()),
        "eventType": "application.aggregated",
        "payload": {
            "applicationId": str(application_id),
            "runNumber": run_number,
        },
    }


class _ScalarsIter:
    def __init__(self, items):
        self._items = list(items or [])

    def __iter__(self):
        return iter(self._items)


class _SessionCtx:
    def __init__(self, scalars_seq=None):
        self._seq = list(scalars_seq or [])
        self.executed: list = []
        self.added: list = []
        self.committed = False

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_exc):
        return False

    async def execute(self, stmt, *_a, **_kw):
        self.executed.append(stmt)
        r = MagicMock()
        if self._seq:
            r.scalars = MagicMock(return_value=_ScalarsIter(self._seq.pop(0)))
        else:
            r.scalars = MagicMock(return_value=_ScalarsIter([]))
        return r

    def add(self, obj):
        self.added.append(obj)

    async def commit(self):
        self.committed = True


def _track_step_noop():
    @asynccontextmanager
    async def _ts(*_a, **_kw):
        yield
    return _ts


def _make_worker():
    with patch("boto3.client"), patch.object(fw.FraudWorker, "__init__", lambda self: None):
        return fw.FraudWorker()


def _cross_row(name="some_rule", status=CheckStatus.pass_):
    r = MagicMock()
    r.rule_name = name
    r.status = status
    r.details = {}
    return r


@pytest.mark.asyncio
async def test_view_incomplete_short_circuits(monkeypatch):
    async def _none(_a):
        return None

    monkeypatch.setattr(fw, "build_application_view", _none)
    monkeypatch.setattr(fw, "track_step", _track_step_noop())

    publishes: list = []
    monkeypatch.setattr(fw, "get_publisher",
                        lambda: type("P", (), {"publish":
                            lambda self, **kw: publishes.append(kw) or None})())

    sessions: list = []

    def _factory():
        s = _SessionCtx()
        sessions.append(s)
        return s

    monkeypatch.setattr(fw, "SessionLocal", _factory)

    worker = _make_worker()
    await worker.handle(_event(uuid.uuid4()))

    assert publishes == []
    assert sessions == [], "no DB write when view is incomplete"


@pytest.mark.asyncio
async def test_happy_path_persists_signals_and_publishes(monkeypatch):
    app_id = uuid.uuid4()
    view = {"use_case": "loan", "documents": {}}

    async def _view(_a):
        return view

    monkeypatch.setattr(fw, "build_application_view", _view)
    monkeypatch.setattr(fw, "track_step", _track_step_noop())

    cross_session = _SessionCtx(scalars_seq=[[_cross_row("ifsc_check")]])
    write_session = _SessionCtx()
    sessions = [cross_session, write_session]
    factory_state = {"i": 0}

    def _factory():
        s = sessions[factory_state["i"]]
        factory_state["i"] += 1
        return s

    monkeypatch.setattr(fw, "SessionLocal", _factory)

    monkeypatch.setattr(fw, "run_all_signals", lambda view, cross: [
        {"signal_name": "ocr_confidence", "severity": "low", "score": 0.1, "details": {"avg": 0.95}},
        {"signal_name": "duplicate_file_hash", "severity": "high", "score": 0.9, "details": {}},
    ])
    monkeypatch.setattr(fw, "aggregate_fraud_score", lambda signals: 0.42)

    published: dict = {}
    monkeypatch.setattr(fw, "get_publisher",
                        lambda: type("P", (), {"publish":
                            lambda self, **kw: published.update(kw)})())

    worker = _make_worker()
    await worker.handle(_event(app_id, run_number=2))

    assert write_session.committed is True
    assert len(write_session.added) == 2
    severities = {row.severity for row in write_session.added}
    assert FraudSeverity.low in severities and FraudSeverity.high in severities

    assert published["topic_name"] == fw.TOPIC_FRAUD_CHECKED
    assert published["event_type"] == fw.EVT_FRAUD_CHECKED
    p = published["payload"]
    assert p["applicationId"] == str(app_id)
    assert p["runNumber"] == 2
    assert p["overallFraudScore"] == 0.42
    assert published["deduplication_id"]


@pytest.mark.asyncio
async def test_idempotent_replay_same_dedup_id(monkeypatch):
    app_id = uuid.uuid4()
    view = {"use_case": "loan", "documents": {}}

    async def _view(_a):
        return view

    monkeypatch.setattr(fw, "build_application_view", _view)
    monkeypatch.setattr(fw, "track_step", _track_step_noop())

    sessions = [
        _SessionCtx(scalars_seq=[[]]), _SessionCtx(),
        _SessionCtx(scalars_seq=[[]]), _SessionCtx(),
    ]
    state = {"i": 0}

    def _factory():
        s = sessions[state["i"]]
        state["i"] += 1
        return s

    monkeypatch.setattr(fw, "SessionLocal", _factory)
    monkeypatch.setattr(fw, "run_all_signals", lambda *_a: [])
    monkeypatch.setattr(fw, "aggregate_fraud_score", lambda *_a: 0.0)

    publishes: list = []
    monkeypatch.setattr(fw, "get_publisher",
                        lambda: type("P", (), {"publish":
                            lambda self, **kw: publishes.append(kw)})())

    worker = _make_worker()
    await worker.handle(_event(app_id, run_number=1))
    await worker.handle(_event(app_id, run_number=1))

    assert publishes[0]["deduplication_id"] == publishes[1]["deduplication_id"]


@pytest.mark.asyncio
async def test_missing_applicationId_raises(monkeypatch):
    monkeypatch.setattr(fw, "track_step", _track_step_noop())
    worker = _make_worker()
    with pytest.raises(KeyError):
        await worker.handle({"eventId": str(uuid.uuid4()), "payload": {}})


@pytest.mark.asyncio
async def test_run_all_signals_failure_propagates(monkeypatch):
    app_id = uuid.uuid4()
    view = {"use_case": "loan", "documents": {}}

    async def _view(_a):
        return view

    monkeypatch.setattr(fw, "build_application_view", _view)
    monkeypatch.setattr(fw, "track_step", _track_step_noop())
    monkeypatch.setattr(fw, "SessionLocal", lambda: _SessionCtx(scalars_seq=[[]]))

    def _boom(*_a, **_kw):
        raise RuntimeError("fraud signal lib oom")

    monkeypatch.setattr(fw, "run_all_signals", _boom)

    publishes: list = []
    monkeypatch.setattr(fw, "get_publisher",
                        lambda: type("P", (), {"publish":
                            lambda self, **kw: publishes.append(kw)})())

    worker = _make_worker()
    with pytest.raises(RuntimeError, match="fraud signal lib oom"):
        await worker.handle(_event(app_id))

    assert publishes == []
