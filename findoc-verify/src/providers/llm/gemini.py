from __future__ import annotations

import json
import re
from functools import lru_cache
from typing import Any

import google.generativeai as genai
from tenacity import retry, retry_if_exception_type, stop_after_attempt, wait_exponential

from src.config import get_settings
from src.logging_config import get_logger
from src.providers.llm.base import ClassificationResult, FieldValue
from src.services import gemini_cache_service as cache

logger = get_logger(__name__)

PROMPT_VERSION_CLASSIFY = "v1"
PROMPT_VERSION_EXTRACT = "v1"

_JSON_FENCE = re.compile(r"```(?:json)?\s*(.+?)\s*```", re.DOTALL)


def _estimate_tokens(text: str) -> int:
    """Rough char/4 token estimate. Good enough for log dashboards."""
    return len(text) // 4 if text else 0


def _smart_snippet(text: str, *, max_chars: int, head_chars: int, tail_chars: int) -> str:
    """Stitch head + tail of OCR text so multi-page summary blocks are not dropped."""
    if not text:
        return ""
    if len(text) <= max_chars:
        return text
    head = text[:head_chars]
    tail = text[-tail_chars:]
    skipped = len(text) - head_chars - tail_chars
    return f"{head}\n\n[... {skipped} characters of intermediate pages omitted ...]\n\n{tail}"

_DOC_TYPE_GUIDANCE: dict[str, str] = {
    "aadhaar": (
        "Aadhaar cards have a photo, the name in Hindi above the English name, "
        "DOB or Year of Birth, gender (Male/Female), and the 12-digit UID grouped 4-4-4. "
        "The address is on the back side."
    ),
    "pan": (
        "PAN cards show the name, father's name, DOB and the 10-character PAN. "
        "Order on most cards: Name, Father's Name, DOB."
    ),
    "payslip": (
        "Indian payslips have an Earnings table (Basic, HRA, Conveyance, Special "
        "Allowance, ...) summed as 'Gross Earnings' / 'Total Earnings', a Deductions "
        "table (PF, Professional Tax, Income Tax, ...) summed as 'Total Deductions', "
        "and a final 'Net Pay' / 'Net Salary Payable' / 'Amount Payable' figure. "
        "Pick the SUM rows, not individual line items. The header carries the "
        "employee name, employee ID, PAN, and pay period (e.g. 'For the month of March 2024')."
    ),
    "bank_statement": (
        "Indian bank statements (HDFC, ICICI, SBI, Axis, Kotak, etc.) start with an "
        "account-summary block (Account Holder, A/c No, IFSC, Branch, Statement Period, "
        "Opening Balance, Closing Balance, Total Credits, Total Debits) and then list "
        "transactions across one or more pages. Salary credits show in narration as "
        "'NEFT/.../SAL ...', 'IMPS/.../SALARY/...', or 'BY SAL'. Closing balance "
        "appears as a running balance per row — use the value next to the LAST "
        "transaction or the explicit 'Closing Balance' summary row."
    ),
    "employment_letter": (
        "Employment letters are on company letterhead and confirm role, joining date, "
        "and either monthly or annual gross. Letter date is at the top; date of "
        "joining is mentioned in the body."
    ),
    "itr": (
        "Indian ITR / Form 16 has sections: Gross Total Income, Deductions u/s 80C/80D, "
        "Total Taxable Income (= Gross Total Income - Deductions), Tax on Total Income, "
        "Surcharge, Cess, Total Tax Paid. Assessment Year is shown as 'AY 2023-24' style. "
        "The taxpayer's PAN, name, and DOB are in the header."
    ),
    "credit_report": (
        "CIBIL / Experian / Equifax / CRIF reports carry the score (300-900) at the top, "
        "followed by personal info (PAN, DOB, name), then per-account loan details "
        "(EMI, outstanding, DPD). Sum active EMIs for existing_emi; pick the highest "
        "DPD across all accounts for dpd_flag."
    ),
}

def _first_json(text: str) -> dict[str, Any]:
    text = text.strip()
    m = _JSON_FENCE.search(text)
    if m:
        text = m.group(1)
    start = text.find("{")
    end = text.rfind("}")
    if start != -1 and end != -1 and end > start:
        text = text[start : end + 1]
    return json.loads(text)


