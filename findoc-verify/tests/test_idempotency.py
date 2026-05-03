"""Idempotency tests for POST /api/v1/loan-origination/submit.

Java's SQS consumer is at-least-once, so the same external_id may arrive twice.
These tests drive the handler directly with mocked DB/storage/publisher deps —
no Postgres or LocalStack needed.
"""
from __future__ import annotations

import uuid
from unittest.mock import AsyncMock, MagicMock

import pytest
from fastapi import Response
from fastapi.responses import JSONResponse
from sqlalchemy.exc import IntegrityError

from src.api import applications as app_module
from src.api.applications import submit_loan
from src.auth import AuthContext
from src.db.models import Application, ApplicationStatus, UseCase


class _FakeResult:
    """Mimics the subset of sqlalchemy.Result used by the handler."""

    def __init__(self, *, scalar=None, scalar_one_or_none=None, scalar_one=None):
        self._scalar = scalar
        self._one_or_none = scalar_one_or_none
        self._exact = scalar_one

    def scalar(self):
        return self._scalar

    def scalar_one_or_none(self):
        return self._one_or_none

    def scalar_one(self):
        return self._exact


class _FakeUpload:
    def __init__(self, name: str):
        self.filename = name
        self.content_type = "application/pdf"

    async def read(self) -> bytes:
        return b"%PDF-1.4 fake"


def _make_app(external_id: str) -> Application:
    return Application(
        id=uuid.uuid4(),
        external_id=external_id,
        use_case=UseCase.loan,
        applicant_name="Test",
        email="t@x.com",
        phone="9999",
        status=ApplicationStatus.received,
    )


def _auth() -> AuthContext:
    return AuthContext(api_key_id=uuid.uuid4(), org_name="test-org", label="test")


def _make_session(results: list[_FakeResult]) -> MagicMock:
    session = MagicMock()
    session.execute = AsyncMock(side_effect=list(results))
    session.rollback = AsyncMock()
    session.commit = AsyncMock()
    return session


def _full_file_bundle() -> dict:
    return {
        "aadhaar": _FakeUpload("aadhaar.pdf"),
        "pan": _FakeUpload("pan.pdf"),
        "bank_statement_1": _FakeUpload("b1.pdf"),
        "bank_statement_2": _FakeUpload("b2.pdf"),
        "bank_statement_3": _FakeUpload("b3.pdf"),
        "payslip_1": _FakeUpload("p1.pdf"),
        "payslip_2": _FakeUpload("p2.pdf"),
        "payslip_3": _FakeUpload("p3.pdf"),
        "employment_letter": _FakeUpload("el.pdf"),
        "itr": _FakeUpload("itr.pdf"),
        "credit_report": _FakeUpload("cr.pdf"),
    }


def _empty_file_bundle() -> dict:
    return {
        "aadhaar": None, "pan": None,
        "bank_statement_1": None, "bank_statement_2": None, "bank_statement_3": None,
        "payslip_1": None, "payslip_2": None, "payslip_3": None,
        "employment_letter": None, "itr": None, "credit_report": None,
    }


# ---------- Case 1: replay hits an already-created row ----------

@pytest.mark.asyncio
async def test_idempotent_replay_returns_200_and_does_not_create():
    existing = _make_app("test-001")
    session = _make_session([
        _FakeResult(scalar_one_or_none=existing),  # existence lookup
        _FakeResult(scalar=5),                     # doc count for response
    ])
    response = Response()

    result = await submit_loan(
        response=response,
        applicant_name="ignored", email="ignored@x", phone="1",
        external_id="test-001",
        **_empty_file_bundle(),
        auth=_auth(), session=session,
    )

    assert response.status_code == 200
    assert result.idempotentReplay is True
    assert result.applicationId == existing.id
    assert result.externalId == "test-001"
    assert result.documentsAccepted == 5
    # Must not have created, committed, or rolled back.
    session.commit.assert_not_awaited()
    session.rollback.assert_not_awaited()


# ---------- Case 2: race — IntegrityError on insert, fall back to existing ----------

@pytest.mark.asyncio
async def test_concurrent_race_returns_200_with_replay_flag(monkeypatch):
    existing = _make_app("test-002")
    session = _make_session([
        _FakeResult(scalar_one_or_none=None),  # first dedup lookup misses (race)
        _FakeResult(scalar_one=existing),      # refetch after IntegrityError
        _FakeResult(scalar=0),                 # doc count for response
    ])

    async def _raise_integrity(*_a, **_kw):
        raise IntegrityError("INSERT", {}, Exception("unique violation"))

    monkeypatch.setattr(app_module, "create_application", _raise_integrity)

    response = Response()
    result = await submit_loan(
        response=response,
        applicant_name="Test", email="t@x", phone="9",
        external_id="test-002",
        **_full_file_bundle(),
        auth=_auth(), session=session,
    )

    assert response.status_code == 200
    assert result.idempotentReplay is True
    assert result.applicationId == existing.id
    session.rollback.assert_awaited_once()
    session.commit.assert_not_awaited()


# ---------- Case 3: no external_id → skip dedup path entirely ----------

@pytest.mark.asyncio
async def test_missing_external_id_skips_dedup_lookup():
    # Session.execute must NOT be called before validation rejects the submission.
    session = MagicMock()
    session.execute = AsyncMock(side_effect=AssertionError(
        "idempotency lookup must not run when external_id is empty"
    ))
    session.rollback = AsyncMock()
    session.commit = AsyncMock()

    response = Response()
    result = await submit_loan(
        response=response,
        applicant_name="x", email="x@x", phone="1",
        external_id="",                  # empty → no dedup
        **_empty_file_bundle(),          # empty bundle → validation fails fast
        auth=_auth(), session=session,
    )

    # Validation error comes out as a 400 JSONResponse; the key assertion
    # is that session.execute was never awaited (assertion side_effect proves it).
    assert isinstance(result, JSONResponse)
    assert result.status_code == 400
    session.execute.assert_not_awaited()
