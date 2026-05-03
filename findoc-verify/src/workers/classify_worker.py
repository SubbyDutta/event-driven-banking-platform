from __future__ import annotations

import asyncio
import uuid

from sqlalchemy import select
from sqlalchemy.dialects.postgresql import insert as pg_insert

from src.db.models import DocClassification, OcrResult
from src.db.session import SessionLocal
from src.logging_config import get_logger
from src.messaging.sns_publisher import get_publisher
from src.messaging.sqs_consumer import SqsConsumer
from src.messaging.topics import EVT_DOC_CLASSIFIED, QUEUE_CLASSIFY, TOPIC_DOC_CLASSIFIED
from src.pipeline.classifier import classify
from src.providers.llm.gemini import get_provider as get_llm
from src.services.pipeline_events_service import track_step

logger = get_logger(__name__)

class ClassifyWorker(SqsConsumer):
    queue_name = QUEUE_CLASSIFY
    worker_name = "classify"

    async def handle(self, event: dict) -> None:
        payload = event["payload"]
        document_id = uuid.UUID(payload["documentId"])
        application_id = uuid.UUID(payload["applicationId"])
        async with SessionLocal() as session:
            ocr = (await session.execute(select(OcrResult).where(OcrResult.document_id == document_id))).scalar_one()

        async with track_step("classify", application_id, document_id):
            try:
                llm = get_llm()
            except Exception:
                llm = None
            out = classify(ocr.raw_text, llm=llm, application_id=str(application_id))

            async with SessionLocal() as session:
                stmt = pg_insert(DocClassification).values(
                    id=uuid.uuid4(),
                    document_id=document_id,
                    classified_type=out.doc_type,
                    confidence=out.confidence,
                    reasoning=out.reasoning,
                ).on_conflict_do_update(
                    index_elements=["document_id"],
                    set_={
                        "classified_type": out.doc_type,
                        "confidence": out.confidence,
                        "reasoning": out.reasoning,
                    },
                )
                await session.execute(stmt)
                await session.commit()

            get_publisher().publish(
                topic_name=TOPIC_DOC_CLASSIFIED,
                event_type=EVT_DOC_CLASSIFIED,
                payload={
                    "applicationId": str(application_id),
                    "documentId": str(document_id),
                    "docType": out.doc_type,
                    "classifiedType": out.doc_type,
                    "confidence": out.confidence,
                    "method": out.method,
                },
            )
