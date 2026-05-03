"""Endpoint auth matrix tests."""
from __future__ import annotations

import os
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from unittest.mock import MagicMock

os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://postgres:postgres@localhost:5432/findoc_test")
os.environ.setdefault("ADMIN_BOOTSTRAP_MODE", "false")

import pytest
from fastapi.testclient import TestClient

from src import auth as auth_module
from src.config import get_settings
from src.db.session import get_session
from src.main import app
from src.services import apikey_service


CALLER_KEY = "fdv_test_caller_no_admin"
ADMIN_KEY_ACME = "fdv_test_admin_acme"
ADMIN_KEY_OTHER = "fdv_test_admin_otherorg"
ADMIN_GLOBAL_KEY = "fdv_test_admin_global"
INVALID_KEY = "fdv_does_not_exist"


@dataclass
class _FakeApiKey:
    id: uuid.UUID
    label: str
    org_name: str
    scopes: list[str]
    rate_limit_per_min: int = 60
    revoked_at: datetime | None = None
    last_used_at: datetime | None = None
    created_at: datetime = datetime(2026, 1, 1, tzinfo=timezone.utc)


CALLER_CTX = _FakeApiKey(
    id=uuid.UUID("11111111-1111-1111-1111-111111111111"),
    label="caller", org_name="acme", scopes=["submit"],
)
ADMIN_CTX_ACME = _FakeApiKey(
    id=uuid.UUID("22222222-2222-2222-2222-222222222222"),
    label="acme-admin", org_name="acme", scopes=["admin"],
)
ADMIN_CTX_OTHER = _FakeApiKey(
    id=uuid.UUID("33333333-3333-3333-3333-333333333333"),
    label="other-admin", org_name="other-org", scopes=["admin"],
)
ADMIN_CTX_GLOBAL = _FakeApiKey(
    id=uuid.UUID("44444444-4444-4444-4444-444444444444"),
    label="global", org_name="acme", scopes=["admin", "admin_global"],
)

KEYS_BY_RAW: dict[str, _FakeApiKey] = {
    CALLER_KEY: CALLER_CTX,
    ADMIN_KEY_ACME: ADMIN_CTX_ACME,
    ADMIN_KEY_OTHER: ADMIN_CTX_OTHER,
    ADMIN_GLOBAL_KEY: ADMIN_CTX_GLOBAL,
}


@pytest.fixture(autouse=True)
def _reset_rate_limits():
    auth_module._reset_rate_limits_for_tests()
    yield
    auth_module._reset_rate_limits_for_tests()


@pytest.fixture
def client(monkeypatch):
    async def fake_find(_session, raw_key):
        return KEYS_BY_RAW.get(raw_key)

    async def fake_touch(_session, _id):
        return None

    monkeypatch.setattr(apikey_service, "find_active_by_raw", fake_find)
    monkeypatch.setattr(apikey_service, "touch_last_used", fake_touch)

    settings = get_settings()
    monkeypatch.setattr(settings, "admin_bootstrap_mode", False)

    async def fake_session():
        s = MagicMock()
        s.execute = MagicMock(side_effect=AssertionError(
            "auth-matrix test reached DB execute - means the auth filter let it through"
        ))
        s.commit = MagicMock()
        s.rollback = MagicMock()
        s.close = MagicMock()
        yield s

    app.dependency_overrides[get_session] = fake_session
    try:
        with TestClient(app, raise_server_exceptions=False) as c:
            yield c
    finally:
        app.dependency_overrides.pop(get_session, None)


APP_ID = "00000000-0000-0000-0000-0000000000aa"
DOC_ID = "00000000-0000-0000-0000-0000000000bb"

ENDPOINTS: list[tuple[str, str, dict | None, str]] = [
    ("GET", "/api/v1/health", None, "none"),

    ("POST", "/api/v1/kyc/submit", {"data": {}}, "caller"),
    ("GET", "/api/v1/applications", None, "caller"),
    ("GET", f"/api/v1/applications/{APP_ID}", None, "caller"),
    ("GET", f"/api/v1/applications/{APP_ID}/report", None, "caller"),
    ("GET", f"/api/v1/applications/{APP_ID}/documents/{DOC_ID}/details", None, "caller"),
    ("GET", f"/api/v1/applications/{APP_ID}/documents/{DOC_ID}/download", None, "caller"),
    ("GET", f"/api/v1/applications/{APP_ID}/documents/{DOC_ID}/file", None, "caller"),
    ("POST", f"/api/v1/applications/{APP_ID}/override", {"json": {"decision": "approve", "reason": "x"}}, "caller"),

    ("POST", "/api/v1/loan-origination/submit", {"data": {}}, "caller"),
    ("GET", f"/api/v1/loan-origination/{APP_ID}", None, "caller"),
    ("GET", f"/api/v1/loan-origination/{APP_ID}/report", None, "caller"),
    ("GET", f"/api/v1/loan-origination/{APP_ID}/documents/{DOC_ID}/details", None, "caller"),
    ("GET", f"/api/v1/loan-origination/{APP_ID}/documents/{DOC_ID}/download", None, "caller"),
    ("GET", f"/api/v1/loan-origination/{APP_ID}/documents/{DOC_ID}/file", None, "caller"),

    ("GET", "/api/v1/admin/me", None, "caller"),

    ("POST", "/api/v1/admin/apikeys", {"json": {"label": "x", "org": "x", "scopes": [], "rateLimitPerMin": 60}}, "admin"),
    ("GET", "/api/v1/admin/apikeys", None, "admin"),
    ("DELETE", f"/api/v1/admin/apikeys/{APP_ID}", None, "admin"),

    ("GET", "/api/v1/admin/applications", None, "admin"),
    ("GET", f"/api/v1/admin/applications/{APP_ID}", None, "admin"),
    ("GET", f"/api/v1/admin/applications/{APP_ID}/timeline", None, "admin"),
    ("GET", f"/api/v1/admin/applications/{APP_ID}/documents/{DOC_ID}/presigned-url", None, "admin"),
    ("GET", "/api/v1/admin/stats", None, "admin"),
    ("POST", f"/api/v1/admin/applications/{APP_ID}/override", {"json": {"decision": "approve", "reason": "x"}}, "admin"),
    ("GET", "/api/v1/admin/policy/thresholds", None, "admin"),
    ("PUT", "/api/v1/admin/policy/thresholds/test_key", {"json": {"value": 0.5, "reason": "x"}}, "admin"),
    ("GET", f"/api/v1/admin/applications/{APP_ID}/extracted-fields", None, "admin"),
    ("PATCH", f"/api/v1/admin/applications/{APP_ID}/extracted-fields", {"json": {"field": "x", "newValue": "y", "reason": "z"}}, "admin"),
    ("POST", f"/api/v1/admin/applications/{APP_ID}/replay", {"json": {"reason": "x"}}, "admin"),

    ("GET", "/api/v1/admin/audit-log", None, "admin"),
]


