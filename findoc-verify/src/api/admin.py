from __future__ import annotations

import uuid

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from src.api.schemas import (
    ApiKeyCreateRequest,
    ApiKeyCreateResponse,
    ApiKeyListItem,
    MeResponse,
)
from src.auth import AuthContext, require_admin_bootstrap_or_caller, require_caller
from src.db.session import get_session
from src.services import apikey_service

router = APIRouter(prefix="/api/v1/admin", tags=["admin"])

@router.post("/apikeys", response_model=ApiKeyCreateResponse, status_code=status.HTTP_201_CREATED)
async def mint_api_key(
    body: ApiKeyCreateRequest,
    _auth=Depends(require_admin_bootstrap_or_caller),
    session: AsyncSession = Depends(get_session),
) -> ApiKeyCreateResponse:
    key_id, raw = await apikey_service.create_api_key(
        session, body.label, body.org, scopes=body.scopes, rate_limit_per_min=body.rateLimitPerMin
    )
    await session.commit()
    return ApiKeyCreateResponse(
        id=key_id, label=body.label, org=body.org,
        scopes=body.scopes, rateLimitPerMin=body.rateLimitPerMin, key=raw,
    )

@router.get("/apikeys", response_model=list[ApiKeyListItem])
async def list_api_keys(
    _auth=Depends(require_admin_bootstrap_or_caller),
    session: AsyncSession = Depends(get_session),
) -> list[ApiKeyListItem]:
    rows = await apikey_service.list_keys(session)
    return [
        ApiKeyListItem(
            id=r.id, label=r.label, org=r.org_name,
            scopes=list(r.scopes or []), rateLimitPerMin=r.rate_limit_per_min,
            createdAt=r.created_at, revokedAt=r.revoked_at, lastUsedAt=r.last_used_at,
        )
        for r in rows
    ]

@router.delete("/apikeys/{key_id}", status_code=status.HTTP_204_NO_CONTENT)
async def revoke_key(
    key_id: uuid.UUID,
    _auth=Depends(require_admin_bootstrap_or_caller),
    session: AsyncSession = Depends(get_session),
) -> None:
    ok = await apikey_service.revoke(session, key_id)
    if not ok:
        raise HTTPException(status_code=404, detail="Key not found or already revoked")

@router.get("/me", response_model=MeResponse)
async def whoami(auth: AuthContext = Depends(require_caller)) -> MeResponse:
    return MeResponse(apiKeyId=auth.api_key_id, label=auth.label, org=auth.org_name)
