from __future__ import annotations

import asyncio
import mimetypes
import uuid
from datetime import datetime

from sqlalchemy import select
from sqlalchemy.dialects.postgresql import insert as pg_insert

from src.db.models import Document, OcrResult
from src.db.session import SessionLocal
from src.logging_config import get_logger
from src.messaging.sns_publisher import get_publisher
from src.messaging.sqs_consumer import SqsConsumer
from src.messaging.topics import EVT_DOC_OCR_COMPLETED, QUEUE_OCR, TOPIC_DOC_OCR_COMPLETED
from src.providers.ocr.google_docai import get_provider as get_ocr
from src.services.pipeline_events_service import track_step
from src.storage.s3_storage import get_storage

logger = get_logger(__name__)

def _mime(filename: str) -> str:
    m, _ = mimetypes.guess_type(filename)
    return m or "application/pdf"

class OcrWorker(SqsConsumer):
    queue_name = QUEUE_OCR
    worker_name = "ocr"

    async def handle(self, event: dict) -> None:
        payload = event["payload"]
        document_id = uuid.UUID(payload["documentId"])
        application_id = uuid.UUID(payload["applicationId"])
        async with SessionLocal() as session:
            doc = (await session.execute(select(Document).where(Document.id == document_id))).scalar_one()

        async with track_step("ocr", application_id, document_id, {"docType": doc.doc_type.value}):
            file_bytes = get_storage().get_document(doc.file_key)
            result = get_ocr().extract(file_bytes, _mime(doc.original_filename))

            async with SessionLocal() as session:
                stmt = pg_insert(OcrResult).values(
                    id=uuid.uuid4(),
                    document_id=document_id,
                    raw_text=result.raw_text,
                    page_texts=result.page_texts,
                    provider=result.provider,
                    latency_ms=result.latency_ms,
                    avg_confidence=result.avg_confidence,
                ).on_conflict_do_update(
                    index_elements=["document_id"],
                    set_={
                        "raw_text": result.raw_text,
                        "page_texts": result.page_texts,
                        "provider": result.provider,
                        "latency_ms": result.latency_ms,
                        "avg_confidence": result.avg_confidence,
                    },
                )
                await session.execute(stmt)
                await session.commit()

            get_publisher().publish(
                topic_name=TOPIC_DOC_OCR_COMPLETED,
                event_type=EVT_DOC_OCR_COMPLETED,
                payload={
                    "applicationId": str(application_id),
                    "documentId": str(document_id),
                    "docType": doc.doc_type.value,
                    "pageCount": len(result.page_texts),
                    "avgConfidence": result.avg_confidence,
                },
            )
