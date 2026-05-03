"""Document-type classification."""
from __future__ import annotations

from dataclasses import dataclass

from src.db.models import DocType

ALLOWED_TYPES = [t.value for t in DocType]

@dataclass
class ClassifierOutput:
    doc_type: str
    confidence: float
    reasoning: str
    method: str

_KEYWORDS: list[tuple[str, list[str], float]] = [
    ("aadhaar", ["aadhaar", "आधार", "unique identification authority", "uidai"], 0.9),
    ("pan", ["permanent account number", "income tax department", "pan card"], 0.9),
    ("payslip", ["payslip", "pay slip", "salary slip", "net pay", "gross salary", "earnings", "deductions"], 0.8),
    ("bank_statement", ["account statement", "bank statement", "opening balance", "closing balance", "ifsc"], 0.85),
    ("employment_letter", ["employment letter", "offer letter", "appointment letter", "hereby confirm", "is employed"], 0.85),
    ("itr", ["assessment year", "itr-", "form 16", "income tax return", "taxable income", "gross total income"], 0.85),
    ("credit_report", ["cibil", "transunion", "credit score", "experian", "crif", "equifax", "dpd", "credit information"], 0.9),
]

def classify_by_keywords(text: str) -> ClassifierOutput | None:
    low = text.lower()
    best: tuple[str, float, str] | None = None
    for doc_type, needles, conf in _KEYWORDS:
        hits = [n for n in needles if n in low]
        if not hits:
            continue
        score = min(0.95, conf + 0.02 * (len(hits) - 1))
        if best is None or score > best[1]:
            best = (doc_type, score, f"matched keywords: {hits[:3]}")
    if best is None:
        return None
    return ClassifierOutput(doc_type=best[0], confidence=best[1], reasoning=best[2], method="keyword")

def classify(text: str, llm=None, *, application_id: str | None = None) -> ClassifierOutput:
    import logging
    kw = classify_by_keywords(text)
    if kw and kw.confidence >= 0.8:
        return kw
    if llm is None:
        return kw or ClassifierOutput(doc_type=ALLOWED_TYPES[0], confidence=0.1, reasoning="no match", method="keyword")
    try:
        try:
            llm_out = llm.classify_document(text, ALLOWED_TYPES, application_id=application_id)
        except TypeError:
            llm_out = llm.classify_document(text, ALLOWED_TYPES)
        return ClassifierOutput(
            doc_type=llm_out.doc_type,
            confidence=llm_out.confidence,
            reasoning=llm_out.reasoning,
            method="llm",
        )
    except Exception as e:
        logging.getLogger(__name__).warning("LLM classification failed; falling back to keyword: %s", e)
        return kw or ClassifierOutput(
            doc_type=ALLOWED_TYPES[0],
            confidence=0.1,
            reasoning=f"llm unavailable, keyword low-confidence",
            method="keyword",
        )
