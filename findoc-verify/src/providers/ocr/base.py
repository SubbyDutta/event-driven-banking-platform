from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol

@dataclass
class OcrResult:
    raw_text: str
    page_texts: list[str]
    latency_ms: int
    provider: str
    avg_confidence: float | None = None

class OcrProvider(Protocol):
    def extract(self, file_bytes: bytes, mime_type: str) -> OcrResult: ...
