from __future__ import annotations

import uuid
from decimal import Decimal

from sqlalchemy import select, update
from sqlalchemy.dialects.postgresql import insert as pg_insert

from src.db.models import (
    Application,
    ApplicationStatus,
    ComplianceCheck,
    CrossDocValidation,
    FraudResult,
    Recommendation,
    UseCase,
    VerificationReport,
)
from src.db.session import SessionLocal
from src.logging_config import get_logger
from src.messaging.sns_publisher import get_publisher
from src.messaging.sqs_consumer import SqsConsumer
from src.messaging.topics import EVT_RISK_SCORED, QUEUE_RISK, TOPIC_RISK_SCORED
from src.pipeline.risk import assemble_kyc_report, assemble_loan_report
from src.policy.thresholds import get_store
from src.services.pipeline_events_service import track_step
from src.workers.aggregate_worker import build_application_view

logger = get_logger(__name__)

_REC_MAP = {
    "approve": Recommendation.approve,
    "reject": Recommendation.reject,
    "manual_review": Recommendation.manual_review,
    "verified": Recommendation.verified,
}

_STATUS_MAP = {
    Recommendation.approve: ApplicationStatus.approved,
    Recommendation.verified: ApplicationStatus.approved,
    Recommendation.reject: ApplicationStatus.rejected,
    Recommendation.manual_review: ApplicationStatus.needs_review,
}

class RiskWorker(SqsConsumer):
    queue_name = QUEUE_RISK
    worker_name = "risk"

    async def handle(self, event: dict) -> None:
        payload = event["payload"]
        application_id = uuid.UUID(payload["applicationId"])
        run_number = int(payload.get("runNumber") or 1)

        async with track_step("risk", application_id):
            await self._compute_and_persist(application_id, run_number)

    async def _compute_and_persist(self, application_id: uuid.UUID, run_number: int = 1) -> None:
        view = await build_application_view(application_id)
        if view is None:
            logger.warning("risk: view incomplete")
            return

        async with SessionLocal() as session:
            compliance = [
                {"name": c.check_name, "status": c.status.value, "details": c.details, "severity": c.details.get("severity")}
                for c in (
                    await session.execute(
                        select(ComplianceCheck).where(ComplianceCheck.application_id == application_id)
                    )
                ).scalars()
            ]
            cross = [
                {"rule_name": c.rule_name, "status": c.status.value, "involved_doc_types": c.details.get("involved_doc_types", []), "details": c.details}
                for c in (
                    await session.execute(
                        select(CrossDocValidation).where(CrossDocValidation.application_id == application_id)
                    )
                ).scalars()
            ]
            fraud = [
                {"signal_name": f.signal_name, "severity": f.severity.value, "score": f.score, "details": f.details}
                for f in (
                    await session.execute(
                        select(FraudResult).where(FraudResult.application_id == application_id)
                    )
                ).scalars()
            ]

        use_case = view.get("use_case", "loan")
        view["_thresholds"] = await get_store().preload()
        if use_case == "kyc":
            report = assemble_kyc_report(view, compliance, cross, fraud)
        else:
            report = assemble_loan_report(view, compliance, cross, fraud)

        rec_enum = _REC_MAP[report["recommendation"]]
        monthly = (report.get("income") or {}).get("declared_monthly_inr")
        annual = (report.get("income") or {}).get("declared_annual_inr")
        dti = (report.get("debt") or {}).get("dti_ratio")
        credit = report.get("credit_score")

        async with SessionLocal() as session:
            stmt = pg_insert(VerificationReport).values(
                id=uuid.uuid4(),
                application_id=application_id,
                recommendation=rec_enum,
                overall_score=float(report["overall_score"]),
                income_monthly_inr=Decimal(str(round(monthly, 2))) if monthly else None,
                income_annual_inr=Decimal(str(round(annual, 2))) if annual else None,
                dti_ratio=dti if dti is not None else None,
                credit_score=credit,
                report_json=report,
            ).on_conflict_do_update(
                index_elements=["application_id"],
                set_={
                    "recommendation": rec_enum,
                    "overall_score": float(report["overall_score"]),
                    "income_monthly_inr": Decimal(str(round(monthly, 2))) if monthly else None,
                    "income_annual_inr": Decimal(str(round(annual, 2))) if annual else None,
                    "dti_ratio": dti if dti is not None else None,
                    "credit_score": credit,
                    "report_json": report,
                },
            )
            await session.execute(stmt)
            await session.execute(
                update(Application).where(Application.id == application_id).values(status=_STATUS_MAP[rec_enum])
            )
            await session.commit()

        get_publisher().publish(
            topic_name=TOPIC_RISK_SCORED,
            event_type=EVT_RISK_SCORED,
            payload={
                "applicationId": str(application_id),
                "useCase": use_case,
                "runNumber": run_number,
                "recommendation": report["recommendation"],
                "overallScore": report["overall_score"],
            },
            deduplication_id=str(uuid.uuid5(
                uuid.NAMESPACE_URL, f"findoc/risk/{application_id}/run/{run_number}"
            )),
        )
