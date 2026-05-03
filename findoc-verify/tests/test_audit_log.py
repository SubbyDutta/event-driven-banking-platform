"""Audit-log endpoint tests."""
from __future__ import annotations

import uuid
from datetime import datetime, timedelta, timezone
from unittest.mock import AsyncMock, MagicMock

import pytest

from src.api import admin_observability as admin_obs
from src.auth import AuthContext
from src.db.models import (
    DecisionOverride,
    ExtractedFieldOverride,
    PipelineRun,
    Recommendation,
)


def _auth_global() -> AuthContext:
    return AuthContext(
        api_key_id=uuid.uuid4(),
        org_name="acme",
        label="admin",
        scopes=["admin", "admin_global"],
    )


class _ScalarsResult:
    def __init__(self, rows):
        self._rows = rows

    def scalars(self):
        return iter(self._rows)

    def scalar(self):
        return self._rows[0] if self._rows else None

    def scalar_one_or_none(self):
        return self._rows[0] if self._rows else None

    def all(self):
        return list(self._rows)


def _make_session(decisions, fields, runs):
    async def fake_execute(stmt, *_a, **_kw):
        text = str(stmt).lower()
        if "from decision_overrides" in text:
            return _ScalarsResult(decisions)
        if "from extracted_field_overrides" in text:
            return _ScalarsResult(fields)
        if "from pipeline_runs" in text:
            return _ScalarsResult(runs)
        if "from applications" in text:
            return _ScalarsResult([])
        if "from api_keys" in text:
            return _ScalarsResult([])
        if "from pipeline_events" in text:
            return _ScalarsResult([])
        return _ScalarsResult([])

    session = MagicMock()
    session.execute = AsyncMock(side_effect=fake_execute)
    return session


def _decision(app_id, when, actor="acme:admin", prev="manual_review", new="approve"):
    return DecisionOverride(
        id=uuid.uuid4(),
        application_id=app_id,
        previous_recommendation=Recommendation(prev),
        new_recommendation=Recommendation(new),
        reason="manual review override",
        actor_api_key_id=None,
        actor_org=actor,
        created_at=when,
    )


def _field(app_id, when, actor="acme:admin", field_name="gross_pay"):
    f = ExtractedFieldOverride(
        application_id=app_id,
        document_id=None,
        field_name=field_name,
        original_value="100",
        new_value="200",
        reason="OCR mistake",
        edited_by=actor,
        edited_at=when,
    )
    f.id = uuid.uuid4().int & 0x7FFFFFFF
    return f


def _run(app_id, when, run_number=2, triggered_by="acme:admin"):
    r = PipelineRun(
        application_id=app_id,
        run_number=run_number,
        triggered_by=triggered_by,
        reason="replay",
        started_at=when,
    )
    r.id = uuid.uuid4().int & 0x7FFFFFFF
    return r


@pytest.mark.asyncio
async def test_audit_log_unions_all_three_sources_and_paginates():
    app_id = uuid.uuid4()
    base = datetime(2026, 5, 1, 12, 0, tzinfo=timezone.utc)

    decisions = [_decision(app_id, base + timedelta(minutes=i)) for i in range(5)]
    fields = [_field(app_id, base + timedelta(minutes=10 + i)) for i in range(3)]
    runs = [_run(app_id, base + timedelta(minutes=20 + i)) for i in range(2)]

    session = _make_session(decisions, fields, runs)

    page0 = await admin_obs.list_audit_log(
        applicationId=None, actor=None, action=None,
        fromDate=None, toDate=None, page=0, size=20,
        auth=_auth_global(), session=session,
    )
    assert page0.total == 10, "5 decisions + 3 fields + 2 runs = 10"
    assert len(page0.items) == 10
    assert page0.page == 0
    assert page0.pageSize == 20
    assert page0.totalPages == 1

    assert page0.items[0].action == "pipeline_run"
    assert page0.items[0].after == {"runNumber": 2, "triggerKind": "replay"}

    p0 = await admin_obs.list_audit_log(
        applicationId=None, actor=None, action=None,
        fromDate=None, toDate=None, page=0, size=4,
        auth=_auth_global(), session=session,
    )
    assert p0.total == 10
    assert p0.totalPages == 3
    assert len(p0.items) == 4

    p2 = await admin_obs.list_audit_log(
        applicationId=None, actor=None, action=None,
        fromDate=None, toDate=None, page=2, size=4,
        auth=_auth_global(), session=session,
    )
    assert len(p2.items) == 2


@pytest.mark.asyncio
async def test_audit_log_filter_by_action():
    app_id = uuid.uuid4()
    base = datetime(2026, 5, 1, tzinfo=timezone.utc)

    decisions = [_decision(app_id, base + timedelta(minutes=i)) for i in range(5)]
    fields = [_field(app_id, base + timedelta(hours=1, minutes=i)) for i in range(3)]
    runs = [_run(app_id, base + timedelta(hours=2, minutes=i)) for i in range(2)]

    session = _make_session(decisions, fields, runs)

    out = await admin_obs.list_audit_log(
        applicationId=None, actor=None, action="decision_override",
        fromDate=None, toDate=None, page=0, size=20,
        auth=_auth_global(), session=session,
    )
    assert out.total == 5
    assert all(i.action == "decision_override" for i in out.items)

    out2 = await admin_obs.list_audit_log(
        applicationId=None, actor=None, action="field_override",
        fromDate=None, toDate=None, page=0, size=20,
        auth=_auth_global(), session=session,
    )
    assert out2.total == 3
    assert all(i.action == "field_override" for i in out2.items)

    out3 = await admin_obs.list_audit_log(
        applicationId=None, actor=None, action="pipeline_run",
        fromDate=None, toDate=None, page=0, size=20,
        auth=_auth_global(), session=session,
    )
    assert out3.total == 2