def _classify_response_schema(doc_type_options: list[str]) -> dict[str, Any]:
    return {
        "type": "object",
        "properties": {
            "doc_type": {"type": "string", "enum": list(doc_type_options) or ["unknown"]},
            "confidence": {"type": "number"},
            "reasoning": {"type": "string"},
        },
        "required": ["doc_type", "confidence", "reasoning"],
    }


def _extract_response_schema(fields_spec: dict[str, str]) -> dict[str, Any]:
    """Build a JSON schema where each field is {value: string|null, confidence: number}."""
    properties: dict[str, Any] = {}
    for field_name in fields_spec:
        properties[field_name] = {
            "type": "object",
            "properties": {
                "value": {"type": "string", "nullable": True},
                "confidence": {"type": "number"},
            },
            "required": ["value", "confidence"],
        }
    return {
        "type": "object",
        "properties": properties,
        "required": list(fields_spec.keys()),
    }


class GeminiProvider:
    def __init__(self) -> None:
        s = get_settings()
        genai.configure(api_key=s.gemini_api_key)
        self._model_name = s.gemini_model
        self._generation_config = {"temperature": 0.1, "response_mime_type": "application/json"}
        self._model = genai.GenerativeModel(
            model_name=self._model_name, generation_config=self._generation_config
        )
        self._timeout = 30

    @retry(
        retry=retry_if_exception_type(Exception),
        stop=stop_after_attempt(3),
        wait=wait_exponential(multiplier=1, min=1, max=8),
        reraise=True,
    )
    def _generate(self, prompt: str, *, response_schema: dict[str, Any] | None = None) -> str:
        if response_schema is not None:
            try:
                cfg = genai.GenerationConfig(
                    temperature=0.1,
                    response_mime_type="application/json",
                    response_schema=response_schema,
                )
                resp = self._model.generate_content(
                    prompt,
                    generation_config=cfg,
                    request_options={"timeout": self._timeout},
                )
                return resp.text or ""
            except Exception as e:
                logger.warning("response_schema rejected, retrying without schema: %s", e)
        resp = self._model.generate_content(prompt, request_options={"timeout": self._timeout})
        return resp.text or ""

    def chat_json(self, prompt: str, temperature: float = 0.1) -> dict[str, Any]:
        raw = self._generate(prompt)
        try:
            return _first_json(raw)
        except Exception:
            logger.warning("LLM returned non-JSON; raw=%r", raw[:500])
            return {}

    def _call_with_cache(
        self,
        *,
        site: str,
        prompt: str,
        prompt_version: str,
        content_for_key: str,
        response_schema: dict[str, Any] | None,
        application_id: str | None = None,
    ) -> dict[str, Any]:
        cache_key = cache.compute_key(
            model=self._model_name, prompt_version=prompt_version, content=content_for_key
        )
        prompt_tokens_in = _estimate_tokens(prompt)

        cached = None
        try:
            cached = cache.get(cache_key)
        except Exception:
            logger.exception("gemini_cache get failed (ignored)")

        if cached is not None:
            logger.info(
                "gemini.call site=%s model=%s prompt_tokens_in=%d tokens_out=%d cache_hit=%s application_id=%s",
                site, self._model_name, prompt_tokens_in, 0, True, application_id or "-",
            )
            return cached

        raw = self._generate(prompt, response_schema=response_schema)
        tokens_out = _estimate_tokens(raw)
        try:
            data = _first_json(raw)
        except Exception:
            logger.warning("LLM returned non-JSON; raw=%r", raw[:500])
            data = {}

        logger.info(
            "gemini.call site=%s model=%s prompt_tokens_in=%d tokens_out=%d cache_hit=%s application_id=%s",
            site, self._model_name, prompt_tokens_in, tokens_out, False, application_id or "-",
        )

        if data:
            try:
                cache.put(
                    cache_key=cache_key,
                    prompt_version=prompt_version,
                    model_name=self._model_name,
                    response_json=data,
                    prompt_tokens_in=prompt_tokens_in,
                    tokens_out=tokens_out,
                )
            except Exception:
                logger.exception("gemini_cache put failed (ignored)")

        return data

    def classify_document(
        self,
        text: str,
        doc_type_options: list[str],
        *,
        application_id: str | None = None,
    ) -> ClassificationResult:
        snippet = text[:4000]
        prompt = f"""You are classifying an Indian KYC / loan-origination document.

Allowed doc_type values (choose exactly one):
{json.dumps(doc_type_options)}

Return strict JSON with keys: doc_type, confidence (0-1 float), reasoning (<= 40 words).
If none fit, pick the closest and set confidence <= 0.3.

OCR text:
---
{snippet}
---"""
        data = self._call_with_cache(
            site="classify",
            prompt=prompt,
            prompt_version=PROMPT_VERSION_CLASSIFY,
            content_for_key=snippet,
            response_schema=_classify_response_schema(doc_type_options),
            application_id=application_id,
        )
        doc_type = str(data.get("doc_type") or "").strip()
        if doc_type not in doc_type_options:
            doc_type = doc_type_options[0] if doc_type_options else "unknown"
        try:
            conf = float(data.get("confidence") or 0.0)
        except (TypeError, ValueError):
            conf = 0.0
        return ClassificationResult(
            doc_type=doc_type,
            confidence=max(0.0, min(1.0, conf)),
            reasoning=str(data.get("reasoning") or ""),
        )

    def extract_fields(
        self,
        text: str,
        fields_spec: dict[str, str],
        doc_type: str,
        *,
        application_id: str | None = None,
    ) -> dict[str, FieldValue]:
        snippet = _smart_snippet(text, max_chars=50_000, head_chars=25_000, tail_chars=25_000)
        field_lines = "\n".join(f"- {k}: {desc}" for k, desc in fields_spec.items())
        guidance = _DOC_TYPE_GUIDANCE.get(doc_type, "")
        prompt = f"""You are extracting structured fields from an Indian {doc_type.replace('_', ' ')} document.
The text below is the OCR output of a real-world (possibly multi-page) Indian PDF —
labels may be bilingual (Hindi/English), numbers use the Indian lakh format
(e.g. 1,23,456.78 = 123456.78), and salary / balance figures may appear with
Cr/Dr suffixes or in parentheses for negatives.

{guidance}

Extract these fields (name: description):
{field_lines}

Output rules:
1. Return STRICT JSON of the shape:
   {{ "<field_name>": {{ "value": "<string|null>", "confidence": <0-1 float> }} }}
2. Use null when a field is genuinely absent. Do NOT guess.
3. For monetary fields: return digits only (no commas, no currency symbol).
   "1,23,456.78" -> "123456.78". A value in parentheses means negative.
4. For dates: return YYYY-MM-DD when day/month/year are all known; for
   month-only contexts (e.g. payslip pay_period) return YYYY-MM.
5. For PAN: 10 uppercase chars matching AAAAA9999A.
6. For Aadhaar: 12 digits, no spaces, must not start with 0 or 1.
7. For IFSC: 11 chars matching AAAA0NNNNNN.
8. For names: drop honorifics (Mr./Mrs./Ms./Shri/Smt./Dr./Sri).
9. If multiple candidates appear (e.g. closing balance per page), prefer the
   summary / last-page value over per-transaction running values.
10. Confidence reflects how clearly the field is anchored in the text
    (1.0 = explicit labelled hit, 0.5 = inferred, <0.3 = unsure).

OCR text:
---
{snippet}
---"""
        sorted_field_names = ",".join(sorted(fields_spec.keys()))
        content_for_key = f"{snippet}\x1f{doc_type}\x1f{sorted_field_names}"
        data = self._call_with_cache(
            site="extract",
            prompt=prompt,
            prompt_version=PROMPT_VERSION_EXTRACT,
            content_for_key=content_for_key,
            response_schema=_extract_response_schema(fields_spec),
            application_id=application_id,
        )
        out: dict[str, FieldValue] = {}
        for k in fields_spec:
            v = data.get(k)
            if isinstance(v, dict):
                val = v.get("value")
                conf = v.get("confidence") or 0.0
                if val in (None, "", "null"):
                    continue
                try:
                    conf = float(conf)
                except (TypeError, ValueError):
                    conf = 0.0
                out[k] = FieldValue(value=str(val), confidence=max(0.0, min(1.0, conf)))
            elif v not in (None, "", "null"):
                out[k] = FieldValue(value=str(v), confidence=0.5)
        return out

@lru_cache(maxsize=1)
def get_provider() -> GeminiProvider:
    return GeminiProvider()
