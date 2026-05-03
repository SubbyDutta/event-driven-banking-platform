from __future__ import annotations

import uuid

from sqlalchemy import delete

from src.db.models import CheckStatus, ComplianceCheck
from src.db.session import SessionLocal
from src.logging_config import get_logger
from src.messaging.sns_publisher import get_publisher
from src.messaging.sqs_consumer import SqsConsumer
from src.messaging.topics import EVT_COMPLIANCE_CHECKED, QUEUE_COMPLIANCE, TOPIC_COMPLIANCE_CHECKED
from src.pipeline.compliance import run_all_checks
from src.policy.thresholds import get_store
from src.services.pipeline_events_service import track_step
from src.workers.aggregate_worker import build_application_view

logger = get_logger(__name__)

def _status(s: str) -> CheckStatus:
    return CheckStatus.pass_ if s == "pass" else (CheckStatus.fail if s == "fail" else CheckStatus.warning)

class ComplianceWorker(SqsConsumer):
    queue_name = QUEUE_COMPLIANCE
    worker_name = "compliance"

    async def handle(self, event: dict) -> None:
        payload = event["payload"]
        application_id = uuid.UUID(payload["applicationId"])
        run_number = int(payload.get("runNumber") or 1)

        async with track_step("compliance", application_id):
            view = await build_application_view(application_id)
            if view is None:
                logger.warning("compliance: application view incomplete, aborting")
                return

            use_case = view.get("use_case", "loan")
            view["_thresholds"] = await get_store().preload()
            results = run_all_checks(view, use_case=use_case)

            async with SessionLocal() as session:
                await session.execute(delete(ComplianceCheck).where(ComplianceCheck.application_id == application_id))
                for r in results:
                    session.add(ComplianceCheck(
                        id=uuid.uuid4(),
                        application_id=application_id,
                        check_name=r["name"],
                        status=_status(r["status"]),
                        details={"severity": r.get("severity"), **r.get("details", {})},
                    ))
                await session.commit()

            counts = {"pass": 0, "fail": 0, "warning": 0}
            for r in results:
                counts[r["status"]] = counts.get(r["status"], 0) + 1

            get_publisher().publish(
                topic_name=TOPIC_COMPLIANCE_CHECKED,
                event_type=EVT_COMPLIANCE_CHECKED,
                payload={
                    "applicationId": str(application_id),
                    "useCase": use_case,
                    "runNumber": run_number,
                    "counts": counts,
                },
                deduplication_id=str(uuid.uuid5(
                    uuid.NAMESPACE_URL, f"findoc/compliance/{application_id}/run/{run_number}"
                )),
            )
