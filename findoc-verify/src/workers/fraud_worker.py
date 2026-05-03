from __future__ import annotations

import uuid

from sqlalchemy import delete, select

from src.db.models import CrossDocValidation, FraudResult, FraudSeverity
from src.db.session import SessionLocal
from src.logging_config import get_logger
from src.messaging.sns_publisher import get_publisher
from src.messaging.sqs_consumer import SqsConsumer
from src.messaging.topics import EVT_FRAUD_CHECKED, QUEUE_FRAUD, TOPIC_FRAUD_CHECKED
from src.pipeline.fraud import aggregate_fraud_score, run_all_signals
from src.services.pipeline_events_service import track_step
from src.workers.aggregate_worker import build_application_view

logger = get_logger(__name__)

def _sev(s: str) -> FraudSeverity:
    return {"low": FraudSeverity.low, "med": FraudSeverity.med, "high": FraudSeverity.high}[s]

class FraudWorker(SqsConsumer):
    queue_name = QUEUE_FRAUD
    worker_name = "fraud"

    async def handle(self, event: dict) -> None:
        payload = event["payload"]
        application_id = uuid.UUID(payload["applicationId"])
        run_number = int(payload.get("runNumber") or 1)

        async with track_step("fraud", application_id):
            view = await build_application_view(application_id)
            if view is None:
                logger.warning("fraud: view incomplete")
                return

            async with SessionLocal() as session:
                cross_rows = list(
                    (await session.execute(
                        select(CrossDocValidation).where(CrossDocValidation.application_id == application_id)
                    )).scalars()
                )
            cross_results = [{"rule_name": c.rule_name, "status": c.status.value, "details": c.details} for c in cross_rows]

            signals = run_all_signals(view, cross_results)
            overall = aggregate_fraud_score(signals)

            async with SessionLocal() as session:
                await session.execute(delete(FraudResult).where(FraudResult.application_id == application_id))
                for s in signals:
                    session.add(FraudResult(
                        id=uuid.uuid4(),
                        application_id=application_id,
                        signal_name=s["signal_name"],
                        severity=_sev(s["severity"]),
                        score=float(s["score"]),
                        details=s["details"],
                    ))
                await session.commit()

            get_publisher().publish(
                topic_name=TOPIC_FRAUD_CHECKED,
                event_type=EVT_FRAUD_CHECKED,
                payload={
                    "applicationId": str(application_id),
                    "runNumber": run_number,
                    "overallFraudScore": overall,
                },
                deduplication_id=str(uuid.uuid5(
                    uuid.NAMESPACE_URL, f"findoc/fraud/{application_id}/run/{run_number}"
                )),
            )
