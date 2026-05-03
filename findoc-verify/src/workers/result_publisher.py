from __future__ import annotations

import uuid

from sqlalchemy import select

from src.db.models import (
    ApiKey,
    Application,
    ComplianceCheck,
    CrossDocValidation,
    DecisionOverride,
    Document,
    ExtractedField,
    FraudResult,
    VerificationReport,
)
from src.db.session import SessionLocal
from src.logging_config import get_logger
from src.messaging.sns_publisher import get_publisher
from src.messaging.sqs_consumer import SqsConsumer
from src.messaging.topics import (
    EVT_KYC_REPORT_READY,
    EVT_LOAN_REPORT_READY,
    QUEUE_RESULT,
    TOPIC_KYC_REPORT_READY,
    TOPIC_LOAN_REPORT_READY,
)
from src.services.pipeline_events_service import track_step

logger = get_logger(__name__)

def _topic_and_event(use_case: str) -> tuple[str, str]:
    if use_case == "kyc":
        return TOPIC_KYC_REPORT_READY, EVT_KYC_REPORT_READY
    return TOPIC_LOAN_REPORT_READY, EVT_LOAN_REPORT_READY

async def build_report_payload(
    application_id: uuid.UUID,
    override: DecisionOverride | None = None,
) -> dict | None:
    """Assemble the final Java-facing event payload for an application.
    Returns None if there's no verification_report yet (caller can skip).
    """
    async with SessionLocal() as session:
        app = (await session.execute(select(Application).where(Application.id == application_id))).scalar_one_or_none()
        if app is None:
            return None
        report = (
            await session.execute(
                select(VerificationReport).where(VerificationReport.application_id == application_id)
            )
        ).scalar_one_or_none()
        if report is None:
            return None

        caller_org: str | None = None
        if app.submitted_by_api_key_id:
            k = (await session.execute(
                select(ApiKey).where(ApiKey.id == app.submitted_by_api_key_id)
            )).scalar_one_or_none()
            caller_org = k.org_name if k else None

        compliance = [
            {"name": c.check_name, "status": c.status.value, "details": c.details}
            for c in (await session.execute(
                select(ComplianceCheck).where(ComplianceCheck.application_id == application_id)
            )).scalars()
        ]
        cross = [
            {"ruleName": c.rule_name, "status": c.status.value, "details": c.details}
            for c in (await session.execute(
                select(CrossDocValidation).where(CrossDocValidation.application_id == application_id)
            )).scalars()
        ]
        fraud = [
            {"signalName": f.signal_name, "severity": f.severity.value, "score": f.score, "details": f.details}
            for f in (await session.execute(
                select(FraudResult).where(FraudResult.application_id == application_id)
            )).scalars()
        ]

    recommendation = report.recommendation.value
    if override is not None:
        recommendation = override.new_recommendation.value

    payload: dict = {
        "schemaVersion": 1,
        "applicationId": str(application_id),
        "correlationId": app.external_id,
        "useCase": app.use_case.value,
        "status": app.status.value,
        "recommendation": recommendation,
        "overallScore": report.overall_score,
        "callerOrg": caller_org,
        "complianceChecks": compliance,
        "crossDocValidations": cross,
        "fraudSignals": fraud,
        "report": report.report_json,
    }

    if override is not None:
        payload["override"] = {
            "id": str(override.id),
            "previousRecommendation": override.previous_recommendation.value,
            "newRecommendation": override.new_recommendation.value,
            "reason": override.reason,
            "actorOrg": override.actor_org,
            "createdAt": override.created_at.isoformat() if override.created_at else None,
        }

    if app.use_case.value == "kyc":
        aad_fields: dict = {}
        pan_fields: dict = {}
        for c in compliance:
            det = c.get("details") or {}
            if c["name"] == "aadhaar_verhoeff":
                aad_fields["valid"] = c["status"] == "pass"
                aad_fields["last4"] = det.get("last4")
            if c["name"] == "pan_format":
                pan_fields["valid"] = c["status"] == "pass"
            if c["name"] == "name_pan_vs_aadhaar":
                payload.setdefault("kycDetails", {})["nameMatchScore"] = det.get("score")
            if c["name"] == "dob_consistency":
                payload.setdefault("kycDetails", {})["dobMatches"] = c["status"] == "pass"

        async with SessionLocal() as session:
            rows = (await session.execute(
                select(Document.doc_type, ExtractedField.field_name, ExtractedField.field_value)
                .join(ExtractedField, ExtractedField.document_id == Document.id)
                .where(Document.application_id == application_id)
                .where(ExtractedField.field_name.in_(("aadhaar_number", "pan_number")))
            )).all()
        for doc_type, field_name, field_value in rows:
            if field_name == "aadhaar_number" and field_value:
                aad_fields["number"] = field_value
            elif field_name == "pan_number" and field_value:
                pan_fields["number"] = field_value

        kyc_details = payload.setdefault("kycDetails", {})
        kyc_details["aadhaar"] = aad_fields or None
        kyc_details["pan"] = pan_fields or None

    return payload

async def publish_report(
    application_id: uuid.UUID,
    override: DecisionOverride | None = None,
    run_number: int = 1,
) -> bool:
    """Publish (or re-publish) the report to the Java-facing topic.

    Called by the result worker after risk_scored, and by the override endpoint
    when `notify=true`. Returns False if no report exists.
    """
    payload = await build_report_payload(application_id, override=override)
    if payload is None:
        return False

    payload["runNumber"] = run_number
    payload["replayed"] = run_number > 1
    payload["overridden"] = override is not None
    topic, event_type = _topic_and_event(payload["useCase"])
    dedup_suffix = (
        f"override/{override.id}" if override else f"final/run/{run_number}"
    )
    get_publisher().publish(
        topic_name=topic,
        event_type=event_type,
        payload=payload,
        deduplication_id=str(uuid.uuid5(
            uuid.NAMESPACE_URL, f"findoc/result/{application_id}/{dedup_suffix}"
        )),
    )
    logger.info(
        "published report-ready",
        extra={"application_id": str(application_id), "use_case": payload["useCase"],
               "topic": topic, "override": bool(override)},
    )
    return True

class ResultPublisher(SqsConsumer):
    """Final step. Re-publishes the full report envelope on the Java-facing
    topic (loan- or kyc-report-ready) once risk scoring is done.
    """
    queue_name = QUEUE_RESULT
    worker_name = "result"

    async def handle(self, event: dict) -> None:
        payload = event["payload"]
        application_id = uuid.UUID(payload["applicationId"])
        run_number = int(payload.get("runNumber") or 1)
        async with track_step("result_publish", application_id):
            ok = await publish_report(application_id, run_number=run_number)
            if not ok:
                logger.warning("result: no verification_report row for %s", application_id)
