"""Pipeline event emission — best-effort observability row per (worker, message).

Every worker wraps its `handle()` body with `track_step()` so the admin timeline
endpoint can render a per-application pipeline diagram. Writes are best-effort:
a failure to persist an event row must NOT fail the actual handler work.
"""
from __future__ import annotations

import contextlib
import time
import uuid
from datetime import datetime, timezone
from typing import Any

from src.db.models import PipelineEvent
from src.db.session import SessionLocal
from src.logging_config import get_logger

logger = get_logger(__name__)

async def _insert(
    application_id: uuid.UUID,
    step_name: str,
    step_status: str,
    document_id: uuid.UUID | None,
    started_at: datetime,
    completed_at: datetime | None,
    duration_ms: int | None,
    details: dict[str, Any],
) -> None:
    try:
        async with SessionLocal() as session:
            session.add(PipelineEvent(
                id=uuid.uuid4(),
                application_id=application_id,
                step_name=step_name,
                step_status=step_status,
                document_id=document_id,
                started_at=started_at,
                completed_at=completed_at,
                duration_ms=duration_ms,
                details=details or {},
            ))
            await session.commit()
    except Exception:
        logger.exception("pipeline_events write failed (ignored)",
                         extra={"step": step_name, "application_id": str(application_id)})

@contextlib.asynccontextmanager
async def track_step(
    step_name: str,
    application_id: uuid.UUID,
    document_id: uuid.UUID | None = None,
    details: dict[str, Any] | None = None,
):
    """Async context manager: inserts a `started` row on enter, then a
    `completed` or `failed` row on exit. Accepts an optional details payload
    that gets merged into the completed/failed row.
    """
    start_wall = datetime.now(timezone.utc)
    start_mono = time.monotonic()
    await _insert(application_id, step_name, "started", document_id, start_wall, None, None, {})
    try:
        yield
    except Exception as e:
        end_wall = datetime.now(timezone.utc)
        dur_ms = int((time.monotonic() - start_mono) * 1000)
        await _insert(
            application_id, step_name, "failed", document_id, start_wall, end_wall, dur_ms,
            {**(details or {}), "error": str(e)[:500]},
        )
        raise
    end_wall = datetime.now(timezone.utc)
    dur_ms = int((time.monotonic() - start_mono) * 1000)
    await _insert(
        application_id, step_name, "completed", document_id, start_wall, end_wall, dur_ms,
        details or {},
    )
