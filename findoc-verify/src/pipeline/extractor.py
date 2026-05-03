"""Regex-based field extraction (ported from legacy findoc-ai).

Pure functions: take OCR text, return extracted field candidates.
No IO, no DB, no LLM. For Gemini-assisted extraction see llm provider.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import date, datetime
from typing import Iterable

AADHAAR_RE = re.compile(r"(?<!\d)([2-9]\d{3}\s?\d{4}\s?\d{4})(?!\d)")
AADHAAR_LABEL_RE = re.compile(
    r"(?:aadhaar|aadhar|आधार|UID|VID)[^\n]{0,40}?"
    r"((?:[2-9]\d{3}[\s\-]?\d{4}[\s\-]?\d{4})|(?:[2-9]\d{11}))",
    re.IGNORECASE,
)

PAN_RE = re.compile(r"\b([A-Z]{5}\s?[0-9]{4}\s?[A-Z])\b")
PAN_LABEL_RE = re.compile(
    r"(?:permanent\s*account\s*number|PAN(?:\s*(?:no|number|card))?|आयकर|पैन)[^\n]{0,40}?"
    r"([A-Z]{5}\s?[0-9]{4}\s?[A-Z])",
    re.IGNORECASE,
)

IFSC_RE = re.compile(r"\b([A-Z]{4}0[A-Z0-9]{6})\b")
IFSC_LABEL_RE = re.compile(
    r"(?:IFSC(?:\s*code)?|IFS\s*code|RTGS/NEFT)[^\n]{0,30}?\b([A-Z]{4}0[A-Z0-9]{6})\b",
    re.IGNORECASE,
)

INR_AMOUNT = (
    r"(?:₹|Rs\.?|INR|Rupees?)?\s?"
    r"(\d{1,3}(?:,\d{2,3})+(?:\.\d{1,2})?|\d{1,12}(?:\.\d{1,2})?)"
    r"(?:\s*/-)?"
    r"(?:\s*(?:Cr|Dr|CR|DR))?"
)
_AMT = INR_AMOUNT
INR_AMOUNT_PARENS = r"\(\s*" + INR_AMOUNT + r"\s*\)"

DATE_DD_MM_YYYY = re.compile(r"\b(\d{1,2}[/\-\.]\d{1,2}[/\-\.]\d{2,4})\b")
DATE_YYYY_MM_DD = re.compile(r"\b(\d{4}[/\-\.]\d{1,2}[/\-\.]\d{1,2})\b")
_MONTHS_LONG = "January|February|March|April|May|June|July|August|September|October|November|December"
_MONTHS_SHORT = "Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec"
DATE_WRITTEN = re.compile(
    rf"\b(\d{{1,2}}[\s\-/]+(?:{_MONTHS_LONG})[\s\-/]+\d{{4}})\b", re.IGNORECASE
)
DATE_WRITTEN_SHORT = re.compile(
    rf"\b(\d{{1,2}}[\s\-/]+(?:{_MONTHS_SHORT})[a-z]*[\s\-/]+\d{{4}})\b", re.IGNORECASE
)
MONTH_YEAR_RE = re.compile(rf"\b((?:{_MONTHS_LONG})[\s\-,]+\d{{4}})\b", re.IGNORECASE)
MONTH_YEAR_SHORT_RE = re.compile(rf"\b((?:{_MONTHS_SHORT})[a-z]*[\s\-,/]+\d{{4}})\b", re.IGNORECASE)

DATE_FORMATS = [
    "%Y-%m-%d", "%d/%m/%Y", "%d-%m-%Y", "%d.%m.%Y",
    "%d/%m/%y", "%d-%m-%y", "%Y/%m/%d",
    "%d %B %Y", "%d-%B-%Y", "%d/%B/%Y",
    "%d %b %Y", "%d-%b-%Y", "%d/%b/%Y",
    "%B %Y", "%b %Y", "%B-%Y", "%b-%Y", "%B,%Y", "%b,%Y",
]

def try_parse_date(s: str) -> date | None:
    s = s.strip()
    s_norm = re.sub(r"[\-/]+", " ", s)
    s_norm = re.sub(r"\s+", " ", s_norm).strip()
    for cand in (s, s_norm):
        for fmt in DATE_FORMATS:
            try:
                return datetime.strptime(cand, fmt).date()
            except ValueError:
                continue
    return None

GROSS_SALARY_PATTERNS = [
    rf"(?:total\s+(?:gross\s+)?earnings?)[:\s]*{_AMT}",
    rf"(?:gross\s+(?:earnings?|salary|pay|amount|wages?|remuneration|income))[:\s]*{_AMT}",
    rf"(?:total\s+(?:salary|pay|wages?|remuneration|income))[:\s]*{_AMT}",
    rf"(?:gross\s+monthly\s+(?:salary|pay))[:\s]*{_AMT}",
    rf"(?:cost\s*to\s*company|(?:total\s+)?(?:gross\s+)?CTC|total\s*package|annual\s+package)[:\s]*{_AMT}",
    rf"(?:earnings\s+total|earnings\s*[:\-]\s*total)[:\s]*{_AMT}",
]

NET_SALARY_PATTERNS = [
    rf"(?:net(?:t)?\s+pay\s+this\s+month)[:\s]*{_AMT}",
    rf"(?:net\s+salary\s+payable|salary\s+payable|amount\s+payable|payable\s+amount)[:\s]*{_AMT}",
    rf"(?:net|nett|take[\s\-]?home)\s+(?:salary|pay|amount|income|wages?)[:\s]*{_AMT}",
    rf"(?:amount\s+(?:due|credited)\s+to\s+(?:you|employee|account))[:\s]*{_AMT}",
    rf"(?:net\s+pay|nett\s+pay|total\s+net\s+pay|total\s+nett|balance\s+due|in\s+hand)[:\s]*{_AMT}",
    rf"(?:net\s+monthly\s+salary|nett\s+monthly\s+salary)[:\s]*{_AMT}",
]

TOTAL_DEDUCTIONS_PATTERNS = [
    rf"(?:total\s+deductions?)[:\s]*{_AMT}",
    rf"(?:deductions?\s+total|deductions?\s*[:\-]\s*total)[:\s]*{_AMT}",
    rf"(?:gross\s+deductions?|sum\s+of\s+deductions?)[:\s]*{_AMT}",
]

INCOME_TAX_PATTERNS = [
    rf"(?:income\s*tax|TDS|tax\s+deducted\s+at\s+source)[:\s]*{_AMT}",
    rf"(?:professional\s*tax|PT|prof\.?\s*tax)[:\s]*{_AMT}",
]

EXISTING_EMI_PATTERNS = [
    rf"(?:current\s+EMI|active\s+EMI|monthly\s+EMI)[:\s]*{_AMT}",
    rf"(?:EMI|loan\s+(?:repayment|deduction)|instalment|installment)\s*(?:amount)?[:\s]*{_AMT}",
    rf"(?:existing\s*(?:debt|emi|obligations?|loan\s+EMI))[:\s]*{_AMT}",
    rf"(?:monthly\s*(?:repayment|instalment|installment|outflow))[:\s]*{_AMT}",
    rf"(?:home\s*loan|vehicle\s*finance|personal\s*loan|car\s*(?:loan|finance)|mortgage|education\s*loan)\s*(?:EMI)?[:\s]*{_AMT}",
]

ACCOUNT_NUMBER_PATTERNS = [
    re.compile(
        r"(?:account\s*(?:number|no\.?|num|#)|a/?c\s*(?:no\.?|number)?)\s*[:\-]?\s*([0-9][0-9\s\-]{5,20}[0-9])",
        re.IGNORECASE,
    ),
    re.compile(r"\bA\s*/\s*C\s*[:\-]?\s*(\d{6,18})", re.IGNORECASE),
    re.compile(r"(?:savings|current|salary)\s*a/?c[^\n]{0,30}?(\d{8,18})", re.IGNORECASE),
]

OPENING_BALANCE_PATTERNS = [
    rf"(?:opening|beginning|brought\s*forward|b/?f|starting|previous)\s*(?:balance)?[:\s]*{_AMT}",
    rf"(?:balance\s+brought\s+forward|bal\.?\s*b/?f)[:\s]*{_AMT}",
]
CLOSING_BALANCE_PATTERNS = [
    rf"(?:closing|ending|carried\s*forward|c/?f|final|current)\s*balance[:\s]*{_AMT}",
    rf"(?:balance\s+carried\s+forward|bal\.?\s*c/?f|available\s+balance|ledger\s+balance)[:\s]*{_AMT}",
]
TOTAL_CREDITS_PATTERNS = [
    rf"(?:total\s*(?:monthly|number\s+of)?\s*credits?|total\s*deposits?|credits?\s*total|total\s*money\s*in|credit\s+summary)[:\s]*{_AMT}",
    rf"(?:total\s+inflow|sum\s+of\s+credits?|aggregate\s+credits?)[:\s]*{_AMT}",
]
TOTAL_DEBITS_PATTERNS = [
    rf"(?:total\s*(?:monthly|number\s+of)?\s*debits?|total\s*(?:monthly\s*)?withdrawals?|debits?\s*total|total\s*money\s*out|debit\s+summary)[:\s]*{_AMT}",
    rf"(?:total\s+outflow|sum\s+of\s+debits?|aggregate\s+debits?)[:\s]*{_AMT}",
]

SALARY_DEPOSIT_PATTERNS = [
    rf"(?:\bSAL\b|\bSALARY\b|salary[\s/\-]+(?:credit|deposit|payment|transfer|cr)|payroll|salaries?\s+paid)[^\n]{{0,80}}?{_AMT}",
    rf"(?:NEFT|IMPS|RTGS|INB|UPI)[^\n]{{0,40}}?(?:SAL|SALARY|PAYROLL)[^\n]{{0,40}}?{_AMT}",
    rf"(?:by\s+transfer|monthly\s+(?:salary|wages?))[^\n]{{0,60}}?{_AMT}",
]

STATEMENT_PERIOD_PATTERNS = [
    re.compile(
        r"(?:statement\s*(?:period|for\s+the\s+period|from)|period\s*[:\-]?|for\s+the\s+period)\s*[:\-]?\s*"
        r"(\d{1,2}[\-/\s][A-Za-z0-9]{2,9}[\-/\s]\d{2,4})\s*(?:to|-|–|—|through)\s*"
        r"(\d{1,2}[\-/\s][A-Za-z0-9]{2,9}[\-/\s]\d{2,4})",
        re.IGNORECASE,
    ),
    re.compile(
        r"\bfrom\s+(\d{1,2}[\-/\s][A-Za-z0-9]{2,9}[\-/\s]\d{2,4})\s+to\s+(\d{1,2}[\-/\s][A-Za-z0-9]{2,9}[\-/\s]\d{2,4})\b",
        re.IGNORECASE,
    ),
]

TAXABLE_INCOME_PATTERNS = [
    rf"(?:total\s+taxable\s+income|taxable\s+total\s+income)[:\s]*{_AMT}",
    rf"(?:taxable\s+income|total\s+taxable\s+amount|net\s+taxable\s+income)[:\s]*{_AMT}",
]
GROSS_INCOME_ITR_PATTERNS = [
    rf"(?:gross\s+total\s+income)[:\s]*{_AMT}",
    rf"(?:total\s+income\s+from\s+all\s+sources|total\s+gross\s+income)[:\s]*{_AMT}",
    rf"(?:income\s+chargeable\s+under)[^\n]{{0,80}}?{_AMT}",
]
TOTAL_TAX_PAID_PATTERNS = [
    rf"(?:total\s+tax\s+paid|net\s+tax\s+paid|aggregate\s+tax\s+paid)[:\s]*{_AMT}",
    rf"(?:total\s+tax\s+(?:payable|liability)|net\s+tax\s+payable|tax\s+payable\s+total)[:\s]*{_AMT}",
    rf"(?:total\s+tax\s+and\s+(?:cess|surcharge))[:\s]*{_AMT}",
]
TAX_YEAR_RE = re.compile(
    r"(?:assessment\s*year|tax\s*year|AY|YoA|A\.Y\.)\s*[:\-]?\s*(\d{4}[\s/\-]\d{2,4})",
    re.IGNORECASE,
)
TAX_YEAR_BARE_RE = re.compile(r"\b(20\d{2}[/\-]\d{2,4})\b")

CREDIT_SCORE_RE = re.compile(
    r"(?:CIBIL(?:\s*TransUnion)?\s*score|credit(?:vision|\s*\w+)?\s*score|consumer\s+credit\s+score|equifax\s+score|experian\s+score|score)\s*[:\-]?\s*(\d{3})",
    re.IGNORECASE,
)
DPD_RE = re.compile(r"(?:DPD|days\s*past\s*due|max\s+dpd)[:\s]*(\d{1,4})", re.IGNORECASE)
TOTAL_OUTSTANDING_PATTERNS = [
    rf"(?:total\s+outstanding(?:\s+balance)?|aggregate\s+outstanding|principal\s+outstanding)[:\s]*{_AMT}",
    rf"(?:current\s+balance|outstanding\s+balance|sum\s+of\s+balances)[:\s]*{_AMT}",
]

NAME_LINE_RE = re.compile(
    r"(?:^|\n)\s*(?:name|applicant\s*name|account[ \t]*holder(?:'s)?(?:\s+name)?|customer(?:\s+name)?|employee(?:\s+name)?|borrower(?:\s+name)?|holder\s+name|in\s+favour\s+of)\s*[:\-]?\s*"
    r"([A-Za-z][A-Za-z \t\.'-]{2,80})",
    re.IGNORECASE,
)
EMPLOYER_LINE_RE = re.compile(
    r"(?:^|\n)\s*(?:employer(?:\s+name)?|company(?:\s+name)?|organi[sz]ation(?:\s+name)?|firm|corporate\s+name|issued\s+by)\s*[:\-]?\s*"
    r"([A-Za-z][A-Za-z \t\.,&'-]{2,100})",
    re.IGNORECASE,
)
DOB_LINE_RE = re.compile(
    r"(?:date\s*of\s*birth|D\.?O\.?B|जन्म[\sऀ-ॿ]*तिथि|जन्मतिथि)\s*[:\-]?\s*([0-9A-Za-z\s/\-\.]{6,30})",
    re.IGNORECASE,
)
YEAR_OF_BIRTH_RE = re.compile(r"(?:year\s*of\s*birth|YoB|जन्म\s*वर्ष)\s*[:\-]?\s*(19\d{2}|20\d{2})", re.IGNORECASE)
GENDER_RE = re.compile(r"\b(MALE|FEMALE|TRANSGENDER|पुरुष|महिला|M/F)\b", re.IGNORECASE)
ADDRESS_LINE_RE = re.compile(
    r"(?:address|residential\s+address|permanent\s+address|registered\s+address|पता)\s*[:\-]?\s*"
    r"([A-Za-z0-9][A-Za-z0-9\s,/\-\.#]{10,250})",
    re.IGNORECASE,
)

def normalize_text(text: str) -> str:
    """Strip rupee symbol, page-break chars, page footers; collapse horizontal whitespace."""
    if not text:
        return text
    t = text
    t = t.replace("₹", "Rs. ")
    t = t.replace("\x0c", "\n")
    t = re.sub(r"\bPage[\s\-]+\d+[\s\-]+of[\s\-]+\d+\b", " ", t, flags=re.IGNORECASE)
    t = re.sub(r"[ \t ]+", " ", t)
    return t

def _parse_amount(s: str) -> float | None:
    if s is None:
        return None
    raw = s.strip()
    if not raw:
        return None
    neg = False
    if raw.startswith("(") and raw.endswith(")"):
        neg = True
        raw = raw[1:-1].strip()
    raw = re.sub(r"\s*/-\s*$", "", raw)
    raw = re.sub(r"\s*(?:Cr|Dr|CR|DR|cr|dr)\.?\s*$", "", raw)
    raw = raw.replace(",", "").replace(" ", "")
    try:
        v = float(raw)
        return -v if neg else v
    except ValueError:
        return None

@dataclass
class Candidate:
    field_name: str
    value: str
    confidence: float

def _match_amount(
    text: str, patterns: Iterable[str], *, prefer_last: bool = False
) -> tuple[float, str, float] | None:
    """Return (amount, raw, conf) for first matching pattern; prefer_last picks last hit on multipage."""
    for i, pat in enumerate(patterns):
        matches = list(re.finditer(pat, text, re.IGNORECASE))
        if not matches:
            continue
        iter_matches = reversed(matches) if prefer_last else matches
        for m in iter_matches:
            raw = m.group(1)
            val = _parse_amount(raw)
            if val is not None:
                conf = max(0.6, 0.9 - i * 0.05)
                return val, raw, conf
    return None

def _is_plausible_aadhaar(digits: str) -> bool:
    if len(digits) != 12 or not digits.isdigit():
        return False
    if digits[0] in ("0", "1"):
        return False
    if len(set(digits)) == 1:
        return False
    if digits in ("123456789012", "234567890123"):
        return False
    return True

def extract_aadhaar(text: str) -> Candidate | None:
    m = AADHAAR_LABEL_RE.search(text)
    if m:
        digits = re.sub(r"[\s\-]", "", m.group(1))
        if _is_plausible_aadhaar(digits):
            return Candidate("aadhaar_number", digits, 0.97)
    for m in AADHAAR_RE.finditer(text):
        digits = m.group(1).replace(" ", "")
        if _is_plausible_aadhaar(digits):
            return Candidate("aadhaar_number", digits, 0.95)
    return None

def extract_pan(text: str) -> Candidate | None:
    m = PAN_LABEL_RE.search(text)
    if m:
        val = re.sub(r"\s", "", m.group(1)).upper()
        if re.fullmatch(r"[A-Z]{5}[0-9]{4}[A-Z]", val):
            return Candidate("pan_number", val, 0.99)
    m = PAN_RE.search(text)
    if not m:
        return None
    val = re.sub(r"\s", "", m.group(1)).upper()
    return Candidate("pan_number", val, 0.98)

def extract_ifsc(text: str) -> Candidate | None:
    m = IFSC_LABEL_RE.search(text)
    if m:
        return Candidate("ifsc", m.group(1).upper(), 0.95)
    m = IFSC_RE.search(text)
    if not m:
        return None
    return Candidate("ifsc", m.group(1).upper(), 0.9)

def extract_account_number(text: str) -> Candidate | None:
    for pat in ACCOUNT_NUMBER_PATTERNS:
        for m in pat.finditer(text):
            raw = re.sub(r"[\s\-]", "", m.group(1))
            if not raw.isdigit():
                continue
            if 8 <= len(raw) <= 18:
                return Candidate("account_number", raw, 0.85)
            if 6 <= len(raw) < 8:
                return Candidate("account_number", raw, 0.65)
    return None

def extract_gross_salary(text: str) -> Candidate | None:
    r = _match_amount(text, GROSS_SALARY_PATTERNS)
    if not r:
        return None
    val, _raw, conf = r
    if not (5_000 <= val <= 100_00_00_000):
        return None
    return Candidate("gross_salary", f"{val:.2f}", conf)

def extract_net_salary(text: str) -> Candidate | None:
    r = _match_amount(text, NET_SALARY_PATTERNS)
    if not r:
        return None
    val, _raw, conf = r
    if not (1_000 <= val <= 100_00_00_000):
        return None
    return Candidate("net_salary", f"{val:.2f}", conf)

def extract_total_deductions(text: str) -> Candidate | None:
    r = _match_amount(text, TOTAL_DEDUCTIONS_PATTERNS)
    if not r:
        return None
    val, _, conf = r
    if val < 0 or val > 10_00_00_000:
        return None
    return Candidate("total_deductions", f"{val:.2f}", conf)

def extract_existing_emi(text: str) -> Candidate | None:
    """Sum EMI-like amounts across the doc (multi-loan borrowers)."""
    total = 0.0
    matched = False
    seen: set[tuple[int, int]] = set()
    for pat in EXISTING_EMI_PATTERNS:
        for m in re.finditer(pat, text, re.IGNORECASE):
            span = m.span()
            if span in seen:
                continue
            seen.add(span)
            val = _parse_amount(m.group(1))
            if val is not None and 100 <= val <= 50_00_000:
                total += val
                matched = True
    if not matched:
        return None
    return Candidate("existing_emi", f"{total:.2f}", 0.7)

def extract_opening_balance(text: str) -> Candidate | None:
    r = _match_amount(text, OPENING_BALANCE_PATTERNS)
    return None if not r else Candidate("opening_balance", f"{r[0]:.2f}", r[2])

def extract_closing_balance(text: str) -> Candidate | None:
    r = _match_amount(text, CLOSING_BALANCE_PATTERNS, prefer_last=True)
    return None if not r else Candidate("closing_balance", f"{r[0]:.2f}", r[2])

def extract_total_credits(text: str) -> Candidate | None:
    r = _match_amount(text, TOTAL_CREDITS_PATTERNS, prefer_last=True)
    return None if not r else Candidate("total_credits", f"{r[0]:.2f}", r[2])

def extract_total_debits(text: str) -> Candidate | None:
    r = _match_amount(text, TOTAL_DEBITS_PATTERNS, prefer_last=True)
    return None if not r else Candidate("total_debits", f"{r[0]:.2f}", r[2])

def extract_salary_credits(text: str) -> list[float]:
    """Return plausible salary-credit amounts from bank-narration lines."""
    credits: list[float] = []
    seen: set[tuple[int, int]] = set()
    for pat in SALARY_DEPOSIT_PATTERNS:
        for m in re.finditer(pat, text, re.IGNORECASE):
            span = m.span()
            if span in seen:
                continue
            seen.add(span)
            val = _parse_amount(m.group(1))
            if val and 5_000 <= val <= 50_00_000:
                credits.append(val)
    credits.sort()
    return credits

def extract_total_outstanding(text: str) -> Candidate | None:
    r = _match_amount(text, TOTAL_OUTSTANDING_PATTERNS)
    return None if not r else Candidate("total_outstanding", f"{r[0]:.2f}", r[2])

def extract_taxable_income(text: str) -> Candidate | None:
    r = _match_amount(text, TAXABLE_INCOME_PATTERNS)
    if not r:
        return None
    val, _, conf = r
    if val < 0 or val > 100_00_00_000:
        return None
    return Candidate("taxable_income", f"{val:.2f}", conf)

def extract_gross_income_itr(text: str) -> Candidate | None:
    r = _match_amount(text, GROSS_INCOME_ITR_PATTERNS)
    if not r:
        return None
    val, _, conf = r
    if val < 0 or val > 100_00_00_000:
        return None
    return Candidate("gross_income", f"{val:.2f}", conf)

def extract_tax_paid(text: str) -> Candidate | None:
    r = _match_amount(text, TOTAL_TAX_PAID_PATTERNS)
    if not r:
        return None
    val, _, conf = r
    if val < 0 or val > 50_00_00_000:
        return None
    return Candidate("total_tax_paid", f"{val:.2f}", conf)

def extract_tax_year(text: str) -> Candidate | None:
    m = TAX_YEAR_RE.search(text) or TAX_YEAR_BARE_RE.search(text)
    if not m:
        return None
    return Candidate("tax_year", re.sub(r"\s+", "", m.group(1)), 0.9)

def extract_credit_score(text: str) -> Candidate | None:
    for m in CREDIT_SCORE_RE.finditer(text):
        try:
            score = int(m.group(1))
        except ValueError:
            continue
        if 300 <= score <= 900:
            return Candidate("credit_score", str(score), 0.95)
    return None

def _strip_name_prefix(val: str) -> str:
    return re.sub(
        r"^(?:Mr|Mrs|Ms|Miss|Shri|Smt|Dr|Sri|Mst|Sh|Sm)\.?\s+",
        "",
        val,
        flags=re.IGNORECASE,
    )

def extract_dob(text: str) -> Candidate | None:
    m = DOB_LINE_RE.search(text)
    if m:
        raw = m.group(1).strip()
        for token in re.findall(r"[0-9A-Za-z]{1,4}[/\-\. ][0-9A-Za-z]{1,4}[/\-\. ][0-9A-Za-z]{2,4}", raw):
            d = try_parse_date(token)
            if d and 1900 <= d.year <= datetime.now().year:
                return Candidate("dob", d.isoformat(), 0.95)
        return Candidate("dob", raw[:32], 0.5)
    m = YEAR_OF_BIRTH_RE.search(text)
    if m:
        try:
            yr = int(m.group(1))
            if 1900 <= yr <= datetime.now().year:
                return Candidate("dob", f"{yr}-01-01", 0.6)
        except ValueError:
            pass
    return None

def extract_first_date(text: str) -> Candidate | None:
    for pat in (DATE_YYYY_MM_DD, DATE_DD_MM_YYYY, DATE_WRITTEN, DATE_WRITTEN_SHORT):
        for m in pat.finditer(text):
            d = try_parse_date(m.group(1))
            if d and 1990 <= d.year <= datetime.now().year + 1:
                return Candidate("first_date", d.isoformat(), 0.75)
    return None

def extract_name(text: str) -> Candidate | None:
    m = NAME_LINE_RE.search(text)
    if not m:
        return None
    val = re.sub(r"\s+", " ", m.group(1).strip(" .:-"))
    val = _strip_name_prefix(val).strip()
    if len(val) < 3 or len(val) > 80:
        return None
    if re.search(r"(?i)\b(?:account|number|address|date|customer|holder|employee)\b", val):
        return None
    return Candidate("full_name", val, 0.75)

def extract_employer(text: str) -> Candidate | None:
    m = EMPLOYER_LINE_RE.search(text)
    if not m:
        return None
    val = re.sub(r"\s+", " ", m.group(1).strip(" .:-"))
    if len(val) < 2:
        return None
    return Candidate("employer_name", val, 0.7)

def extract_address(text: str) -> Candidate | None:
    m = ADDRESS_LINE_RE.search(text)
    if not m:
        return None
    val = re.sub(r"\s+", " ", m.group(1).strip(" .:-"))
    if len(val) < 10:
        return None
    return Candidate("address", val, 0.6)

def extract_period_month(text: str) -> date | None:
    """Infer which month a payslip or statement covers."""
    for pat in STATEMENT_PERIOD_PATTERNS:
        m = pat.search(text)
        if m:
            d = try_parse_date(m.group(1))
            if d:
                return date(d.year, d.month, 1)
    m = MONTH_YEAR_RE.search(text)
    if m:
        d = try_parse_date(m.group(1))
        if d:
            return date(d.year, d.month, 1)
    m = MONTH_YEAR_SHORT_RE.search(text)
    if m:
        d = try_parse_date(m.group(1))
        if d:
            return date(d.year, d.month, 1)
    cand = extract_first_date(text)
    if cand:
        try:
            d = datetime.fromisoformat(cand.value).date()
        except ValueError:
            d = try_parse_date(cand.value)
        if d:
            return date(d.year, d.month, 1)
    return None

def extract_statement_period(text: str) -> tuple[date, date] | None:
    for pat in STATEMENT_PERIOD_PATTERNS:
        m = pat.search(text)
        if m:
            ds = try_parse_date(m.group(1))
            de = try_parse_date(m.group(2))
            if ds and de:
                return ds, de
    return None

def extract_gender(text: str) -> Candidate | None:
    m = GENDER_RE.search(text)
    if not m:
        return None
    raw = m.group(1).upper()
    if raw == "पुरुष":
        raw = "MALE"
    elif raw == "महिला":
        raw = "FEMALE"
    return Candidate("gender", raw, 0.85)

def extract_for_aadhaar(text: str) -> list[Candidate]:
    text = normalize_text(text)
    out: list[Candidate] = []
    for fn in (extract_aadhaar, extract_name, extract_dob, extract_address, extract_gender):
        c = fn(text)
        if c:
            out.append(c)
    return out

def extract_for_pan(text: str) -> list[Candidate]:
    text = normalize_text(text)
    out: list[Candidate] = []
    for fn in (extract_pan, extract_name, extract_dob):
        c = fn(text)
        if c:
            out.append(c)
    return out

def extract_for_payslip(text: str) -> list[Candidate]:
    text = normalize_text(text)
    out: list[Candidate] = []
    for fn in (
        extract_gross_salary,
        extract_net_salary,
        extract_total_deductions,
        extract_employer,
        extract_name,
        extract_pan,
        extract_existing_emi,
    ):
        c = fn(text)
        if c:
            out.append(c)
    return out

def extract_for_bank_statement(text: str) -> list[Candidate]:
    text = normalize_text(text)
    out: list[Candidate] = []
    for fn in (
        extract_opening_balance,
        extract_closing_balance,
        extract_total_credits,
        extract_total_debits,
        extract_account_number,
        extract_ifsc,
        extract_name,
        extract_address,
    ):
        c = fn(text)
        if c:
            out.append(c)

    rng = extract_statement_period(text)
    if rng:
        ds, de = rng
        out.append(Candidate("statement_period_start", ds.isoformat(), 0.9))
        out.append(Candidate("statement_period_end", de.isoformat(), 0.9))

    credits = extract_salary_credits(text)
    if credits:
        credits.sort()
        mid = credits[len(credits) // 2]
        out.append(Candidate("monthly_credits", f"{mid:.2f}", 0.7))
    return out

def extract_for_employment_letter(text: str) -> list[Candidate]:
    text = normalize_text(text)
    out: list[Candidate] = []
    for fn in (
        extract_employer,
        extract_name,
        extract_gross_salary,
        extract_first_date,
        extract_address,
    ):
        c = fn(text)
        if c:
            out.append(c)
    return out

def extract_for_itr(text: str) -> list[Candidate]:
    text = normalize_text(text)
    out: list[Candidate] = []
    for fn in (
        extract_pan,
        extract_taxable_income,
        extract_gross_income_itr,
        extract_tax_paid,
        extract_tax_year,
        extract_name,
        extract_dob,
    ):
        c = fn(text)
        if c:
            out.append(c)
    return out

def extract_for_credit_report(text: str) -> list[Candidate]:
    text = normalize_text(text)
    out: list[Candidate] = []
    for fn in (
        extract_pan,
        extract_credit_score,
        extract_existing_emi,
        extract_total_outstanding,
        extract_name,
        extract_dob,
    ):
        c = fn(text)
        if c:
            out.append(c)
    return out

EXTRACTORS_BY_DOC_TYPE = {
    "aadhaar": extract_for_aadhaar,
    "pan": extract_for_pan,
    "payslip": extract_for_payslip,
    "bank_statement": extract_for_bank_statement,
    "employment_letter": extract_for_employment_letter,
    "itr": extract_for_itr,
    "credit_report": extract_for_credit_report,
}

LLM_FIELD_SPEC_BY_TYPE: dict[str, dict[str, str]] = {
    "aadhaar": {
        "aadhaar_number": "12-digit Aadhaar UID, e.g. '234567891234' (no spaces). Never starts with 0 or 1.",
        "full_name": "Full name as printed in English on the card (strip Mr./Mrs./Shri/Smt prefixes). Ignore the Hindi line.",
        "dob": "Date of birth in YYYY-MM-DD. If only Year of Birth printed, use YYYY-01-01.",
        "address": "Full address from the back of the card; collapse to a single line.",
        "gender": "MALE / FEMALE / TRANSGENDER",
    },
    "pan": {
        "pan_number": "10-character PAN, format AAAAA9999A (5 letters + 4 digits + 1 letter), uppercase.",
        "full_name": "Full name on card (strip Mr./Mrs./Shri/Smt prefixes).",
        "dob": "Date of birth as YYYY-MM-DD.",
        "fathers_name": "Father's name as printed (optional; strip Mr./Mrs./Shri/Smt prefixes).",
    },
    "payslip": {
        "gross_salary": "Gross / Total Earnings in INR for this month (sum of Basic+HRA+all allowances), digits only.",
        "net_salary": "Net Pay / Take-home / Amount Payable in INR for this month, digits only.",
        "total_deductions": "Total Deductions in INR (PF + Tax + Professional Tax + ...).",
        "employer_name": "Issuing employer / company name (the legal name printed on the slip header).",
        "full_name": "Employee name (strip Mr./Mrs. prefixes).",
        "pay_period": "Month this payslip covers, formatted YYYY-MM (e.g. 2024-03).",
        "pan_number": "Employee PAN if printed (10-char AAAAA9999A).",
    },
    "bank_statement": {
        "account_holder": "Account holder full name (strip Mr./Mrs./Ms. prefixes).",
        "account_number": "Account number, digits only (8-18 digits typical).",
        "ifsc": "11-character IFSC code, format AAAA0NNNNNN.",
        "opening_balance": "Opening balance INR for the statement period, digits only.",
        "closing_balance": "Closing balance INR for the statement period (use the last balance on the last page).",
        "total_credits": "Total credits / deposits in the period INR.",
        "total_debits": "Total debits / withdrawals in the period INR.",
        "monthly_credits": "Median salary credit amount in INR (look for SAL/SALARY/PAYROLL in transaction narration).",
        "statement_period_start": "Start of statement period YYYY-MM-DD.",
        "statement_period_end": "End of statement period YYYY-MM-DD.",
        "address": "Account holder address as printed.",
    },
    "employment_letter": {
        "employer_name": "Issuing employer / company name (the letterhead's legal entity).",
        "full_name": "Employee name (strip prefixes).",
        "position": "Job title / designation.",
        "gross_salary": "Stated annual or monthly gross INR (whichever is mentioned). Digits only.",
        "employment_start_date": "Date of joining YYYY-MM-DD.",
        "letter_date": "Date the letter was issued YYYY-MM-DD.",
        "address": "Employer address from letterhead.",
    },
    "itr": {
        "pan_number": "Taxpayer PAN (10-char AAAAA9999A).",
        "full_name": "Taxpayer name (strip prefixes).",
        "taxable_income": "Total taxable income INR (the figure tax is computed on, after deductions).",
        "gross_income": "Gross Total Income INR (before deductions u/s 80C etc.).",
        "total_tax_paid": "Total tax paid / payable INR including cess and surcharge.",
        "tax_year": "Assessment year e.g. '2023-24' or '2024-25'.",
        "dob": "Date of birth YYYY-MM-DD.",
    },
    "credit_report": {
        "pan_number": "Borrower PAN (10-char AAAAA9999A).",
        "full_name": "Borrower name (strip prefixes).",
        "credit_score": "Credit score 300–900 (CIBIL / Experian / Equifax / CRIF).",
        "total_outstanding": "Sum of current outstanding loan + card balances INR.",
        "existing_emi": "Sum of active monthly EMIs across all reported loans INR.",
        "dpd_flag": "Highest DPD (days past due) number reported across all accounts.",
        "dob": "Date of birth YYYY-MM-DD.",
    },
}
