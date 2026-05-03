from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Protocol

@dataclass
class ClassificationResult:
    doc_type: str
    confidence: float
    reasoning: str

@dataclass
class FieldValue:
    value: str
    confidence: float

class LlmProvider(Protocol):
    def classify_document(self, text: str, doc_type_options: list[str]) -> ClassificationResult: ...

    def extract_fields(
        self, text: str, fields_spec: dict[str, str], doc_type: str
    ) -> dict[str, FieldValue]: ...

    def chat_json(self, prompt: str, temperature: float = 0.1) -> dict[str, Any]: ...
