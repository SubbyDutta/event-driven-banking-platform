from __future__ import annotations

import time
from typing import Any

import joblib
import numpy as np
import pandas as pd
from pydantic import BaseModel, ConfigDict, Field, ValidationError

from src.config import get_settings
from src.logging_config import get_logger
from src.messaging.schemas import NonRetriableError
from src.metrics import risk_prediction_duration_seconds

logger = get_logger(__name__)

_MODEL_FEATURES = ["income", "balance", "avg_transaction", "credit_score", "requested_amount"]

class LoanFeatures(BaseModel):
    """Validated feature row consumed by the XGBoost model. Field names and order
    must match `_MODEL_FEATURES`. Unknown keys are rejected so a misnamed feature
    can't silently drop to a model default."""

    model_config = ConfigDict(extra="forbid")

    income: float = Field(gt=0)
    balance: float = Field(ge=0)
    avg_transaction: float = Field(ge=0)
    credit_score: int = Field(ge=300, le=900)
    requested_amount: float = Field(gt=0)

class RiskPredictor:
    """Wraps the XGBoost classifier + scaler pair, adapts the rich inbound feature
    schema onto the 5 columns the model was trained on, and converts the model's
    *eligibility* probability into a *probability of default*.

    Bridge (documented gap — retrain to remove):
      - monthly_income        -> income
      - bank_avg_balance      -> balance  (falls back to monthly_income when absent)
      - monthly_income * 0.05 -> avg_transaction  (stand-in; model has no real signal for it)
      - credit_score          -> credit_score
      - amountRequested       -> requested_amount  (from payload, not features)
    """

    def __init__(self, model: Any, scaler: Any, version: str) -> None:
        self._model = model
        self._scaler = scaler
        self.version = version

    @classmethod
    def load(cls) -> "RiskPredictor":
        s = get_settings()
        model = joblib.load(s.model_path)
        scaler = joblib.load(s.scaler_path)
        logger.info("predictor loaded", extra={"model_path": s.model_path, "version": s.model_version})
        return cls(model=model, scaler=scaler, version=s.model_version)

    def _map_features(self, inputs: dict[str, Any]) -> dict[str, float]:
        try:
            monthly_income = float(inputs["monthly_income"])
            credit_score = float(inputs["credit_score"])
            amount_requested = float(inputs["amountRequested"])
        except (KeyError, TypeError, ValueError) as e:
            raise NonRetriableError(f"missing_or_invalid_feature:{e}") from e

        bank_balance_raw = inputs.get("bank_avg_balance")
        bank_balance = float(bank_balance_raw) if bank_balance_raw is not None else monthly_income

        return {
            "income": monthly_income,
            "balance": bank_balance,
            "avg_transaction": monthly_income * 0.05,
            "credit_score": credit_score,
            "requested_amount": amount_requested,
        }

    def _decision(self, pod: float) -> str:
        s = get_settings()
        if pod < s.pod_approve_threshold:
            return "approve"
        if pod < s.pod_reject_threshold:
            return "manual_review"
        return "reject"

    @staticmethod
    def _risk_band(pod: float) -> str:
        if pod < 0.05:
            return "A"
        if pod < 0.10:
            return "B"
        if pod < 0.20:
            return "C"
        if pod < 0.35:
            return "D"
        return "E"

    @staticmethod
    def _reason(pod: float, decision: str) -> str:
        if decision == "approve":
            return "low_probability_of_default"
        if decision == "reject":
            return "high_probability_of_default"
        return "borderline_probability_of_default"

    def predict(self, inputs: dict[str, Any]) -> dict[str, Any]:
        with risk_prediction_duration_seconds.time():
            features = self._map_features(inputs)
            try:
                validated = LoanFeatures(**features)
            except ValidationError as e:
                raise NonRetriableError(f"invalid_features:{e.errors()}") from e
            df = pd.DataFrame([validated.model_dump()], columns=_MODEL_FEATURES)
            scaled = self._scaler.transform(df)
            prob_eligible = float(self._model.predict_proba(scaled)[0][1])
            pod = float(np.clip(1.0 - prob_eligible, 0.0, 1.0))
            decision = self._decision(pod)
            band = self._risk_band(pod)
            return {
                "probability_of_default": pod,
                "decision": decision,
                "risk_band": band,
                "reason": self._reason(pod, decision),
                "featuresUsed": _MODEL_FEATURES,
                "modelVersion": self.version,
            }

def _warm_throwaway_call(predictor: RiskPredictor) -> None:
    try:
        predictor.predict(
            {"monthly_income": 50000.0, "credit_score": 700, "amountRequested": 100000.0, "bank_avg_balance": 30000.0}
        )
    except Exception:
        logger.exception("predictor warm-up failed (non-fatal)")

def warm(predictor: RiskPredictor) -> None:
    """First predict_proba on a fresh XGBoost model is ~30x slower than subsequent
    calls due to lazy booster initialization — warm it at startup so the first real
    SQS message doesn't pay that cost inside worker_poll loop latency budget."""
    t0 = time.perf_counter()
    _warm_throwaway_call(predictor)
    logger.info("predictor warmed in %.3fs", time.perf_counter() - t0)
