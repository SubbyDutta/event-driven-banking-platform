from __future__ import annotations

import uuid

from sqlalchemy import delete

from src.db.models import CheckStatus, CrossDocValidation
from src.db.session import SessionLocal
from src.logging_config import get_logger
from src.messaging.sns_publisher import get_publisher
from src.messaging.sqs_consumer import SqsConsumer
from src.messaging.topics import EVT_CROSSDOC_VALIDATED, QUEUE_CROSSDOC, TOPIC_CROSSDOC_VALIDATED
from src.pipeline.cross_doc import run_all_rules
from src.services.pipeline_events_service import track_step
from src.workers.aggregate_worker import build_application_view

logger = get_logger(__name__)

def _status(s: str) -> CheckStatus:
    return CheckStatus.pass_ if s == "pass" else (CheckStatus.fail if s == "fail" else CheckStatus.warning)

class CrossDocWorker(SqsConsumer):
    queue_name = QUEUE_CROSSDOC
    worker_name = "crossdoc"

    async def handle(self, event: dict) -> None:
        payload = event["payload"]
        application_id = uuid.UUID(payload["applicationId"])
        run_number = int(payload.get("runNumber") or 1)

        async with track_step("crossdoc", application_id):
            view = await build_application_view(application_id)
            if view is None:
                logger.warning("crossdoc: view incomplete")
                return

            rules = run_all_rules(view, use_case=view.get("use_case", "loan"))

            async with SessionLocal() as session:
                await session.execute(
                    delete(CrossDocValidation).where(CrossDocValidation.application_id == application_id)
                )
                for r in rules:
                    session.add(CrossDocValidation(
                        id=uuid.uuid4(),
                        application_id=application_id,
                        rule_name=r["rule_name"],
                        status=_status(r["status"]),
                        doc_ids=[],
                        details={"involved_doc_types": r["involved_doc_types"], **r["details"]},
                    ))
                await session.commit()

            counts = {"pass": 0, "fail": 0, "warning": 0}
            for r in rules:
                counts[r["status"]] = counts.get(r["status"], 0) + 1

            get_publisher().publish(
                topic_name=TOPIC_CROSSDOC_VALIDATED,
                event_type=EVT_CROSSDOC_VALIDATED,
                payload={
                    "applicationId": str(application_id),
                    "runNumber": run_number,
                    "counts": counts,
                },
                deduplication_id=str(uuid.uuid5(
                    uuid.NAMESPACE_URL, f"findoc/crossdoc/{application_id}/run/{run_number}"
                )),
            )
