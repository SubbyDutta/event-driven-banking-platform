from __future__ import annotations

import hashlib
import secrets
import uuid
from datetime import datetime, timezone

from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from src.db.models import ApiKey

def _hash_key(raw: str) -> str:
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()

def _generate_raw_key() -> str:
    return "fdv_" + secrets.token_urlsafe(36)

async def create_api_key(
    session: AsyncSession,
    label: str,
    org_name: str,
    scopes: list[str] | None = None,
    rate_limit_per_min: int = 60,
) -> tuple[uuid.UUID, str]:
    raw = _generate_raw_key()
    row = ApiKey(
        id=uuid.uuid4(),
        key_hash=_hash_key(raw),
        label=label,
        org_name=org_name,
        scopes=list(scopes or []),
        rate_limit_per_min=rate_limit_per_min,
    )
    session.add(row)
    await session.flush()
    return row.id, raw

async def find_active_by_raw(session: AsyncSession, raw_key: str) -> ApiKey | None:
    stmt = select(ApiKey).where(
        ApiKey.key_hash == _hash_key(raw_key), ApiKey.revoked_at.is_(None)
    )
    res = await session.execute(stmt)
    return res.scalar_one_or_none()

async def touch_last_used(session: AsyncSession, key_id: uuid.UUID) -> None:
    await session.execute(
        update(ApiKey).where(ApiKey.id == key_id).values(last_used_at=datetime.now(timezone.utc))
    )

async def list_keys(session: AsyncSession) -> list[ApiKey]:
    res = await session.execute(select(ApiKey).order_by(ApiKey.created_at.desc()))
    return list(res.scalars().all())

async def revoke(session: AsyncSession, key_id: uuid.UUID) -> bool:
    res = await session.execute(
        update(ApiKey)
        .where(ApiKey.id == key_id, ApiKey.revoked_at.is_(None))
        .values(revoked_at=datetime.now(timezone.utc))
    )
    return (res.rowcount or 0) > 0
