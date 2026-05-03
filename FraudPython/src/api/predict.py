from __future__ import annotations

import asyncio
import time
from typing import Any

from fastapi import APIRouter, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel

from src.logging_config import correlation_id_var, get_logger
from src.metrics import fraud_predictions_total, fraud_validation_failures_total
from src.model.predictor import TransactionFeatures

logger = get_logger(__name__)

router = APIRouter()

class BatchRequest(BaseModel):
    transactions: list[TransactionFeatures]

@router.post("/predict")
async def predict(batch: BatchRequest, request: Request) -> dict[str, Any]:
    predictor = request.app.state.predictor
    rows = [t.model_dump() for t in batch.transactions]

    started = time.perf_counter()
    loop = asyncio.get_running_loop()
    results = await loop.run_in_executor(None, predictor.predict_batch, rows)
    latency_ms = (time.perf_counter() - started) * 1000.0

    for r in results:
        fraud_predictions_total.labels(
            is_fraud=str(r["is_fraud"]),
            risk_band=r["risk_band"],
        ).inc()

    logger.info(
        "fraud batch scored",
        extra={
            "n": len(results),
            "latency_ms": round(latency_ms, 2),
            "correlation_id": correlation_id_var.get(),
            "any_fraud": any(r["is_fraud"] == 1 for r in results),
        },
    )

    return {"results": results}

async def validation_handler(request: Request, exc: RequestValidationError):
    fraud_validation_failures_total.inc()
    return JSONResponse(
        status_code=400,
        content={"error": "invalid_features", "details": exc.errors()},
    )