def _send(client: TestClient, method: str, path: str, body: dict | None, headers: dict | None):
    method = method.upper()
    kwargs: dict = {"headers": headers or {}}
    if body:
        kwargs.update(body)
    fn = getattr(client, method.lower())
    return fn(path, **kwargs)


@pytest.mark.parametrize("method,path,body,auth", ENDPOINTS, ids=[f"{e[0]} {e[1]}" for e in ENDPOINTS])
def test_no_api_key(client, method, path, body, auth):
    res = _send(client, method, path, body, headers=None)
    if auth == "none":
        assert res.status_code == 200, (
            f"public endpoint {method} {path} should 200 without auth, got {res.status_code} "
            f"body={res.text[:120]}"
        )
    else:
        assert res.status_code == 401, (
            f"protected endpoint {method} {path} should 401 without X-API-Key, got {res.status_code} "
            f"body={res.text[:120]}"
        )


@pytest.mark.parametrize("method,path,body,auth", ENDPOINTS, ids=[f"{e[0]} {e[1]}" for e in ENDPOINTS])
def test_invalid_api_key(client, method, path, body, auth):
    headers = {"X-API-Key": INVALID_KEY}
    res = _send(client, method, path, body, headers=headers)
    if auth == "none":
        assert res.status_code == 200, (
            f"public endpoint {method} {path} should 200 with invalid X-API-Key, got {res.status_code}"
        )
    else:
        assert res.status_code == 401, (
            f"protected endpoint {method} {path} should 401 with invalid X-API-Key, got {res.status_code} "
            f"body={res.text[:120]}"
        )


@pytest.mark.parametrize("method,path,body,auth", ENDPOINTS, ids=[f"{e[0]} {e[1]}" for e in ENDPOINTS])
def test_caller_key_without_admin_scope(client, method, path, body, auth):
    headers = {"X-API-Key": CALLER_KEY}
    res = _send(client, method, path, body, headers=headers)

    if auth == "admin":
        assert res.status_code == 403, (
            f"admin endpoint {method} {path} should 403 for caller (no admin scope), "
            f"got {res.status_code} body={res.text[:120]}"
        )
    elif auth == "caller":
        assert res.status_code not in (401, 403), (
            f"caller endpoint {method} {path} should accept a valid caller key (not 401/403), "
            f"got {res.status_code} body={res.text[:120]}"
        )
    else:
        assert res.status_code == 200


@pytest.mark.parametrize(
    "method,path,body,auth",
    [e for e in ENDPOINTS if e[3] == "admin"],
    ids=[f"{e[0]} {e[1]}" for e in ENDPOINTS if e[3] == "admin"],
)
def test_admin_key_passes_scope_filter(client, method, path, body, auth):
    headers = {"X-API-Key": ADMIN_KEY_ACME}
    res = _send(client, method, path, body, headers=headers)
    assert res.status_code not in (401, 403), (
        f"admin endpoint {method} {path} should let admin-scoped key past the auth filter, "
        f"got {res.status_code} body={res.text[:120]}"
    )


def test_org_scope_documented():
    from src.auth import AuthContext, SCOPE_ADMIN
    ctx = AuthContext(api_key_id=uuid.uuid4(), org_name="acme", label="x", scopes=["submit"])
    assert ctx.has_scope(SCOPE_ADMIN) is False
    ctx_admin = AuthContext(api_key_id=uuid.uuid4(), org_name="acme", label="x", scopes=["admin"])
    assert ctx_admin.has_scope(SCOPE_ADMIN) is True


def test_catalogue_total_count_matches_phase0_report():
    expected = {"none": 1, "caller": 15, "admin": 15}
    actual = {
        "none": sum(1 for e in ENDPOINTS if e[3] == "none"),
        "caller": sum(1 for e in ENDPOINTS if e[3] == "caller"),
        "admin": sum(1 for e in ENDPOINTS if e[3] == "admin"),
    }
    assert actual == expected, f"endpoint catalogue drift: expected {expected}, got {actual}"
