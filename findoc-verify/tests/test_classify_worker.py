"""Tests for ClassifyWorker.handle."""
from __future__ import annotations

import uuid
from contextlib import asynccontextmanager
from unittest.mock import MagicMock, patch

import pytest
from sqlalchemy.dialects import postgresql as pg_dialect

from src.pipeline.classifier import ClassifierOutput
from src.workers import classify_worker as cw


def _ocr_row(text: str = "permanent account number ABCPE1234F"):
    o = MagicMock()
    o.document_id = uuid.uuid4()
    o.raw_text = text
    return o


def _event(document_id: uuid.UUID, application_id: uuid.UUID) -> dict:
    return {
        "eventId": str(uuid.uuid4()),
        "eventType": "doc.ocr.completed",
        "payload": {
            "documentId": str(document_id),
            "applicationId": str(application_id),
        },
    }


class _SessionCtx:
    def __init__(self, scalar_one_value=None):
        self._v = scalar_one_value
        self.executed: list = []
        self.committed = False

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_exc):
        return False

    async def execute(self, stmt, *_a, **_kw):
        self.executed.append(stmt)
        r = MagicMock()
        r.scalar_one = MagicMock(return_value=self._v)
        return r

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


def _make_worker():
    with patch("boto3.client"), patch.object(cw.ClassifyWorker, "__init__", lambda self: None):
        return cw.ClassifyWorker()


@pytest.mark.asyncio
async def test_happy_path_persists_and_publishes(monkeypatch):
    doc_id = uuid.uuid4()
    app_id = uuid.uuid4()
    ocr = _ocr_row()
    ocr.document_id = doc_id

    select_ctx = _SessionCtx(scalar_one_value=ocr)
    insert_ctx = _SessionCtx()
    monkeypatch.setattr(cw, "SessionLocal", _SessionFactory([select_ctx, insert_ctx]))
    monkeypatch.setattr(cw, "track_step", _track_step_noop())

    monkeypatch.setattr(cw, "get_llm", lambda: MagicMock())
    monkeypatch.setattr(cw, "classify", lambda text, llm=None, application_id=None: ClassifierOutput(
        doc_type="pan", confidence=0.92, reasoning="keyword match", method="keyword",
    ))

    published: dict = {}

    class _Pub:
        def publish(self, topic_name, event_type, payload, deduplication_id=None):
            published.update(topic=topic_name, event=event_type, payload=payload)

    monkeypatch.setattr(cw, "get_publisher", lambda: _Pub())

    worker = _make_worker()
    await worker.handle(_event(doc_id, app_id))

    assert insert_ctx.committed is True
    assert published["topic"] == cw.TOPIC_DOC_CLASSIFIED
    assert published["event"] == cw.EVT_DOC_CLASSIFIED
    p = published["payload"]
    assert p["documentId"] == str(doc_id)
    assert p["applicationId"] == str(app_id)
    assert p["docType"] == "pan"
    assert p["classifiedType"] == "pan"
    assert p["confidence"] == 0.92
    assert p["method"] == "keyword"


@pytest.mark.asyncio
async def test_idempotent_replay_uses_upsert(monkeypatch):
    doc_id = uuid.uuid4()
    app_id = uuid.uuid4()
    ocr = _ocr_row()
    ocr.document_id = doc_id

    s1, i1, s2, i2 = _SessionCtx(scalar_one_value=ocr), _SessionCtx(), _SessionCtx(scalar_one_value=ocr), _SessionCtx()
    monkeypatch.setattr(cw, "SessionLocal", _SessionFactory([s1, i1, s2, i2]))
    monkeypatch.setattr(cw, "track_step", _track_step_noop())
    monkeypatch.setattr(cw, "get_llm", lambda: None)
    monkeypatch.setattr(cw, "classify", lambda text, llm=None, application_id=None: ClassifierOutput(
        doc_type="aadhaar", confidence=0.9, reasoning="kw", method="keyword",
    ))
    monkeypatch.setattr(cw, "get_publisher",
                        lambda: type("P", (), {"publish": lambda self, **kw: None})())

    worker = _make_worker()
    await worker.handle(_event(doc_id, app_id))
    await worker.handle(_event(doc_id, app_id))

    for ctx in (i1, i2):
        upsert = ctx.executed[-1]
        assert "ON CONFLICT" in str(upsert.compile(dialect=pg_dialect.dialect()))


@pytest.mark.asyncio
async def test_missing_documentId_raises(monkeypatch):
    monkeypatch.setattr(cw, "track_step", _track_step_noop())
    monkeypatch.setattr(cw, "SessionLocal", _SessionFactory([_SessionCtx()]))
    worker = _make_worker()
    with pytest.raises(KeyError):
        await worker.handle({"eventId": str(uuid.uuid4()),
                             "payload": {"applicationId": str(uuid.uuid4())}})


@pytest.mark.asyncio
async def test_llm_unavailable_falls_back_to_keyword(monkeypatch):
    doc_id = uuid.uuid4()
    app_id = uuid.uuid4()
    ocr = _ocr_row("AADHAAR card sample 234123412346")
    ocr.document_id = doc_id

    monkeypatch.setattr(cw, "SessionLocal",
                        _SessionFactory([_SessionCtx(scalar_one_value=ocr), _SessionCtx()]))
    monkeypatch.setattr(cw, "track_step", _track_step_noop())

    def _boom():
        raise RuntimeError("Gemini key missing")

    monkeypatch.setattr(cw, "get_llm", _boom)

    captured_kwargs: dict = {}

    def _classify(text, llm=None, application_id=None):
        captured_kwargs["llm"] = llm
        return ClassifierOutput(doc_type="aadhaar", confidence=0.85, reasoning="kw", method="keyword")

    monkeypatch.setattr(cw, "classify", _classify)
    monkeypatch.setattr(cw, "get_publisher",
                        lambda: type("P", (), {"publish": lambda self, **kw: None})())

    worker = _make_worker()
    await worker.handle(_event(doc_id, app_id))

    assert captured_kwargs["llm"] is None


@pytest.mark.asyncio
async def test_classify_failure_propagates(monkeypatch):
    doc_id = uuid.uuid4()
    app_id = uuid.uuid4()
    ocr = _ocr_row()
    ocr.document_id = doc_id

    monkeypatch.setattr(cw, "SessionLocal",
                        _SessionFactory([_SessionCtx(scalar_one_value=ocr)]))
    monkeypatch.setattr(cw, "track_step", _track_step_noop())
    monkeypatch.setattr(cw, "get_llm", lambda: MagicMock())

    def _boom(*_a, **_kw):
        raise RuntimeError("classifier crashed")

    monkeypatch.setattr(cw, "classify", _boom)

    publishes: list = []
    monkeypatch.setattr(cw, "get_publisher",
                        lambda: type("P", (), {"publish": lambda self, **kw: publishes.append(kw) or None})())

    worker = _make_worker()
    with pytest.raises(RuntimeError, match="classifier crashed"):
        await worker.handle(_event(doc_id, app_id))

    assert publishes == []
