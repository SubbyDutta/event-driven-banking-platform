"""Unit tests for the in-process rate limiter and scope enforcement.

These cover the pure bits of src/auth.py (AuthContext.has_scope + the rolling
window check). They don't spin up FastAPI — the request-side behaviour is
covered indirectly by the integration tests against a running service.
"""
from __future__ import annotations

import uuid

import pytest

from src import auth as auth_module
from src.auth import (
    SCOPE_ADMIN,
    SCOPE_ADMIN_GLOBAL,
    SCOPE_SUBMIT,
    AuthContext,
    _rate_limit_check,
    _reset_rate_limits_for_tests,
)


@pytest.fixture(autouse=True)
def _reset():
    _reset_rate_limits_for_tests()
    yield
    _reset_rate_limits_for_tests()


# ---------- scopes ----------

def test_empty_scopes_is_backcompat_allow_all():
    ctx = AuthContext(api_key_id=uuid.uuid4(), org_name="x", label="y", scopes=[])
    assert ctx.has_scope(SCOPE_SUBMIT) is True
    assert ctx.has_scope(SCOPE_ADMIN) is True


def test_explicit_scope_grants_only_that_scope():
    ctx = AuthContext(
        api_key_id=uuid.uuid4(), org_name="x", label="y",
        scopes=[SCOPE_SUBMIT],
    )
    assert ctx.has_scope(SCOPE_SUBMIT) is True
    assert ctx.has_scope(SCOPE_ADMIN) is False


def test_admin_global_grants_everything():
    ctx = AuthContext(
        api_key_id=uuid.uuid4(), org_name="x", label="y",
        scopes=[SCOPE_ADMIN_GLOBAL],
    )
    assert ctx.has_scope(SCOPE_SUBMIT) is True
    assert ctx.has_scope(SCOPE_ADMIN) is True
    assert ctx.has_scope(SCOPE_ADMIN_GLOBAL) is True


# ---------- rate limiter ----------

def test_rate_limiter_allows_below_limit():
    key = uuid.uuid4()
    for _ in range(60):
        assert _rate_limit_check(key, 60) is True


def test_rate_limiter_blocks_when_limit_hit():
    key = uuid.uuid4()
    for _ in range(60):
        assert _rate_limit_check(key, 60) is True
    assert _rate_limit_check(key, 60) is False


def test_rate_limiter_is_per_key():
    k1 = uuid.uuid4()
    k2 = uuid.uuid4()
    for _ in range(60):
        _rate_limit_check(k1, 60)
    # k1 is saturated; k2 is fresh.
    assert _rate_limit_check(k1, 60) is False
    assert _rate_limit_check(k2, 60) is True


def test_rate_limiter_window_drops_old_entries(monkeypatch):
    """Sliding window: entries older than 60s fall out, freeing capacity."""
    key = uuid.uuid4()
    fake_now = {"t": 1_000.0}

    def fake_monotonic() -> float:
        return fake_now["t"]

    monkeypatch.setattr(auth_module.time, "monotonic", fake_monotonic)

    for _ in range(60):
        assert _rate_limit_check(key, 60) is True
    assert _rate_limit_check(key, 60) is False

    # Jump 61 seconds forward — all prior entries are outside the window.
    fake_now["t"] += 61.0
    assert _rate_limit_check(key, 60) is True
