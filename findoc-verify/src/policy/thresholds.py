"""Admin-tunable numeric thresholds backed by the `policy_thresholds` table.

Threshold keys consumed by the pipeline (defaults shipped in migration 006):

  Compliance:
    bank_holder_name_match_min      85.0
    credit_score_min                650.0
    payslip_period_months           3.0
    dti_max_ratio                   0.55
    income_cv_max                   0.40
    bank_bounce_max                 3.0
    itr_payslip_deviation_max       0.30
    emi_burden_max                  0.50
    id_min_short_side_px            600.0
    ocr_confidence_min              0.80

  Risk recommendation (src/pipeline/risk.py):
    recommendation_approve_min_score    0.85
    recommendation_reject_max_score     0.45

Caching: 60-second in-process TTL. `set` invalidates the cache so the next read
reflects the change. `preload` is used by workers to fetch all current values
once per pipeline run, then passed as a plain dict via `app["_thresholds"]`.
"""
from __future__ import annotations

import time
from typing import Any

from sqlalchemy import select
from sqlalchemy.dialects.postgresql import insert as pg_insert

from src.db.models import PolicyThreshold
from src.db.session import SessionLocal

_CACHE_TTL_SECONDS = 60.0

class ThresholdStore:
    def __init__(self) -> None:
        self._cache: dict[str, float] = {}
        self._loaded_at: float = 0.0

    async def _refresh_if_stale(self) -> None:
        if time.monotonic() - self._loaded_at < _CACHE_TTL_SECONDS and self._cache:
            return
        async with SessionLocal() as session:
            rows = list((await session.execute(select(PolicyThreshold))).scalars())
        self._cache = {r.key: float(r.value) for r in rows}
        self._loaded_at = time.monotonic()

    async def get(self, key: str, default: float) -> float:
        await self._refresh_if_stale()
        v = self._cache.get(key)
        return float(v) if v is not None else float(default)

    async def get_all(self) -> dict[str, float]:
        await self._refresh_if_stale()
        return dict(self._cache)

    async def preload(self) -> dict[str, float]:
        return await self.get_all()

    async def set(self, key: str, value: float, actor: str | None) -> None:
        async with SessionLocal() as session:
            stmt = pg_insert(PolicyThreshold).values(key=key, value=float(value), updated_by=actor)
            stmt = stmt.on_conflict_do_update(
                index_elements=[PolicyThreshold.key],
                set_={"value": float(value), "updated_by": actor},
            )
            await session.execute(stmt)
            await session.commit()
        self._cache.pop(key, None)
        self._loaded_at = 0.0

_singleton: ThresholdStore | None = None

def get_store() -> ThresholdStore:
    global _singleton
    if _singleton is None:
        _singleton = ThresholdStore()
    return _singleton
