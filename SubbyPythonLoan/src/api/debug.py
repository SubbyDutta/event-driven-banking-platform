from __future__ import annotations

from fastapi import APIRouter, HTTPException, Request, status
from pydantic import BaseModel

from src.messaging.schemas import LoanRiskFeatures, NonRetriableError

router = APIRouter()

class DebugPredictRequest(BaseModel):
    amountRequested: float
    features: LoanRiskFeatures

@router.get("/health")
async def health(request: Request) -> dict:
    predictor = getattr(request.app.state, "predictor", None)
    return {
        "status": "ok",
        "model_version": predictor.version if predictor else None,
    }

@router.post("/api/v1/debug/predict", tags=["debug"])
async def debug_predict(body: DebugPredictRequest, request: Request) -> dict:
    """Sync prediction for local dev only.

    Does NOT publish an SNS event — use SQS for that path. Useful for curl-testing
    threshold tuning without touching LocalStack.
    """
    predictor = getattr(request.app.state, "predictor", None)
    if predictor is None:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="predictor not ready"
        )
    inputs = {**body.features.model_dump(exclude_none=True), "amountRequested": body.amountRequested}
    try:
        return predictor.predict(inputs)
    except NonRetriableError as e:
        raise HTTPException(status_code=400, detail=e.reason) from e