@pytest.mark.asyncio
async def test_audit_log_accepts_all_filters_without_error():
    app_id = uuid.uuid4()
    base = datetime(2026, 5, 1, tzinfo=timezone.utc)

    decisions = [_decision(app_id, base + timedelta(minutes=i), actor="acme:admin") for i in range(2)]
    fields = [_field(app_id, base + timedelta(minutes=i), actor="acme:admin") for i in range(2)]
    runs = [_run(app_id, base + timedelta(minutes=i), triggered_by="acme:admin") for i in range(2)]

    session = _make_session(decisions, fields, runs)

    out = await admin_obs.list_audit_log(
        applicationId=app_id,
        actor="acme",
        action=None,
        fromDate=base - timedelta(days=1),
        toDate=base + timedelta(days=1),
        page=0,
        size=20,
        auth=_auth_global(),
        session=session,
    )
    assert out.page == 0
    assert out.pageSize == 20
    assert out.totalPages >= 1


@pytest.mark.asyncio
async def test_audit_log_invalid_action_rejected():
    from fastapi import HTTPException

    session = _make_session([], [], [])
    with pytest.raises(HTTPException) as excinfo:
        await admin_obs.list_audit_log(
            applicationId=None, actor=None, action="bogus",
            fromDate=None, toDate=None, page=0, size=20,
            auth=_auth_global(), session=session,
        )
    assert excinfo.value.status_code == 400


@pytest.mark.asyncio
async def test_audit_log_response_shape_decision_override():
    app_id = uuid.uuid4()
    when = datetime(2026, 5, 1, tzinfo=timezone.utc)
    d = _decision(app_id, when, actor="acme:reviewer", prev="manual_review", new="approve")

    session = _make_session([d], [], [])
    out = await admin_obs.list_audit_log(
        applicationId=None, actor=None, action="decision_override",
        fromDate=None, toDate=None, page=0, size=20,
        auth=_auth_global(), session=session,
    )
    assert out.total == 1
    item = out.items[0]
    assert item.before == {"decision": "manual_review"}
    assert item.after == {"decision": "approve"}
    assert item.actor == "acme:reviewer"
    assert item.applicationId == app_id
    assert item.reason == "manual review override"
    assert item.id.startswith("decision_override:")


@pytest.mark.asyncio
async def test_audit_log_response_shape_field_override():
    app_id = uuid.uuid4()
    when = datetime(2026, 5, 1, tzinfo=timezone.utc)
    f = _field(app_id, when, actor="acme:officer", field_name="net_pay")

    session = _make_session([], [f], [])
    out = await admin_obs.list_audit_log(
        applicationId=None, actor=None, action="field_override",
        fromDate=None, toDate=None, page=0, size=20,
        auth=_auth_global(), session=session,
    )
    item = out.items[0]
    assert item.before == {"field": "net_pay", "value": "100"}
    assert item.after == {"field": "net_pay", "value": "200"}
    assert item.actor == "acme:officer"
    assert item.id.startswith("field_override:")


@pytest.mark.asyncio
async def test_audit_log_response_shape_pipeline_run():
    app_id = uuid.uuid4()
    when = datetime(2026, 5, 1, tzinfo=timezone.utc)
    r_initial = _run(app_id, when, run_number=1, triggered_by="system")
    r_replay = _run(app_id, when + timedelta(minutes=1), run_number=2, triggered_by="acme:admin")

    session = _make_session([], [], [r_initial, r_replay])
    out = await admin_obs.list_audit_log(
        applicationId=None, actor=None, action="pipeline_run",
        fromDate=None, toDate=None, page=0, size=20,
        auth=_auth_global(), session=session,
    )
    assert out.total == 2
    assert out.items[0].after == {"runNumber": 2, "triggerKind": "replay"}
    assert out.items[1].after == {"runNumber": 1, "triggerKind": "initial"}


@pytest.mark.asyncio
async def test_audit_log_empty_when_no_data():
    session = _make_session([], [], [])
    out = await admin_obs.list_audit_log(
        applicationId=None, actor=None, action=None,
        fromDate=None, toDate=None, page=0, size=20,
        auth=_auth_global(), session=session,
    )
    assert out.total == 0
    assert out.items == []
    assert out.totalPages == 1


@pytest.mark.asyncio
async def test_timeline_pagination():
    from src.db.models import Application, ApplicationStatus, PipelineEvent, UseCase

    app_id = uuid.uuid4()
    app_obj = Application(
        id=app_id, external_id="ext-1", use_case=UseCase.loan,
        applicant_name="Test", email="t@x.com", phone="9",
        status=ApplicationStatus.processing,
    )

    events = []
    for i in range(7):
        e = PipelineEvent(
            application_id=app_id,
            step_name=f"step_{i}",
            step_status="completed",
            details={},
            created_at=datetime(2026, 5, 1, tzinfo=timezone.utc) + timedelta(seconds=i),
        )
        e.id = uuid.uuid4()
        events.append(e)

    async def fake_execute(stmt, *_a, **_kw):
        text = str(stmt).lower()
        if "from applications" in text:
            return _ScalarsResult([app_obj])
        if "count(" in text and "pipeline_events" in text:
            return _ScalarsResult([len(events)])
        if "from pipeline_events" in text:
            return _ScalarsResult(events)
        return _ScalarsResult([])

    session = MagicMock()
    session.execute = AsyncMock(side_effect=fake_execute)

    p0 = await admin_obs.get_timeline(
        application_id=app_id, page=0, size=3,
        auth=_auth_global(), session=session,
    )
    assert p0.total == 7
    assert p0.pageSize == 3
    assert p0.page == 0
    assert p0.totalPages == 3
