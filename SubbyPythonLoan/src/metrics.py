from __future__ import annotations

from prometheus_client import Counter, Histogram

risk_predictions_total = Counter(
    "risk_predictions_total",
    "Loan risk predictions emitted, partitioned by decision and risk band.",
    ["decision", "risk_band"],
)

risk_prediction_duration_seconds = Histogram(
    "risk_prediction_duration_seconds",
    "Wall-clock time to run the loan risk predictor (includes feature mapping).",
    buckets=(0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5),
)

sqs_messages_failed_total = Counter(
    "sqs_messages_failed_total",
    "SQS messages that failed processing, partitioned by consumer and failure kind.",
    ["consumer", "kind"],
)

sqs_messages_processed_total = Counter(
    "sqs_messages_processed_total",
    "SQS messages successfully processed, partitioned by consumer.",
    ["consumer"],
)
