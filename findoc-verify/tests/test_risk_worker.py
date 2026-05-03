"""Tests for RiskWorker.handle (findoc-verify side)."""
from __future__ import annotations

import uuid
from contextlib import asynccontextmanager
from unittest.mock import MagicMock, patch

import pytest
from sqlalchemy.dialects import postgresql as pg_dialect

from src.db.models import ApplicationStatus, Recommendation
from src.workers import risk_worker as rw


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

    async def commit(self):
        self.committed = True


def _track_step_noop():
    @asynccontextmanager
    async def _ts(*_a, **_kw):
        yield
    return _ts


def _make_worker():
    with patch("boto3.client"), patch.object(rw.RiskWorker, "__init__", lambda self: None):
        return rw.RiskWorker()


def _approve_loan_report():
    return {
        "recommendation": "approve",
        "overall_score": 78.5,
        "income": {"declared_monthly_inr": 75000.0, "declared_annual_inr": 900000.0},
        "debt": {"dti_ratio": 0.18},
        "credit_score": 760,
    }


def _kyc_verified_report():
    return {"recommendation": "verified", "overall_score": 95.0}


class _FakeStore:
    async def preload(self):
        return {}


@pytest.mark.asyncio
async def test_view_incomplete_short_circuits(monkeypatch):
    async def _none(_a):
        return None

    monkeypatch.setattr(rw, "build_application_view", _none)
    monkeypatch.setattr(rw, "track_step", _track_step_noop())
    monkeypatch.setattr(rw, "get_store", lambda: _FakeStore())

    publishes: list = []
    monkeypatch.setattr(rw, "get_publisher",
                        lambda: type("P", (), {"publish":
                            lambda self, **kw: publishes.append(kw) or None})())

    sessions: list = []

    def _factory():
        s = _SessionCtx()
        sessions.append(s)
        return s

    monkeypatch.setattr(rw, "SessionLocal", _factory)

    worker = _make_worker()
    await worker.handle(_event(uuid.uuid4()))

    assert publishes == []


@pytest.mark.asyncio
async def test_happy_path_loan_approve_persists_and_publishes(monkeypatch):
    app_id = uuid.uuid4()
    view = {"use_case": "loan"}

    async def _view(_a):
        return view

    monkeypatch.setattr(rw, "build_application_view", _view)
    monkeypatch.setattr(rw, "track_step", _track_step_noop())
    monkeypatch.setattr(rw, "get_store", lambda: _FakeStore())

    read_session = _SessionCtx(scalars_seq=[[], [], []])
    write_session = _SessionCtx()
    sessions = [read_session, write_session]
    state = {"i": 0}

    def _factory():
        s = sessions[state["i"]]
        state["i"] += 1
        return s

    monkeypatch.setattr(rw, "SessionLocal", _factory)
    monkeypatch.setattr(rw, "assemble_loan_report", lambda *_a, **_k: _approve_loan_report())
    monkeypatch.setattr(rw, "assemble_kyc_report",
                        lambda *_a, **_k: pytest.fail("must use loan path"))

    published: dict = {}
    monkeypatch.setattr(rw, "get_publisher",
                        lambda: type("P", (), {"publish":
                            lambda self, **kw: published.update(kw)})())

    worker = _make_worker()
    await worker.handle(_event(app_id, run_number=3))

    assert write_session.committed is True
    assert len(write_session.executed) == 2
    upsert_sql = str(write_session.executed[0].compile(dialect=pg_dialect.dialect()))
    assert "ON CONFLICT" in upsert_sql

    assert published["topic_name"] == rw.TOPIC_RISK_SCORED
    assert published["event_type"] == rw.EVT_RISK_SCORED
    p = published["payload"]
    assert p["applicationId"] == str(app_id)
    assert p["useCase"] == "loan"
    assert p["recommendation"] == "approve"
    assert p["overallScore"] == 78.5
    assert p["runNumber"] == 3
    assert published["deduplication_id"]


