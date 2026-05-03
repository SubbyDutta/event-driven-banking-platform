"""Tests for OcrWorker.handle."""
from __future__ import annotations

import uuid
from contextlib import asynccontextmanager
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from sqlalchemy.dialects import postgresql as pg_dialect

from src.db.models import DocType
from src.providers.ocr.base import OcrResult as OcrProviderResult
from src.workers import ocr_worker as ow


def _make_doc():
    doc = MagicMock()
    doc.id = uuid.uuid4()
    doc.application_id = uuid.uuid4()
    doc.doc_type = DocType.aadhaar
    doc.file_key = "uploads/abc.pdf"
    doc.original_filename = "aadhaar.pdf"
    return doc


def _make_event(document_id: uuid.UUID, application_id: uuid.UUID) -> dict:
    return {
        "eventId": str(uuid.uuid4()),
        "eventType": "doc.ocr.requested",
        "payload": {
            "documentId": str(document_id),
            "applicationId": str(application_id),
        },
    }


class _SessionCtx:
    def __init__(self, scalar_one_value=None):
        self._scalar_one_value = scalar_one_value
        self.executed: list = []
        self.committed = False

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_exc):
        return False

    async def execute(self, stmt, *_a, **_kw):
        self.executed.append(stmt)
        result = MagicMock()
        result.scalar_one = MagicMock(return_value=self._scalar_one_value)
        return result

    async def commit(self):
        self.committed = True


class _SessionFactory:
    def __init__(self, contexts: list[_SessionCtx]):
        self._contexts = list(contexts)
        self.opened: list[_SessionCtx] = []

    def __call__(self):
        ctx = self._contexts.pop(0)
        self.opened.append(ctx)
        return ctx


def _track_step_noop():
    @asynccontextmanager
    async def _ts(*_a, **_kw):
        yield
    return _ts


@pytest.mark.asyncio
async def test_happy_path_persists_ocr_and_publishes(monkeypatch):
    doc = _make_doc()
    event = _make_event(doc.id, doc.application_id)

    select_ctx = _SessionCtx(scalar_one_value=doc)
    insert_ctx = _SessionCtx()
    factory = _SessionFactory([select_ctx, insert_ctx])
    monkeypatch.setattr(ow, "SessionLocal", factory)
    monkeypatch.setattr(ow, "track_step", _track_step_noop())

    fake_storage = MagicMock()
    fake_storage.get_document = MagicMock(return_value=b"%PDF-1.4 fake")
    monkeypatch.setattr(ow, "get_storage", lambda: fake_storage)

    fake_ocr = MagicMock()
    fake_ocr.extract = MagicMock(return_value=OcrProviderResult(
        raw_text="hello world", page_texts=["hello world"],
        latency_ms=42, provider="google_docai", avg_confidence=0.95,
    ))
    monkeypatch.setattr(ow, "get_ocr", lambda: fake_ocr)

    published: dict = {}

    class _Pub:
        def publish(self, topic_name, event_type, payload, deduplication_id=None):
            published.update(topic=topic_name, event=event_type, payload=payload)

    monkeypatch.setattr(ow, "get_publisher", lambda: _Pub())

    with patch("boto3.client"):
        with patch.object(ow.OcrWorker, "__init__", lambda self: None):
            worker = ow.OcrWorker()

    await worker.handle(event)

    assert fake_ocr.extract.call_count == 1
    assert insert_ctx.committed is True
    assert published["topic"] == ow.TOPIC_DOC_OCR_COMPLETED
    assert published["event"] == ow.EVT_DOC_OCR_COMPLETED
    p = published["payload"]
    assert p["documentId"] == str(doc.id)
    assert p["applicationId"] == str(doc.application_id)
    assert p["docType"] == "aadhaar"
    assert p["pageCount"] == 1
    assert p["avgConfidence"] == 0.95


@pytest.mark.asyncio
async def test_idempotent_replay_uses_upsert(monkeypatch):
    doc = _make_doc()
    event = _make_event(doc.id, doc.application_id)

    select_ctx_1 = _SessionCtx(scalar_one_value=doc)
    insert_ctx_1 = _SessionCtx()
    select_ctx_2 = _SessionCtx(scalar_one_value=doc)
    insert_ctx_2 = _SessionCtx()
    factory = _SessionFactory([select_ctx_1, insert_ctx_1, select_ctx_2, insert_ctx_2])
    monkeypatch.setattr(ow, "SessionLocal", factory)
    monkeypatch.setattr(ow, "track_step", _track_step_noop())

    fake_storage = MagicMock()
    fake_storage.get_document = MagicMock(return_value=b"x")
    monkeypatch.setattr(ow, "get_storage", lambda: fake_storage)

    fake_ocr = MagicMock()
    fake_ocr.extract = MagicMock(return_value=OcrProviderResult(
        raw_text="t", page_texts=["t"], latency_ms=5,
        provider="google_docai", avg_confidence=0.9,
    ))
    monkeypatch.setattr(ow, "get_ocr", lambda: fake_ocr)

    publishes: list = []
    monkeypatch.setattr(ow, "get_publisher",
                        lambda: type("P", (), {"publish": lambda self, **kw: publishes.append(kw) or None})())

    with patch("boto3.client"), patch.object(ow.OcrWorker, "__init__", lambda self: None):
        worker = ow.OcrWorker()

    await worker.handle(event)
    await worker.handle(event)

    assert insert_ctx_1.committed and insert_ctx_2.committed
    for ctx in (insert_ctx_1, insert_ctx_2):
        upsert_stmt = ctx.executed[-1]
        assert "ON CONFLICT" in str(upsert_stmt.compile(dialect=pg_dialect.dialect()))


@pytest.mark.asyncio
async def test_missing_documentId_raises(monkeypatch):
    monkeypatch.setattr(ow, "track_step", _track_step_noop())
    monkeypatch.setattr(ow, "SessionLocal", _SessionFactory([_SessionCtx()]))

    with patch("boto3.client"), patch.object(ow.OcrWorker, "__init__", lambda self: None):
        worker = ow.OcrWorker()

    event = {"eventId": str(uuid.uuid4()), "payload": {"applicationId": str(uuid.uuid4())}}
    with pytest.raises(KeyError):
        await worker.handle(event)


@pytest.mark.asyncio
async def test_docai_failure_propagates(monkeypatch):
    doc = _make_doc()
    event = _make_event(doc.id, doc.application_id)

    select_ctx = _SessionCtx(scalar_one_value=doc)
    factory = _SessionFactory([select_ctx])
    monkeypatch.setattr(ow, "SessionLocal", factory)
    monkeypatch.setattr(ow, "track_step", _track_step_noop())

    fake_storage = MagicMock()
    fake_storage.get_document = MagicMock(return_value=b"x")
    monkeypatch.setattr(ow, "get_storage", lambda: fake_storage)

    boom = RuntimeError("DocAI 503")
    fake_ocr = MagicMock()
    fake_ocr.extract = MagicMock(side_effect=boom)
    monkeypatch.setattr(ow, "get_ocr", lambda: fake_ocr)

    publishes: list = []
    monkeypatch.setattr(ow, "get_publisher",
                        lambda: type("P", (), {"publish": lambda self, **kw: publishes.append(kw) or None})())

    with patch("boto3.client"), patch.object(ow.OcrWorker, "__init__", lambda self: None):
        worker = ow.OcrWorker()

    with pytest.raises(RuntimeError, match="DocAI 503"):
        await worker.handle(event)

    assert publishes == [], "must not publish doc.ocr.completed when OCR provider failed"
