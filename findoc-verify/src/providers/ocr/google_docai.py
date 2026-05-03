from __future__ import annotations

import time
from functools import lru_cache

from google.api_core.client_options import ClientOptions
from google.cloud import documentai_v1 as documentai

from src.config import get_settings
from src.logging_config import get_logger
from src.providers.ocr.base import OcrResult

logger = get_logger(__name__)

PROVIDER_NAME = "google_docai"
MAX_SYNC_PAGES = 15
DOCAI_REQUEST_TIMEOUT_SECONDS = 45.0

class GoogleDocAiProvider:
    def __init__(self) -> None:
        s = get_settings()
        opts = ClientOptions(api_endpoint=f"{s.google_docai_location}-documentai.googleapis.com")
        self._client = documentai.DocumentProcessorServiceClient(client_options=opts)
        self._processor_name = self._client.processor_path(
            s.google_project_id, s.google_docai_location, s.google_docai_processor_id
        )

    def extract(self, file_bytes: bytes, mime_type: str) -> OcrResult:
        start = time.monotonic()
        raw_doc = documentai.RawDocument(content=file_bytes, mime_type=mime_type)
        request = documentai.ProcessRequest(name=self._processor_name, raw_document=raw_doc)
        try:
            response = self._client.process_document(
                request=request, timeout=DOCAI_REQUEST_TIMEOUT_SECONDS
            )
        except Exception as e:
            logger.exception("DocAI process_document failed: %s", e)
            raise
        doc = response.document
        raw_text = doc.text or ""
        page_texts: list[str] = []
        confidences: list[float] = []
        for page in doc.pages:
            segments = []
            for layout in (page.paragraphs or []):
                la = layout.layout
                if la and la.text_anchor and la.text_anchor.text_segments:
                    for seg in la.text_anchor.text_segments:
                        start_i = int(seg.start_index or 0)
                        end_i = int(seg.end_index or 0)
                        segments.append(raw_text[start_i:end_i])
                if la and la.confidence:
                    confidences.append(float(la.confidence))
            page_texts.append("\n".join(segments) if segments else "")

        if not page_texts:
            page_texts = [raw_text]

        latency_ms = int((time.monotonic() - start) * 1000)
        avg_conf = (sum(confidences) / len(confidences)) if confidences else None
        return OcrResult(
            raw_text=raw_text,
            page_texts=page_texts,
            latency_ms=latency_ms,
            provider=PROVIDER_NAME,
            avg_confidence=avg_conf,
        )

@lru_cache(maxsize=1)
def get_provider() -> GoogleDocAiProvider:
    return GoogleDocAiProvider()
