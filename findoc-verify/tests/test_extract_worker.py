"""Tests for ExtractWorker.handle."""
from __future__ import annotations

import uuid
from contextlib import asynccontextmanager
from datetime import date
from unittest.mock import MagicMock, patch

import pytest
from sqlalchemy.dialects import postgresql as pg_dialect

from src.db.models import DocType, ExtractionMethod
from src.providers.llm.base import FieldValue
from src.workers import extract_worker as ew


def _make_doc(doc_type=DocType.pan):
    d = MagicMock()
    d.id = uuid.uuid4()
    d.application_id = uuid.uuid4()
    d.doc_type = doc_type
    d.original_filename = "x.pdf"
    return d


def _make_ocr(text: str):
    o = MagicMock()
    o.document_id = uuid.uuid4()
    o.raw_text = text
    return o


def _event(document_id: uuid.UUID, application_id: uuid.UUID) -> dict:
    return {
        "eventId": str(uuid.uuid4()),
        "eventType": "doc.classified",
        "payload": {
            "documentId": str(document_id),
            "applicationId": str(application_id),
        },
    }


class _SessionCtx:
    def __init__(self, scalar_one_values=None, scalar_one_or_none_values=None):
        self._so = list(scalar_one_values or [])
        self._sone = list(scalar_one_or_none_values or [])
        self.executed: list = []
        self.committed = False

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_exc):
        return False

    async def execute(self, stmt, *_a, **_kw):
        self.executed.append(stmt)
        r = MagicMock()
        if self._so:
            r.scalar_one = MagicMock(return_value=self._so.pop(0))
        if self._sone:
            r.scalar_one_or_none = MagicMock(return_value=self._sone.pop(0))
        return r

    async def commit(self):
        self.committed = True


class _SessionFactory:
    def __init__(self, contexts):
        self._contexts = list(contexts)
        self.opened = []

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
    with patch("boto3.client"), patch.object(ew.ExtractWorker, "__init__", lambda self: None):
        return ew.ExtractWorker()


@pytest.mark.asyncio
async def test_happy_path_persists_fields_and_publishes(monkeypatch):
    doc = _make_doc(DocType.pan)
    ocr = _make_ocr("Permanent Account Number ABCPE1234F\nName: Raj Kumar")
    ocr.document_id = doc.id

    select_ctx = _SessionCtx(scalar_one_values=[doc, ocr],
                             scalar_one_or_none_values=[None])
    write_ctx = _SessionCtx()
    monkeypatch.setattr(ew, "SessionLocal", _SessionFactory([select_ctx, write_ctx]))
    monkeypatch.setattr(ew, "track_step", _track_step_noop())

    fake_llm = MagicMock()
    fake_llm.extract_fields = MagicMock(return_value={
        "full_name": FieldValue(value="Raj Kumar", confidence=0.91),
    })
    monkeypatch.setattr(ew, "get_llm", lambda: fake_llm)

    published: dict = {}
    monkeypatch.setattr(ew, "get_publisher",
                        lambda: type("P", (), {"publish":
                            lambda self, **kw: published.update(kw)})())

    worker = _make_worker()
    await worker.handle(_event(doc.id, doc.application_id))

    assert write_ctx.committed is True
    assert published["topic_name"] == ew.TOPIC_DOC_EXTRACTED
    assert published["event_type"] == ew.EVT_DOC_EXTRACTED
    p = published["payload"]
    assert p["documentId"] == str(doc.id)
    assert p["applicationId"] == str(doc.application_id)
    assert p["docType"] == "pan"
    assert p["fieldCount"] >= 1


@pytest.mark.asyncio
async def test_idempotent_replay_deletes_then_inserts(monkeypatch):
    doc = _make_doc(DocType.pan)
    ocr1 = _make_ocr("PAN ABCPE1234F")
    ocr1.document_id = doc.id
    ocr2 = _make_ocr("PAN ABCPE1234F")
    ocr2.document_id = doc.id

    s1 = _SessionCtx(scalar_one_values=[doc, ocr1], scalar_one_or_none_values=[None])
    w1 = _SessionCtx()
    s2 = _SessionCtx(scalar_one_values=[doc, ocr2], scalar_one_or_none_values=[None])
    w2 = _SessionCtx()
    monkeypatch.setattr(ew, "SessionLocal", _SessionFactory([s1, w1, s2, w2]))
    monkeypatch.setattr(ew, "track_step", _track_step_noop())
    monkeypatch.setattr(ew, "get_llm", lambda: MagicMock(extract_fields=lambda *_a, **_k: {}))
    monkeypatch.setattr(ew, "get_publisher",
                        lambda: type("P", (), {"publish": lambda self, **kw: None})())

    worker = _make_worker()
    await worker.handle(_event(doc.id, doc.application_id))
    await worker.handle(_event(doc.id, doc.application_id))

    for ctx in (w1, w2):
        first = ctx.executed[0]
        assert "DELETE" in str(first.compile(dialect=pg_dialect.dialect()))


@pytest.mark.asyncio
async def test_missing_documentId_raises(monkeypatch):
    monkeypatch.setattr(ew, "track_step", _track_step_noop())
    monkeypatch.setattr(ew, "SessionLocal", _SessionFactory([_SessionCtx()]))
    worker = _make_worker()
    with pytest.raises(KeyError):
        await worker.handle({"eventId": str(uuid.uuid4()),
                             "payload": {"applicationId": str(uuid.uuid4())}})


@pytest.mark.asyncio
async def test_llm_failure_does_not_block_publish(monkeypatch):
    doc = _make_doc(DocType.pan)
    ocr = _make_ocr("PAN ABCPE1234F")
    ocr.document_id = doc.id

    monkeypatch.setattr(ew, "SessionLocal", _SessionFactory([
        _SessionCtx(scalar_one_values=[doc, ocr], scalar_one_or_none_values=[None]),
        _SessionCtx(),
    ]))
    monkeypatch.setattr(ew, "track_step", _track_step_noop())

    fake_llm = MagicMock()
    fake_llm.extract_fields = MagicMock(side_effect=RuntimeError("rate limited"))
    monkeypatch.setattr(ew, "get_llm", lambda: fake_llm)

    published: dict = {}
    monkeypatch.setattr(ew, "get_publisher",
                        lambda: type("P", (), {"publish":
                            lambda self, **kw: published.update(kw)})())

    worker = _make_worker()
    await worker.handle(_event(doc.id, doc.application_id))

    assert published["topic_name"] == ew.TOPIC_DOC_EXTRACTED
    assert published["payload"]["fieldCount"] >= 1


@pytest.mark.asyncio
async def test_publisher_failure_propagates(monkeypatch):
    doc = _make_doc(DocType.pan)
    ocr = _make_ocr("PAN ABCPE1234F")
    ocr.document_id = doc.id

    monkeypatch.setattr(ew, "SessionLocal", _SessionFactory([
        _SessionCtx(scalar_one_values=[doc, ocr], scalar_one_or_none_values=[None]),
        _SessionCtx(),
    ]))
    monkeypatch.setattr(ew, "track_step", _track_step_noop())
    monkeypatch.setattr(ew, "get_llm",
                        lambda: MagicMock(extract_fields=lambda *_a, **_k: {}))

    class _BoomPub:
        def publish(self, **_kw):
            raise RuntimeError("SNS down")

    monkeypatch.setattr(ew, "get_publisher", lambda: _BoomPub())

    worker = _make_worker()
    with pytest.raises(RuntimeError, match="SNS down"):
        await worker.handle(_event(doc.id, doc.application_id))
