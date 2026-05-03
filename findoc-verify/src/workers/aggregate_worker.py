from __future__ import annotations

import uuid
from typing import Any

from sqlalchemy import func, select, update

from src.db.models import (
    Application,
    ApplicationStatus,
    Document,
    DocType,
    ExtractedField,
    ExtractedFieldOverride,
    OcrResult,
    PipelineRun,
)
from src.db.session import SessionLocal
from src.logging_config import get_logger
from src.messaging.sns_publisher import get_publisher
from src.messaging.sqs_consumer import SqsConsumer
from src.messaging.topics import (
    EVT_APPLICATION_AGGREGATED,
    QUEUE_AGGREGATE,
    TOPIC_APPLICATION_AGGREGATED,
)
from src.services.pipeline_events_service import track_step

logger = get_logger(__name__)

async def build_application_view(application_id: uuid.UUID) -> dict[str, Any] | None:
    """Assemble the dict shape used by compliance/cross_doc/fraud/risk.
    Returns None if not every required slot has been extracted yet.
    """
    async with SessionLocal() as session:
        app = (await session.execute(select(Application).where(Application.id == application_id))).scalar_one()
        docs = list((await session.execute(select(Document).where(Document.application_id == application_id))).scalars())
        if not docs:
            return None

        doc_ids = [d.id for d in docs]
        ocr_rows = {
            r.document_id: r
            for r in (
                await session.execute(select(OcrResult).where(OcrResult.document_id.in_(doc_ids)))
            ).scalars()
        }
        field_rows = list(
            (await session.execute(select(ExtractedField).where(ExtractedField.document_id.in_(doc_ids)))).scalars()
        )
        override_rows = list(
            (await session.execute(
                select(ExtractedFieldOverride)
                .where(ExtractedFieldOverride.application_id == application_id)
                .where(ExtractedFieldOverride.applied_to_run_id.is_not(None))
                .order_by(ExtractedFieldOverride.edited_at.asc())
            )).scalars()
        )

    fields_by_doc: dict[uuid.UUID, dict[str, str]] = {}
    for f in field_rows:
        fields_by_doc.setdefault(f.document_id, {})[f.field_name] = f.field_value

    for ov in override_rows:
        if ov.document_id is not None:
            target = fields_by_doc.setdefault(ov.document_id, {})
            target[ov.field_name] = ov.new_value
        else:
            for doc_id, fld in fields_by_doc.items():
                if ov.field_name in fld:
                    fld[ov.field_name] = ov.new_value

    for d in docs:
        if d.id not in ocr_rows:
            return None

    def _wrap(d: Document) -> dict:
        return {
            "document_id": str(d.id),
            "file_hash": d.file_hash,
            "ocr_avg_confidence": (ocr_rows.get(d.id).avg_confidence if ocr_rows.get(d.id) else None),
            "period_month": d.period_month.isoformat() if d.period_month else None,
            "fields": fields_by_doc.get(d.id, {}),
        }

    view: dict[str, Any] = {
        "application_id": str(app.id),
        "external_id": app.external_id,
        "use_case": app.use_case.value,
        "applicant_name": app.applicant_name,
        "applicant_dob": app.applicant_dob.isoformat() if app.applicant_dob else None,
        "email": app.email,
        "phone": app.phone,
        "documents": {
            "aadhaar": None,
            "pan": None,
            "bank_statements": [],
            "payslips": [],
            "employment_letter": None,
            "itr": None,
            "credit_report": None,
        },
    }
    for d in docs:
        w = _wrap(d)
        dt = d.doc_type
        if dt == DocType.aadhaar:
            view["documents"]["aadhaar"] = w
        elif dt == DocType.pan:
            view["documents"]["pan"] = w
        elif dt == DocType.bank_statement:
            view["documents"]["bank_statements"].append(w)
        elif dt == DocType.payslip:
            view["documents"]["payslips"].append(w)
        elif dt == DocType.employment_letter:
            view["documents"]["employment_letter"] = w
        elif dt == DocType.itr:
            view["documents"]["itr"] = w
        elif dt == DocType.credit_report:
            view["documents"]["credit_report"] = w
    return view

class AggregateWorker(SqsConsumer):
    """Fires once per doc-extracted; publishes application-aggregated only when
    every uploaded document has an OCR + extracted_fields row.
    """

    queue_name = QUEUE_AGGREGATE
    worker_name = "aggregate"

    async def handle(self, event: dict) -> None:
        payload = event["payload"]
        application_id = uuid.UUID(payload["applicationId"])

        async with track_step("aggregate", application_id):
            view = await build_application_view(application_id)
            if view is None:
                logger.info("aggregate: not all docs ready yet, waiting for more doc.extracted events")
                return

            async with SessionLocal() as session:
                await session.execute(
                    update(Application)
                    .where(Application.id == application_id)
                    .values(status=ApplicationStatus.processing)
                )
                run_number = int((await session.execute(
                    select(func.coalesce(func.max(PipelineRun.run_number), 1))
                    .where(PipelineRun.application_id == application_id)
                )).scalar() or 1)
                await session.commit()

            get_publisher().publish(
                topic_name=TOPIC_APPLICATION_AGGREGATED,
                event_type=EVT_APPLICATION_AGGREGATED,
                payload={
                    "applicationId": str(application_id),
                    "externalId": view["external_id"],
                    "useCase": view["use_case"],
                    "runNumber": run_number,
                    "documentCount": sum(
                        1 if v and not isinstance(v, list) else len(v or [])
                        for v in view["documents"].values()
                    ),
                },
                deduplication_id=str(uuid.uuid5(
                    uuid.NAMESPACE_URL, f"findoc/aggregated/{application_id}/run/{run_number}"
                )),
            )
