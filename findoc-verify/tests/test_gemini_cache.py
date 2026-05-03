"""Tests for the Gemini Postgres-backed response cache."""
from __future__ import annotations

from typing import Any
from unittest.mock import MagicMock

import pytest

from src.providers.llm import gemini as gemini_module
from src.services import gemini_cache_service as cache_service


def test_compute_key_is_deterministic_for_same_inputs():
    k1 = cache_service.compute_key(model="gemini-2.0-flash", prompt_version="v1", content="hello world")
    k2 = cache_service.compute_key(model="gemini-2.0-flash", prompt_version="v1", content="hello world")
    assert k1 == k2
    assert len(k1) == 64


def test_compute_key_changes_when_any_input_changes():
    base = cache_service.compute_key(model="gemini-2.0-flash", prompt_version="v1", content="hello")
    assert cache_service.compute_key(model="gemini-2.0-flash", prompt_version="v2", content="hello") != base
    assert cache_service.compute_key(model="gemini-1.5-pro", prompt_version="v1", content="hello") != base
    assert cache_service.compute_key(model="gemini-2.0-flash", prompt_version="v1", content="hello!") != base


class _FakeStore:
    def __init__(self) -> None:
        self.data: dict[str, dict[str, Any]] = {}
        self.put_calls = 0
        self.get_calls = 0

    def get(self, cache_key: str, *, session=None):
        self.get_calls += 1
        return self.data.get(cache_key)

    def put(self, cache_key, prompt_version, model_name, response_json,
            prompt_tokens_in, tokens_out, *, session=None) -> None:
        self.put_calls += 1
        self.data[cache_key] = dict(response_json)


@pytest.fixture
def fake_cache(monkeypatch):
    store = _FakeStore()
    monkeypatch.setattr(cache_service, "get", store.get)
    monkeypatch.setattr(cache_service, "put", store.put)
    monkeypatch.setattr(gemini_module.cache, "get", store.get)
    monkeypatch.setattr(gemini_module.cache, "put", store.put)
    return store


def test_get_returns_none_for_cold_key(fake_cache):
    assert cache_service.get("does-not-exist") is None


def test_put_then_get_returns_the_stored_response(fake_cache):
    key = cache_service.compute_key(model="m", prompt_version="v1", content="abc")
    cache_service.put(
        cache_key=key,
        prompt_version="v1",
        model_name="m",
        response_json={"doc_type": "pan", "confidence": 0.91, "reasoning": "x"},
        prompt_tokens_in=100,
        tokens_out=10,
    )
    got = cache_service.get(key)
    assert got == {"doc_type": "pan", "confidence": 0.91, "reasoning": "x"}


def _build_provider_without_sdk(monkeypatch) -> gemini_module.GeminiProvider:
    p = gemini_module.GeminiProvider.__new__(gemini_module.GeminiProvider)
    p._model_name = "gemini-2.0-flash"
    p._generation_config = {"temperature": 0.1, "response_mime_type": "application/json"}
    p._model = MagicMock()
    p._timeout = 30
    return p


def test_classify_document_hits_cache_on_second_call(fake_cache, monkeypatch):
    p = _build_provider_without_sdk(monkeypatch)

    sdk_calls = {"n": 0}

    def fake_generate(prompt: str, *, response_schema=None) -> str:
        sdk_calls["n"] += 1
        return '{"doc_type": "pan", "confidence": 0.95, "reasoning": "matched header"}'

    monkeypatch.setattr(p, "_generate", fake_generate)

    text = "Permanent Account Number ABCDE1234F belongs to Test User"
    options = ["aadhaar", "pan", "bank_statement"]

    out1 = p.classify_document(text, options)
    out2 = p.classify_document(text, options)

    assert out1.doc_type == "pan"
    assert out2.doc_type == "pan"
    assert sdk_calls["n"] == 1, "second identical call must be served from cache"
    assert fake_cache.put_calls == 1
    assert fake_cache.get_calls == 2


def test_extract_fields_hits_cache_on_second_call(fake_cache, monkeypatch):
    p = _build_provider_without_sdk(monkeypatch)

    sdk_calls = {"n": 0}

    def fake_generate(prompt: str, *, response_schema=None) -> str:
        sdk_calls["n"] += 1
        return (
            '{"pan": {"value": "ABCDE1234F", "confidence": 0.95},'
            ' "name": {"value": "Test User", "confidence": 0.9}}'
        )

    monkeypatch.setattr(p, "_generate", fake_generate)

    text = "PAN: ABCDE1234F\nName: Test User"
    spec = {"pan": "10-char PAN", "name": "Person name"}

    out1 = p.extract_fields(text, spec, "pan")
    out2 = p.extract_fields(text, spec, "pan")

    assert out1["pan"].value == "ABCDE1234F"
    assert out2["name"].value == "Test User"
    assert sdk_calls["n"] == 1
    assert fake_cache.put_calls == 1


def test_extract_fields_different_doc_type_misses_cache(fake_cache, monkeypatch):
    p = _build_provider_without_sdk(monkeypatch)

    sdk_calls = {"n": 0}

    def fake_generate(prompt: str, *, response_schema=None) -> str:
        sdk_calls["n"] += 1
        return '{"pan": {"value": "ABCDE1234F", "confidence": 0.9}}'

    monkeypatch.setattr(p, "_generate", fake_generate)

    text = "PAN: ABCDE1234F"
    spec = {"pan": "10-char PAN"}

    p.extract_fields(text, spec, "pan")
    p.extract_fields(text, spec, "aadhaar")

    assert sdk_calls["n"] == 2, "different doc_type must produce a different cache key"