@pytest.mark.asyncio
async def test_kyc_use_case_calls_kyc_assembler(monkeypatch):
    app_id = uuid.uuid4()
    view = {"use_case": "kyc"}

    async def _view(_a):
        return view

    monkeypatch.setattr(rw, "build_application_view", _view)
    monkeypatch.setattr(rw, "track_step", _track_step_noop())
    monkeypatch.setattr(rw, "get_store", lambda: _FakeStore())

    sessions = [_SessionCtx(scalars_seq=[[], [], []]), _SessionCtx()]
    state = {"i": 0}

    def _factory():
        s = sessions[state["i"]]
        state["i"] += 1
        return s

    monkeypatch.setattr(rw, "SessionLocal", _factory)
    monkeypatch.setattr(rw, "assemble_kyc_report", lambda *_a, **_k: _kyc_verified_report())
    monkeypatch.setattr(rw, "assemble_loan_report",
                        lambda *_a, **_k: pytest.fail("must not be called for KYC"))

    published: dict = {}
    monkeypatch.setattr(rw, "get_publisher",
                        lambda: type("P", (), {"publish":
                            lambda self, **kw: published.update(kw)})())

    worker = _make_worker()
    await worker.handle(_event(app_id))

    assert published["payload"]["recommendation"] == "verified"
    assert published["payload"]["useCase"] == "kyc"


@pytest.mark.asyncio
async def test_idempotent_replay_same_dedup_id(monkeypatch):
    app_id = uuid.uuid4()
    view = {"use_case": "loan"}

    async def _view(_a):
        return view

    monkeypatch.setattr(rw, "build_application_view", _view)
    monkeypatch.setattr(rw, "track_step", _track_step_noop())
    monkeypatch.setattr(rw, "get_store", lambda: _FakeStore())

    sessions = [
        _SessionCtx(scalars_seq=[[], [], []]), _SessionCtx(),
        _SessionCtx(scalars_seq=[[], [], []]), _SessionCtx(),
    ]
    state = {"i": 0}

    def _factory():
        s = sessions[state["i"]]
        state["i"] += 1
        return s

    monkeypatch.setattr(rw, "SessionLocal", _factory)
    monkeypatch.setattr(rw, "assemble_loan_report", lambda *_a, **_k: _approve_loan_report())

    publishes: list = []
    monkeypatch.setattr(rw, "get_publisher",
                        lambda: type("P", (), {"publish":
                            lambda self, **kw: publishes.append(kw)})())

    worker = _make_worker()
    await worker.handle(_event(app_id, run_number=1))
    await worker.handle(_event(app_id, run_number=1))

    assert publishes[0]["deduplication_id"] == publishes[1]["deduplication_id"]


@pytest.mark.asyncio
async def test_missing_applicationId_raises(monkeypatch):
    monkeypatch.setattr(rw, "track_step", _track_step_noop())
    monkeypatch.setattr(rw, "get_store", lambda: _FakeStore())
    worker = _make_worker()
    with pytest.raises(KeyError):
        await worker.handle({"eventId": str(uuid.uuid4()), "payload": {}})


@pytest.mark.asyncio
async def test_assembler_failure_propagates(monkeypatch):
    app_id = uuid.uuid4()
    view = {"use_case": "loan"}

    async def _view(_a):
        return view

    monkeypatch.setattr(rw, "build_application_view", _view)
    monkeypatch.setattr(rw, "track_step", _track_step_noop())
    monkeypatch.setattr(rw, "get_store", lambda: _FakeStore())
    monkeypatch.setattr(rw, "SessionLocal", lambda: _SessionCtx(scalars_seq=[[], [], []]))

    def _boom(*_a, **_kw):
        raise RuntimeError("model NaN")

    monkeypatch.setattr(rw, "assemble_loan_report", _boom)

    publishes: list = []
    monkeypatch.setattr(rw, "get_publisher",
                        lambda: type("P", (), {"publish":
                            lambda self, **kw: publishes.append(kw)})())

    worker = _make_worker()
    with pytest.raises(RuntimeError, match="model NaN"):
        await worker.handle(_event(app_id))

    assert publishes == []
