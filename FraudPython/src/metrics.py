from __future__ import annotations

from prometheus_client import Counter, Histogram

fraud_predictions_total = Counter(
    "fraud_predictions_total",
    "Fraud predictions emitted, partitioned by decision and risk band.",
    ["is_fraud", "risk_band"],
)

fraud_prediction_duration_seconds = Histogram(
    "fraud_prediction_duration_seconds",
    "Wall-clock time to run the fraud predictor (includes feature engineering).",
    buckets=(0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5),
)

fraud_validation_failures_total = Counter(
    "fraud_validation_failures_total",
    "Inbound /predict requests rejected due to schema validation.",
)

fraud_critical_overrides_total = Counter(
    "fraud_critical_overrides_total",
    "Predictions where the critical_low_balance business rule overrode the model.",
)
