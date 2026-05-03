from __future__ import annotations

import uuid
from datetime import datetime, timezone
from typing import Any

from src.config import get_settings
from src.logging_config import get_logger
from src.messaging.schemas import NonRetriableError
from src.messaging.sns_publisher import SnsPublisher
from src.messaging.sqs_consumer import SqsConsumer
from src.messaging.topics import (
    EVT_LOAN_RISK_RESULT,
    QUEUE_RISK_REQUESTS,
    SCHEMA_VERSION,
    TOPIC_RISK_RESULT,
)
from src.metrics import risk_predictions_total
from src.model.predictor import RiskPredictor

logger = get_logger(__name__)

_REQUIRED_PAYLOAD_FIELDS = ("loanAppId", "amountRequested", "features")

class RiskWorker(SqsConsumer):
    queue_name = QUEUE_RISK_REQUESTS
    consumer_name = "subby-python-loan.risk_worker"

    def __init__(self, predictor: RiskPredictor, publisher: SnsPublisher) -> None:
        super().__init__()
        self.predictor = predictor
        self.publisher = publisher

    async def handle(self, event: dict[str, Any]) -> None:
        if event.get("eventType") and event["eventType"] != "LoanRiskRequested":
            raise NonRetriableError(f"unexpected_event_type:{event.get('eventType')}")

        payload = event.get("payload")
        if not isinstance(payload, dict):
            if all(field in event for field in _REQUIRED_PAYLOAD_FIELDS):
                payload = event
            else:
                raise NonRetriableError("missing_payload")
        for field in _REQUIRED_PAYLOAD_FIELDS:
            if field not in payload:
                raise NonRetriableError(f"missing_payload_field:{field}")
        features = payload.get("features")
        if not isinstance(features, dict):
            raise NonRetriableError("missing_features")

        model_inputs = {**features, "amountRequested": payload["amountRequested"]}
        result = self.predictor.predict(model_inputs)

        correlation_id = event.get("correlationId") or payload["loanAppId"]
        out = {
            "eventId": str(uuid.uuid4()),
            "occurredAt": datetime.now(timezone.utc).isoformat(),
            "schemaVersion": SCHEMA_VERSION,
            "eventType": EVT_LOAN_RISK_RESULT,
            "correlationId": correlation_id,
            "payload": {
                "loanAppId": payload["loanAppId"],
                "decision": result["decision"],
                "probability_of_default": result["probability_of_default"],
                "risk_band": result["risk_band"],
                "modelVersion": result["modelVersion"],
                "featuresUsed": result["featuresUsed"],
                "reason": result["reason"],
            },
        }

        topic = get_settings().sns_topic_result or TOPIC_RISK_RESULT
        self.publisher.publish(topic, out)

        risk_predictions_total.labels(
            decision=result["decision"], risk_band=result["risk_band"]
        ).inc()

        logger.info(
            "risk decision emitted",
            extra={
                "loan_app_id": payload["loanAppId"],
                "decision": result["decision"],
                "risk_band": result["risk_band"],
                "pod": result["probability_of_default"],
                "correlation_id": correlation_id,
            },
        )
