from __future__ import annotations

import time
from typing import Any

import joblib
import numpy as np
import pandas as pd
from pydantic import BaseModel, ConfigDict, Field

from src.config import get_settings
from src.logging_config import get_logger
from src.metrics import (
    fraud_critical_overrides_total,
    fraud_prediction_duration_seconds,
)

logger = get_logger(__name__)

class TransactionFeatures(BaseModel):
    """Inbound row matching the 7 raw inputs the model was trained on. Derived
    features (high_amount, ratios, etc.) are computed in :meth:`_engineer`."""

    model_config = ConfigDict(extra="forbid")

    amount: float = Field(gt=0)
    hour: int = Field(ge=0, le=23)
    is_foreign: int = Field(ge=0, le=1, default=0)
    is_high_risk: int = Field(ge=0, le=1, default=0)
    userId: int = Field(ge=0)
    balance: float = Field(ge=0)
    avg_amount: float = Field(ge=0)

_MODEL_COLUMNS = [
    "amount", "hour", "is_foreign", "is_high_risk", "userId", "balance",
    "avg_amount", "high_amount", "night_transaction", "amount_hour_ratio",
    "foreign_high", "risk_high", "amount_to_avg_ratio", "balance_to_avg_ratio",
    "critical_low_balance",
]

class FraudPredictor:
    """Wraps the XGBoost classifier, runs the same feature-engineering pipeline
    used during training (see ``train.py``), and applies the
    ``critical_low_balance`` business override that supersedes the model's
    output when amount > threshold AND balance < mult*amount.

    The override is preserved verbatim from the legacy ``app.py`` — losing it
    would change observable behaviour for the Java caller.
    """

    def __init__(self, model: Any, version: str) -> None:
        self._model = model
        self.version = version
        s = get_settings()
        self._amount_threshold = s.amount_threshold
        self._critical_balance_mult = s.critical_balance_mult
        self._decision_threshold = s.fraud_decision_threshold

    @classmethod
    def load(cls) -> "FraudPredictor":
        s = get_settings()
        model = joblib.load(s.model_path)
        logger.info(
            "fraud predictor loaded",
            extra={"model_path": s.model_path, "version": s.model_version},
        )
        return cls(model=model, version=s.model_version)

    def _engineer(self, df: pd.DataFrame) -> pd.DataFrame:
        df = df.copy()
        df["high_amount"] = (df["amount"] > df["balance"] * 1.5).astype(int)
        df["night_transaction"] = ((df["hour"] < 6) | (df["hour"] > 22)).astype(int)
        df["amount_hour_ratio"] = df["amount"] / (df["hour"] + 1)
        df["foreign_high"] = df["is_foreign"] * df["high_amount"]
        df["risk_high"] = df["is_high_risk"] * df["high_amount"]
        df["amount_to_avg_ratio"] = df["amount"] / (df["avg_amount"] + 1)
        df["balance_to_avg_ratio"] = df["balance"] / (df["avg_amount"] + 1)
        df["critical_low_balance"] = (
            (df["amount"] > self._amount_threshold)
            & (df["balance"] < self._critical_balance_mult * df["amount"])
        ).astype(int)
        return df

    @staticmethod
    def _risk_band(prob: float) -> str:
        if prob < 0.20:
            return "LOW"
        if prob < 0.50:
            return "MEDIUM"
        if prob < 0.85:
            return "HIGH"
        return "CRITICAL"

    def predict_batch(self, rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
        """Score N transactions in a single XGBoost call. Always returns one
        result per input row, in the same order."""
        if not rows:
            return []

        with fraud_prediction_duration_seconds.time():
            df = pd.DataFrame(rows)
            df = self._engineer(df)

            X = df[_MODEL_COLUMNS]
            probs = self._model.predict_proba(X)[:, 1]
            labels = (probs > self._decision_threshold).astype(int)

            mask = df["critical_low_balance"].to_numpy().astype(bool)
            override_count = int(mask.sum())
            if override_count:
                probs = probs.copy()
                labels = labels.copy()
                probs[mask] = 0.99
                labels[mask] = 1
                fraud_critical_overrides_total.inc(override_count)

            results: list[dict[str, Any]] = []
            for i, row in df.iterrows():
                prob = float(probs[i])
                results.append(
                    {
                        "input": {k: row[k] for k in (
                            "amount", "hour", "is_foreign", "is_high_risk",
                            "userId", "balance", "avg_amount",
                        )},
                        "fraud_probability": prob,
                        "is_fraud": int(labels[i]),
                        "risk_band": self._risk_band(prob),
                        "model_version": self.version,
                        "override_applied": bool(mask[i]),
                    }
                )
            return results

def warm(predictor: FraudPredictor) -> None:
    """First predict_proba on a fresh XGBoost model is ~30x slower than
    subsequent calls due to lazy booster initialization — warm it at startup
    so the first real /predict call doesn't pay that cost on the request path."""
    sample = {
        "amount": 1000.0,
        "hour": 12,
        "is_foreign": 0,
        "is_high_risk": 0,
        "userId": 1,
        "balance": 5000.0,
        "avg_amount": 500.0,
    }
    t0 = time.perf_counter()
    try:
        predictor.predict_batch([sample])
    except Exception:
        logger.exception("predictor warm-up failed (non-fatal)")
        return
    logger.info("predictor warmed in %.3fs", time.perf_counter() - t0)
