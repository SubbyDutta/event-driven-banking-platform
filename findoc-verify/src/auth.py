from __future__ import annotations

import time
import uuid
from collections import deque
from dataclasses import dataclass, field
from threading import Lock

from fastapi import Depends, Header, HTTPException, Request, status
from sqlalchemy.ext.asyncio import AsyncSession

from src.config import get_settings
from src.db.session import get_session
from src.services import apikey_service

SCOPE_SUBMIT = "submit"
SCOPE_ADMIN = "admin"
SCOPE_ADMIN_GLOBAL = "admin_global"

@dataclass
class AuthContext:
    api_key_id: uuid.UUID
    org_name: str
    label: str
    scopes: list[str] = field(default_factory=list)
    rate_limit_per_min: int = 60

    def has_scope(self, scope: str) -> bool:
        if not self.scopes:
            return True
        return scope in self.scopes or SCOPE_ADMIN_GLOBAL in self.scopes

_WINDOW_SECONDS = 60.0
_rl_lock = Lock()
_rl_hits: dict[uuid.UUID, deque[float]] = {}

def _rate_limit_check(key_id: uuid.UUID, per_min: int) -> bool:
    now = time.monotonic()
    cutoff = now - _WINDOW_SECONDS
    with _rl_lock:
        dq = _rl_hits.setdefault(key_id, deque())
        while dq and dq[0] < cutoff:
            dq.popleft()
        if len(dq) >= per_min:
            return False
        dq.append(now)
        return True

def _reset_rate_limits_for_tests() -> None:
    with _rl_lock:
        _rl_hits.clear()

async def require_caller(
    request: Request,
    x_api_key: str | None = Header(default=None, alias="X-API-Key"),
    session: AsyncSession = Depends(get_session),
) -> AuthContext:
    """Always require an active API key. Enforces per-key rate limit."""
    if not x_api_key:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Missing X-API-Key")

    key = await apikey_service.find_active_by_raw(session, x_api_key)
    if not key:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid or revoked API key")

    per_min = key.rate_limit_per_min or 60
    if not _rate_limit_check(key.id, per_min):
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail=f"Rate limit exceeded ({per_min}/min)",
            headers={"Retry-After": "60"},
        )

    await apikey_service.touch_last_used(session, key.id)
    return AuthContext(
        api_key_id=key.id,
        org_name=key.org_name,
        label=key.label,
        scopes=list(key.scopes or []),
        rate_limit_per_min=per_min,
    )

def require_scope(scope: str):
    """FastAPI dependency that enforces a scope on the active AuthContext."""

    async def _dep(auth: AuthContext = Depends(require_caller)) -> AuthContext:
        if not auth.has_scope(scope):
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail=f"API key missing required scope: {scope}",
            )
        return auth

    return _dep

async def require_admin_bootstrap_or_caller(
    request: Request,
    x_api_key: str | None = Header(default=None, alias="X-API-Key"),
    session: AsyncSession = Depends(get_session),
) -> AuthContext | None:
    """Admin-key management endpoints: open when ADMIN_BOOTSTRAP_MODE=true (so
    the first key can be minted), else require a caller with admin scope.
    """
    settings = get_settings()
    if settings.admin_bootstrap_mode and not x_api_key:
        return None
    ctx = await require_caller(request, x_api_key, session)
    if not ctx.has_scope(SCOPE_ADMIN):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="API key missing required scope: admin",
        )
    return ctx
