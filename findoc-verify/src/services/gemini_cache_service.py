"""Postgres-backed cache for Gemini LLM responses."""
from __future__ import annotations

import hashlib
from datetime import datetime, timezone
from functools import lru_cache
from typing import Any

from sqlalchemy import create_engine, select, update
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.orm import Session, sessionmaker

from src.config import get_settings
from src.db.models import GeminiCache
from src.logging_config import get_logger

logger = get_logger(__name__)


@lru_cache(maxsize=1)
def _sync_engine():
    s = get_settings()
    return create_engine(s.alembic_database_url, pool_pre_ping=True, pool_size=5, max_overflow=5)


@lru_cache(maxsize=1)
def _sync_session_factory():
    return sessionmaker(bind=_sync_engine(), expire_on_commit=False, class_=Session)


def compute_key(*, model: str, prompt_version: str, content: str) -> str:
    content_hash = hashlib.sha256(content.encode("utf-8")).hexdigest()
    composite = f"{model}:{prompt_version}:{content_hash}"
    return hashlib.sha256(composite.encode("utf-8")).hexdigest()


def get(cache_key: str, *, session: Session | None = None) -> dict[str, Any] | None:
    own = session is None
    s = session or _sync_session_factory()()
    try:
        row = s.execute(select(GeminiCache).where(GeminiCache.cache_key == cache_key)).scalar_one_or_none()
        if row is None:
            return None
        try:
            s.execute(
                update(GeminiCache)
                .where(GeminiCache.cache_key == cache_key)
                .values(hit_count=GeminiCache.hit_count + 1, last_hit_at=datetime.now(timezone.utc))
            )
            if own:
                s.commit()
        except Exception:
            logger.exception("gemini_cache hit-count update failed (ignored)")
            if own:
                s.rollback()
        return dict(row.response_json or {})
    finally:
        if own:
            s.close()


def put(
    cache_key: str,
    prompt_version: str,
    model_name: str,
    response_json: dict[str, Any],
    prompt_tokens_in: int,
    tokens_out: int,
    *,
    session: Session | None = None,
) -> None:
    own = session is None
    s = session or _sync_session_factory()()
    try:
        stmt = pg_insert(GeminiCache).values(
            cache_key=cache_key,
            prompt_version=prompt_version,
            model_name=model_name,
            response_json=response_json,
            prompt_tokens_in=prompt_tokens_in,
            tokens_out=tokens_out,
        ).on_conflict_do_update(
            index_elements=["cache_key"],
            set_={
                "prompt_version": prompt_version,
                "model_name": model_name,
                "response_json": response_json,
                "prompt_tokens_in": prompt_tokens_in,
                "tokens_out": tokens_out,
            },
        )
        s.execute(stmt)
        if own:
            s.commit()
    except Exception:
        logger.exception("gemini_cache put failed (ignored)")
        if own:
            s.rollback()
    finally:
        if own:
            s.close()
