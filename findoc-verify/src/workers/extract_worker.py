from __future__ import annotations

import asyncio
import uuid
from datetime import datetime, date

from sqlalchemy import delete, select, update
from sqlalchemy.dialects.postgresql import insert as pg_insert

from src.db.models import DocClassification, Document, ExtractedField, ExtractionMethod, OcrResult
from src.db.session import SessionLocal
from src.logging_config import get_logger
from src.messaging.sns_publisher import get_publisher
from src.messaging.sqs_consumer import SqsConsumer
from src.messaging.topics import EVT_DOC_EXTRACTED, QUEUE_EXTRACT, TOPIC_DOC_EXTRACTED
from src.pipeline.extractor import (
    EXTRACTORS_BY_DOC_TYPE,
    LLM_FIELD_SPEC_BY_TYPE,
    extract_period_month,
)
from src.providers.llm.gemini import get_provider as get_llm
from src.services.pipeline_events_service import track_step

logger = get_logger(__name__)

_LLM_DOC_TYPES = {"payslip", "bank_statement", "itr", "employment_letter", "credit_report", "aadhaar", "pan"}

class ExtractWorker(SqsConsumer):
    queue_name = QUEUE_EXTRACT
    worker_name = "extract"

    async def handle(self, event: dict) -> None:
        payload = event["payload"]
        document_id = uuid.UUID(payload["documentId"])
        application_id = uuid.UUID(payload["applicationId"])

        async with SessionLocal() as session:
            doc = (await session.execute(select(Document).where(Document.id == document_id))).scalar_one()
            ocr = (await session.execute(select(OcrResult).where(OcrResult.document_id == document_id))).scalar_one()
            cls = (
                await session.execute(select(DocClassification).where(DocClassification.document_id == document_id))
            ).scalar_one_or_none()

        async with track_step("extract", application_id, document_id, {"docType": doc.doc_type.value}):
            await self._extract_and_publish(document_id, application_id, payload, doc, ocr)

    async def _extract_and_publish(
        self,
        document_id: uuid.UUID,
        application_id: uuid.UUID,
        payload: dict,
        doc,
        ocr,
    ) -> None:

        doc_type = doc.doc_type.value
        text = ocr.raw_text or ""

        regex_fields: dict[str, tuple[str, float]] = {}
        fn = EXTRACTORS_BY_DOC_TYPE.get(doc_type)
        if fn:
            for cand in fn(text):
                prev = regex_fields.get(cand.field_name)
                if prev is None or cand.confidence > prev[1]:
                    regex_fields[cand.field_name] = (cand.value, cand.confidence)

        llm_fields: dict[str, tuple[str, float]] = {}
        spec = LLM_FIELD_SPEC_BY_TYPE.get(doc_type, {})
        missing = [k for k in spec if k not in regex_fields]
        if missing and doc_type in _LLM_DOC_TYPES and text.strip():
            try:
                llm = get_llm()
                slim_spec = {k: spec[k] for k in missing}
                try:
                    vals = llm.extract_fields(text, slim_spec, doc_type, application_id=str(application_id))
                except TypeError:
                    vals = llm.extract_fields(text, slim_spec, doc_type)
                for k, fv in vals.items():
                    llm_fields[k] = (fv.value, fv.confidence)
            except Exception as e:
                logger.warning("LLM extraction failed: %s", e)

        period_month: date | None = None
        if doc_type in ("payslip", "bank_statement"):
            period_month = extract_period_month(text)

        async with SessionLocal() as session:
            await session.execute(delete(ExtractedField).where(ExtractedField.document_id == document_id))

            rows = []
            for name, (val, conf) in regex_fields.items():
                rows.append({
                    "id": uuid.uuid4(),
                    "document_id": document_id,
                    "field_name": name,
                    "field_value": val,
                    "confidence": conf,
                    "extraction_method": ExtractionMethod.regex,
                })
            for name, (val, conf) in llm_fields.items():
                rows.append({
                    "id": uuid.uuid4(),
                    "document_id": document_id,
                    "field_name": name,
                    "field_value": val,
                    "confidence": conf,
                    "extraction_method": ExtractionMethod.llm,
                })
            if rows:
                await session.execute(pg_insert(ExtractedField), rows)

            if period_month:
                await session.execute(
                    update(Document).where(Document.id == document_id).values(period_month=period_month)
                )
            await session.commit()

        get_publisher().publish(
            topic_name=TOPIC_DOC_EXTRACTED,
            event_type=EVT_DOC_EXTRACTED,
            payload={
                "applicationId": str(application_id),
                "documentId": str(document_id),
                "docType": doc_type,
                "fieldCount": len(regex_fields) + len(llm_fields),
                "periodMonth": period_month.isoformat() if period_month else None,
            },
        )
