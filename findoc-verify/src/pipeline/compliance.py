"""Indian-compliance checks. Pure functions over an aggregated application dict.

Each check returns dict: {name, status: pass|fail|warning, details: {...}, severity: low|med|high}.

Shape of `application_data` (aggregator builds this):
{
  "documents": {
    "aadhaar": {"fields": {"aadhaar_number": "...", "full_name": "...", "dob": "...", "address": "..."}, "file_hash": "...", "ocr_avg_confidence": 0.82},
    "pan": {"fields": {"pan_number": "...", "full_name": "...", "dob": "..."}, ...},
    "bank_statements": [{"period_month": "YYYY-MM-DD", "fields": {...}, ...}, ...],
    "payslips": [{"period_month": "YYYY-MM-DD", "fields": {...}, ...}, ...],
    "employment_letter": {...},
    "itr": {...},
    "credit_report": {...},
  }
}
"""
from __future__ import annotations

import math
import re
from dataclasses import asdict, dataclass
from datetime import date, datetime, timedelta
from typing import Any

from rapidfuzz import fuzz

def _threshold(app: dict, key: str, default: float) -> float:
    t = app.get("_thresholds") or {}
    v = t.get(key)
    try:
        return float(v) if v is not None else default
    except (TypeError, ValueError):
        return default

_VERHOEFF_D = [
    [0, 1, 2, 3, 4, 5, 6, 7, 8, 9],
    [1, 2, 3, 4, 0, 6, 7, 8, 9, 5],
    [2, 3, 4, 0, 1, 7, 8, 9, 5, 6],
    [3, 4, 0, 1, 2, 8, 9, 5, 6, 7],
    [4, 0, 1, 2, 3, 9, 5, 6, 7, 8],
    [5, 9, 8, 7, 6, 0, 4, 3, 2, 1],
    [6, 5, 9, 8, 7, 1, 0, 4, 3, 2],
    [7, 6, 5, 9, 8, 2, 1, 0, 4, 3],
    [8, 7, 6, 5, 9, 3, 2, 1, 0, 4],
    [9, 8, 7, 6, 5, 4, 3, 2, 1, 0],
]
_VERHOEFF_P = [
    [0, 1, 2, 3, 4, 5, 6, 7, 8, 9],
    [1, 5, 7, 6, 2, 8, 3, 0, 9, 4],
    [5, 8, 0, 3, 7, 9, 6, 1, 4, 2],
    [8, 9, 1, 6, 0, 4, 3, 5, 2, 7],
    [9, 4, 5, 3, 1, 2, 6, 8, 7, 0],
    [4, 2, 8, 6, 5, 7, 3, 9, 0, 1],
    [2, 7, 9, 3, 8, 0, 6, 4, 1, 5],
    [7, 0, 4, 6, 9, 1, 3, 2, 5, 8],
]

def verhoeff_valid(number: str) -> bool:
    if not number.isdigit() or len(number) != 12:
        return False
    c = 0
    for i, digit in enumerate(reversed(number)):
        c = _VERHOEFF_D[c][_VERHOEFF_P[i % 8][int(digit)]]
    return c == 0

PAN_FORMAT_RE = re.compile(r"^[A-Z]{5}\d{4}[A-Z]$")

def _pass(name: str, details: dict | None = None, severity: str = "low") -> dict:
    return {"name": name, "status": "pass", "details": details or {}, "severity": severity}

def _fail(name: str, details: dict | None = None, severity: str = "high") -> dict:
    return {"name": name, "status": "fail", "details": details or {}, "severity": severity}

def _warn(name: str, details: dict | None = None, severity: str = "med") -> dict:
    return {"name": name, "status": "warning", "details": details or {}, "severity": severity}

def _norm_name(s: str | None) -> str:
    if not s:
        return ""
    s = re.sub(r"[^A-Za-z\s]", " ", s)
    return re.sub(r"\s+", " ", s).strip().upper()

def _fuzzy_name(a: str | None, b: str | None) -> float:
    a = _norm_name(a)
    b = _norm_name(b)
    if not a or not b:
        return 0.0
    return float(fuzz.token_sort_ratio(a, b))

def _parse_date(s: Any) -> date | None:
    if not s:
        return None
    if isinstance(s, date):
        return s
    s = str(s).strip()
    for fmt in ("%Y-%m-%d", "%d/%m/%Y", "%d-%m-%Y", "%d.%m.%Y", "%d %b %Y", "%d %B %Y"):
        try:
            return datetime.strptime(s, fmt).date()
        except ValueError:
            continue
    return None

def _doc_field(doc: dict | None, key: str) -> Any:
    if not doc:
        return None
    return (doc.get("fields") or {}).get(key)

def _today() -> date:
    return date.today()

def _month_first(d: date) -> date:
    return date(d.year, d.month, 1)

def _months_ago(months: int) -> date:
    d = _today()
    y, m = d.year, d.month - months
    while m <= 0:
        m += 12
        y -= 1
    return date(y, m, 1)

def check_pan_format(app: dict) -> dict | None:
    pan = _doc_field(app["documents"].get("pan"), "pan_number") or _doc_field(app["documents"].get("itr"), "pan_number")
    if not pan:
        return None
    pan = pan.strip().upper()
    if not PAN_FORMAT_RE.match(pan):
        return _fail("pan_format", {"pan_last": pan[-1:], "reason": "regex mismatch"})
    fourth = pan[3]
    allowed_category = fourth in "PFCHATBLJG"
    if not allowed_category:
        return _fail("pan_format_category", {"fourth_char": fourth})
    return _pass("pan_format", {"fourth_char": fourth, "category_ok": True})

def check_aadhaar_verhoeff(app: dict) -> dict | None:
    num = _doc_field(app["documents"].get("aadhaar"), "aadhaar_number")
    if not num:
        return None
    clean = re.sub(r"\s", "", str(num))
    if not verhoeff_valid(clean):
        return _fail("aadhaar_verhoeff", {"last4": clean[-4:]})
    return _pass("aadhaar_verhoeff", {"last4": clean[-4:]})

def check_name_pan_aadhaar(app: dict) -> dict | None:
    pan_name = _doc_field(app["documents"].get("pan"), "full_name")
    aad_name = _doc_field(app["documents"].get("aadhaar"), "full_name")
    if not (pan_name and aad_name):
        return None
    score = _fuzzy_name(pan_name, aad_name)
    if score >= 85:
        return _pass("name_pan_vs_aadhaar", {"score": score})
    if score >= 70:
        return _warn("name_pan_vs_aadhaar", {"score": score, "threshold": 85})
    return _fail("name_pan_vs_aadhaar", {"score": score, "threshold": 85})

def check_dob_consistency(app: dict) -> dict | None:
    dobs: dict[str, date] = {}
    for k in ("aadhaar", "pan", "itr"):
        d = _parse_date(_doc_field(app["documents"].get(k), "dob"))
        if d:
            dobs[k] = d
    if len(dobs) < 2:
        return None
    ref = next(iter(dobs.values()))
    mismatches = {k: v.isoformat() for k, v in dobs.items() if v != ref}
    if mismatches:
        return _fail("dob_consistency", {"dobs": {k: v.isoformat() for k, v in dobs.items()}})
    return _pass("dob_consistency", {"dobs": {k: v.isoformat() for k, v in dobs.items()}})

def check_itr_pan_matches_id(app: dict) -> dict | None:
    id_pan = _doc_field(app["documents"].get("pan"), "pan_number")
    itr_pan = _doc_field(app["documents"].get("itr"), "pan_number")
    if not (id_pan and itr_pan):
        return None
    if id_pan.strip().upper() == itr_pan.strip().upper():
        return _pass("itr_pan_matches_id")
    return _fail("itr_pan_matches_id", {"id_pan_last": id_pan[-4:], "itr_pan_last": itr_pan[-4:]})

def check_itr_ay_sanity(app: dict) -> dict | None:
    ay = _doc_field(app["documents"].get("itr"), "tax_year")
    if not ay:
        return None
    m = re.search(r"(\d{4})", str(ay))
    if not m:
        return _warn("itr_ay_sanity", {"tax_year_raw": str(ay)})
    year = int(m.group(1))
    cur_ay = _today().year
    if cur_ay - year <= 2:
        return _pass("itr_ay_sanity", {"year": year})
    return _fail("itr_ay_sanity", {"year": year, "current_year": cur_ay})

def _months_of(docs: list[dict]) -> list[date]:
    months: list[date] = []
    for d in docs:
        m = _parse_date(d.get("period_month"))
        if m:
            months.append(_month_first(m))
    return sorted(set(months))

def check_payslip_period_coverage(app: dict) -> dict:
    required_months = int(_threshold(app, "payslip_period_months", 3))
    months = _months_of(app["documents"].get("payslips", []))
    if len(months) < required_months:
        return _fail("payslip_period_coverage", {"found_months": [m.isoformat() for m in months], "required": required_months})
    consecutive = all(
        (months[i + 1].year * 12 + months[i + 1].month) - (months[i].year * 12 + months[i].month) == 1
        for i in range(len(months) - 1)
    )
    last_ok = months[-1] >= _months_ago(4)
    if consecutive and last_ok:
        return _pass("payslip_period_coverage", {"months": [m.isoformat() for m in months]})
    return _fail(
        "payslip_period_coverage",
        {"months": [m.isoformat() for m in months], "consecutive": consecutive, "recent": last_ok},
    )

def check_bank_period_coverage(app: dict) -> dict:
    required_months = int(_threshold(app, "payslip_period_months", 3))
    months = _months_of(app["documents"].get("bank_statements", []))
    if len(months) < required_months:
        return _fail("bank_period_coverage", {"found_months": [m.isoformat() for m in months], "required": required_months})
    consecutive = all(
        (months[i + 1].year * 12 + months[i + 1].month) - (months[i].year * 12 + months[i].month) == 1
        for i in range(len(months) - 1)
    )
    last_ok = months[-1] >= _months_ago(4)
    if consecutive and last_ok:
        p_months = set(_months_of(app["documents"].get("payslips", [])))
        overlap = [m for m in months if m in p_months]
        if len(overlap) >= 3:
            return _pass("bank_period_coverage", {"months": [m.isoformat() for m in months]})
        return _warn(
            "bank_period_coverage",
            {"months": [m.isoformat() for m in months], "overlap_months": [m.isoformat() for m in overlap]},
        )
    return _fail(
        "bank_period_coverage",
        {"months": [m.isoformat() for m in months], "consecutive": consecutive, "recent": last_ok},
    )

def _to_float(v: Any) -> float | None:
    if v is None:
        return None
    try:
        return float(str(v).replace(",", "").replace(" ", ""))
    except ValueError:
        return None

def check_payslip_bank_reconciliation(app: dict) -> dict | None:
    payslips = app["documents"].get("payslips", [])
    banks = app["documents"].get("bank_statements", [])
    if not payslips or not banks:
        return None
    bank_by_month: dict[date, dict] = {}
    for b in banks:
        m = _parse_date(b.get("period_month"))
        if m:
            bank_by_month[_month_first(m)] = b
    checks: list[dict] = []
    for p in payslips:
        m = _parse_date(p.get("period_month"))
        if not m:
            continue
        bk = bank_by_month.get(_month_first(m))
        if not bk:
            checks.append({"month": m.isoformat(), "status": "skip", "reason": "no bank statement"})
            continue
        net = _to_float(_doc_field(p, "net_salary"))
        credit = _to_float(_doc_field(bk, "monthly_credits"))
        if not net or not credit:
            checks.append({"month": m.isoformat(), "status": "skip", "reason": "missing figures"})
            continue
        diff = abs(net - credit) / max(net, credit)
        status = "pass" if diff <= 0.05 else ("warning" if diff <= 0.15 else "fail")
        checks.append({"month": m.isoformat(), "net_pay": net, "bank_credit": credit, "diff_pct": round(diff, 4), "status": status})

    fails = [c for c in checks if c["status"] == "fail"]
    warns = [c for c in checks if c["status"] == "warning"]
    if fails:
        return _fail("payslip_vs_bank_salary", {"per_month": checks})
    if warns:
        return _warn("payslip_vs_bank_salary", {"per_month": checks})
    if any(c["status"] == "pass" for c in checks):
        return _pass("payslip_vs_bank_salary", {"per_month": checks})
    return None

def check_employer_consistency(app: dict) -> dict | None:
    payslips = app["documents"].get("payslips", [])
    letter = app["documents"].get("employment_letter") or {}
    if not payslips or not letter:
        return None
    emp_letter = _doc_field(letter, "employer_name")
    emp_payslips = [_doc_field(p, "employer_name") for p in payslips if _doc_field(p, "employer_name")]
    if not emp_letter or not emp_payslips:
        return None
    scores = [_fuzzy_name(emp_letter, e) for e in emp_payslips]
    avg = sum(scores) / len(scores)
    if avg >= 90:
        return _pass("employer_consistency", {"avg_score": avg})
    if avg >= 75:
        return _warn("employer_consistency", {"avg_score": avg, "threshold": 90})
    return _fail("employer_consistency", {"avg_score": avg, "threshold": 90})

def check_employment_letter_recency(app: dict) -> dict | None:
    letter = app["documents"].get("employment_letter") or {}
    letter_date = _parse_date(_doc_field(letter, "letter_date") or _doc_field(letter, "first_date"))
    if not letter_date:
        return None
    age_days = (_today() - letter_date).days
    if 0 <= age_days <= 180:
        return _pass("employment_letter_recency", {"letter_date": letter_date.isoformat(), "age_days": age_days})
    return _fail(
        "employment_letter_recency",
        {"letter_date": letter_date.isoformat(), "age_days": age_days, "max_age_days": 180},
    )

def check_bank_holder_name(app: dict) -> dict | None:
    banks = app["documents"].get("bank_statements", [])
    id_name = _doc_field(app["documents"].get("aadhaar"), "full_name") or _doc_field(app["documents"].get("pan"), "full_name")
    if not banks or not id_name:
        return None
    scores: list[float] = []
    for b in banks:
        name = _doc_field(b, "account_holder") or _doc_field(b, "full_name")
        if name:
            scores.append(_fuzzy_name(id_name, name))
    if not scores:
        return None
    avg = sum(scores) / len(scores)
    threshold = _threshold(app, "bank_holder_name_match_min", 85)
    if avg >= threshold:
        return _pass("bank_holder_name_matches_id", {"avg_score": avg})
    if avg >= threshold - 15:
        return _warn("bank_holder_name_matches_id", {"avg_score": avg, "threshold": threshold})
    return _fail("bank_holder_name_matches_id", {"avg_score": avg, "threshold": threshold})

def check_credit_pan_matches_id(app: dict) -> dict | None:
    cr = app["documents"].get("credit_report")
    id_pan = _doc_field(app["documents"].get("pan"), "pan_number")
    cr_pan = _doc_field(cr, "pan_number")
    if not (id_pan and cr_pan):
        return None
    if id_pan.strip().upper() == cr_pan.strip().upper():
        return _pass("credit_pan_matches_id")
    return _fail("credit_pan_matches_id", {"id_pan_last": id_pan[-4:], "cr_pan_last": cr_pan[-4:]})

def check_credit_score_threshold(app: dict) -> dict | None:
    cr = app["documents"].get("credit_report")
    score = _to_float(_doc_field(cr, "credit_score"))
    if score is None:
        return None
    pass_min = _threshold(app, "credit_score_min", 650)
    warn_min = pass_min - 50
    if score >= pass_min:
        return _pass("credit_score_threshold", {"score": score, "threshold": pass_min})
    if score >= warn_min:
        return _warn("credit_score_threshold", {"score": score, "band": f"{warn_min}-{pass_min}"})
    return _fail("credit_score_threshold", {"score": score, "threshold": warn_min})

def check_income_consistency(app: dict) -> dict | None:
    payslips = app["documents"].get("payslips", [])
    itr = app["documents"].get("itr")
    banks = app["documents"].get("bank_statements", [])
    if not payslips:
        return None
    nets = [n for n in (_to_float(_doc_field(p, "net_salary")) for p in payslips) if n]
    if not nets:
        return None
    monthly = sum(nets) / len(nets)
    annual_payslip = monthly * 12

    annual_itr = _to_float(_doc_field(itr, "gross_income") or _doc_field(itr, "taxable_income"))
    bank_credits = [c for c in (_to_float(_doc_field(b, "monthly_credits")) for b in banks) if c]
    annual_bank = (sum(bank_credits) / len(bank_credits)) * 12 if bank_credits else None

    details = {
        "annual_from_payslips": annual_payslip,
        "annual_from_itr": annual_itr,
        "annual_from_bank": annual_bank,
    }
    problems = []
    if annual_itr:
        diff = abs(annual_payslip - annual_itr) / max(annual_payslip, annual_itr)
        if diff > 0.20:
            problems.append({"source": "itr", "diff_pct": round(diff, 4)})
    if annual_bank:
        diff = abs(annual_payslip - annual_bank) / max(annual_payslip, annual_bank)
        if diff > 0.15:
            problems.append({"source": "bank", "diff_pct": round(diff, 4)})
    if problems:
        return _fail("income_consistency", {**details, "problems": problems})
    return _pass("income_consistency", details)

def check_address_consistency(app: dict) -> dict | None:
    addrs: dict[str, str] = {}
    aad = _doc_field(app["documents"].get("aadhaar"), "address")
    if aad:
        addrs["aadhaar"] = aad
    banks = app["documents"].get("bank_statements", [])
    for i, b in enumerate(banks):
        a = _doc_field(b, "address")
        if a:
            addrs[f"bank_{i + 1}"] = a
            break
    letter = _doc_field(app["documents"].get("employment_letter"), "address")
    if letter:
        addrs["employment_letter"] = letter
    if len(addrs) < 2:
        return None
    pairs = list(addrs.items())
    scores: list[float] = []
    for i in range(len(pairs)):
        for j in range(i + 1, len(pairs)):
            scores.append(float(fuzz.token_set_ratio(_norm_name(pairs[i][1]), _norm_name(pairs[j][1]))))
    avg = sum(scores) / len(scores) if scores else 0.0
    if avg >= 70:
        return _pass("address_consistency", {"avg_score": avg, "sources": list(addrs.keys())})
    if avg >= 50:
        return _warn("address_consistency", {"avg_score": avg, "sources": list(addrs.keys())})
    return _fail("address_consistency", {"avg_score": avg, "sources": list(addrs.keys())})

def check_ocr_quality(app: dict) -> dict:
    low: list[str] = []
    for doc_type, doc in app["documents"].items():
        if isinstance(doc, list):
            continue
        conf = doc.get("ocr_avg_confidence") if doc else None
        if conf is not None and conf < 0.75:
            low.append(doc_type)
    for lst_key in ("payslips", "bank_statements"):
        for i, d in enumerate(app["documents"].get(lst_key, [])):
            conf = d.get("ocr_avg_confidence")
            if conf is not None and conf < 0.75:
                low.append(f"{lst_key}[{i}]")
    if low:
        return _warn("ocr_quality", {"low_confidence_docs": low})
    return _pass("ocr_quality", {"low_confidence_docs": []})

def _monthly_income_from_payslips(app: dict) -> float | None:
    nets = [n for n in (_to_float(_doc_field(p, "net_salary")) for p in app["documents"].get("payslips", [])) if n]
    if not nets:
        return None
    return sum(nets) / len(nets)

def check_dti_ratio(app: dict) -> dict | None:
    monthly_income = _monthly_income_from_payslips(app)
    if not monthly_income or monthly_income <= 0:
        return None
    existing = _to_float(_doc_field(app["documents"].get("credit_report"), "existing_emi")) or 0.0
    proposed = _to_float(app.get("proposed_emi") or _doc_field(app["documents"].get("credit_report"), "proposed_emi")) or 0.0
    if existing == 0 and proposed == 0:
        return None
    ratio = (existing + proposed) / monthly_income
    fail_max = _threshold(app, "dti_max_ratio", 0.55)
    warn_min = max(fail_max - 0.15, 0.0)
    details = {"existing_emi": existing, "proposed_emi": proposed, "monthly_income": monthly_income, "ratio": round(ratio, 4), "threshold": fail_max}
    if ratio > fail_max:
        return _fail("dti_ratio", details)
    if ratio >= warn_min:
        return _warn("dti_ratio", details)
    return _pass("dti_ratio", details)

def check_income_stability(app: dict) -> dict | None:
    sources: list[float] = []
    for p in app["documents"].get("payslips", []):
        v = _to_float(_doc_field(p, "net_salary"))
        if v:
            sources.append(v)
    for b in app["documents"].get("bank_statements", []):
        v = _to_float(_doc_field(b, "monthly_credits"))
        if v:
            sources.append(v)
    if len(sources) < 3:
        return None
    mean = sum(sources) / len(sources)
    if mean <= 0:
        return None
    variance = sum((x - mean) ** 2 for x in sources) / len(sources)
    cv = math.sqrt(variance) / mean
    fail_max = _threshold(app, "income_cv_max", 0.40)
    warn_min = max(fail_max - 0.15, 0.0)
    details = {"sample_count": len(sources), "mean": round(mean, 2), "cv": round(cv, 4), "threshold": fail_max}
    if cv > fail_max:
        return _fail("income_stability", details)
    if cv >= warn_min:
        return _warn("income_stability", details)
    return _pass("income_stability", details)

_BOUNCE_PATTERNS = re.compile(r"\b(RETURN|BOUNCE|INSUFFICIENT\s+FUNDS|ECS\s+RETURN)\b", re.IGNORECASE)

def check_bank_statement_bounces(app: dict) -> dict | None:
    banks = app["documents"].get("bank_statements", [])
    if not banks:
        return None
    total = 0
    for b in banks:
        explicit = _to_float(_doc_field(b, "bounce_count")) or _to_float(_doc_field(b, "return_count"))
        if explicit is not None:
            total += int(explicit)
            continue
        narration = (_doc_field(b, "transactions_text") or _doc_field(b, "narration") or "")
        total += len(_BOUNCE_PATTERNS.findall(str(narration)))
    fail_min = int(_threshold(app, "bank_bounce_max", 3))
    details = {"total_bounces": total, "threshold": fail_min}
    if total >= fail_min:
        return _fail("bank_statement_bounces", details)
    if total >= 1:
        return _warn("bank_statement_bounces", details)
    return _pass("bank_statement_bounces", details)

def check_itr_payslip_reconciliation(app: dict) -> dict | None:
    itr = app["documents"].get("itr")
    annual_itr = _to_float(_doc_field(itr, "gross_income") or _doc_field(itr, "taxable_income"))
    monthly_payslip = _monthly_income_from_payslips(app)
    if not annual_itr or not monthly_payslip:
        return None
    implied_annual = monthly_payslip * 12
    deviation = abs(annual_itr - implied_annual) / max(annual_itr, implied_annual)
    fail_max = _threshold(app, "itr_payslip_deviation_max", 0.30)
    warn_min = max(fail_max - 0.15, 0.0)
    details = {"itr_annual": annual_itr, "implied_from_payslips": implied_annual, "deviation_pct": round(deviation, 4), "threshold": fail_max}
    if deviation > fail_max:
        return _fail("itr_payslip_reconciliation", details)
    if deviation >= warn_min:
        return _warn("itr_payslip_reconciliation", details)
    return _pass("itr_payslip_reconciliation", details)

def check_emi_burden(app: dict) -> dict | None:
    monthly_income = _monthly_income_from_payslips(app)
    if not monthly_income or monthly_income <= 0:
        return None
    proposed = _to_float(app.get("proposed_emi") or _doc_field(app["documents"].get("credit_report"), "proposed_emi"))
    if not proposed:
        return None
    burden = proposed / monthly_income
    fail_max = _threshold(app, "emi_burden_max", 0.50)
    warn_min = max(fail_max - 0.15, 0.0)
    details = {"proposed_emi": proposed, "monthly_income": monthly_income, "ratio": round(burden, 4), "threshold": fail_max}
    if burden > fail_max:
        return _fail("emi_burden", details)
    if burden >= warn_min:
        return _warn("emi_burden", details)
    return _pass("emi_burden", details)

def check_image_quality(app: dict) -> dict | None:
    id_docs = {"aadhaar": app["documents"].get("aadhaar"), "pan": app["documents"].get("pan")}
    id_docs = {k: v for k, v in id_docs.items() if v}
    if not id_docs:
        return None
    min_side = _threshold(app, "id_min_short_side_px", 600)
    ocr_min = _threshold(app, "ocr_confidence_min", 0.80)
    issues: list[dict] = []
    for name, doc in id_docs.items():
        width = _to_float(doc.get("image_width"))
        height = _to_float(doc.get("image_height"))
        ocr_conf = doc.get("ocr_avg_confidence")
        item: dict[str, Any] = {"doc": name}
        below_dim = False
        if width is not None and height is not None:
            short = min(width, height)
            item["short_side_px"] = short
            if short < min_side:
                below_dim = True
        below_ocr = ocr_conf is not None and ocr_conf < ocr_min
        if below_ocr:
            item["ocr_confidence"] = ocr_conf
        if below_dim or below_ocr:
            issues.append(item)
    details = {"min_side_threshold": min_side, "ocr_confidence_threshold": ocr_min, "issues": issues}
    if issues:
        return _fail("image_quality", details)
    return _pass("image_quality", details)

def check_duplicate_file_hashes(app: dict) -> dict:
    seen: dict[str, list[str]] = {}

    def _reg(key: str, h: str) -> None:
        if not h:
            return
        seen.setdefault(h, []).append(key)

    for dt, doc in app["documents"].items():
        if isinstance(doc, list):
            for i, d in enumerate(doc):
                _reg(f"{dt}[{i}]", d.get("file_hash"))
        elif isinstance(doc, dict):
            _reg(dt, doc.get("file_hash"))

    dups = {h: refs for h, refs in seen.items() if len(refs) > 1}
    if dups:
        return _fail("duplicate_file_hash", {"groups": list(dups.values())})
    return _pass("duplicate_file_hash", {"groups": []})

def check_aadhaar_address_present(app: dict) -> dict | None:
    addr = _doc_field(app["documents"].get("aadhaar"), "address")
    if addr is None:
        return None
    if len(str(addr).strip()) < 10:
        return _warn("aadhaar_address_present", {"reason": "address too short"})
    return _pass("aadhaar_address_present", {"length": len(str(addr))})

def check_applicant_name_match(app: dict) -> dict | None:
    """Applicant vs. documents name match.

    Matches the applicant-supplied full name (from signup / KYC form) against
    the name extracted from Aadhaar and PAN. This is the anti-impersonation
    defence: a fraudster uploading someone else's docs trips this check even
    if name_pan_vs_aadhaar passes (since PAN and Aadhaar are both the
    fraudster's target, and match each other).

    pass   ≥85   both docs match applicant within threshold
    warn   ≥70   edge case — transliteration, initials; admin review
    fail   <70   likely impersonation — reject
    """
    applicant = app.get("applicant_name")
    if not applicant:
        return None
    aad_name = _doc_field(app["documents"].get("aadhaar"), "full_name")
    pan_name = _doc_field(app["documents"].get("pan"), "full_name")
    scores: dict[str, float] = {}
    if aad_name:
        scores["aadhaar"] = _fuzzy_name(applicant, aad_name)
    if pan_name:
        scores["pan"] = _fuzzy_name(applicant, pan_name)
    if not scores:
        return None
    worst = min(scores.values())
    details = {"scores": scores, "applicant_name": applicant, "threshold": 85}
    if worst >= 85:
        return _pass("applicant_name_match", details)
    if worst >= 70:
        return _warn("applicant_name_match", details)
    return _fail("applicant_name_match", details)

def check_applicant_dob_match(app: dict) -> dict | None:
    """Applicant-supplied DOB vs. Aadhaar/PAN extracted DOB.

    Catches signups where the applicant registered with a DOB that doesn't
    match their documents — either a typo at signup or impersonation.
    Returns None when any side is missing (the `dob_consistency` check still
    cross-validates Aadhaar vs. PAN on its own).
    """
    applicant = _parse_date(app.get("applicant_dob"))
    if not applicant:
        return None
    mismatches: dict[str, str] = {}
    matches: dict[str, str] = {}
    for k in ("aadhaar", "pan"):
        d = _parse_date(_doc_field(app["documents"].get(k), "dob"))
        if not d:
            continue
        (mismatches if d != applicant else matches)[k] = d.isoformat()
    if not mismatches and not matches:
        return None
    details = {
        "applicant_dob": applicant.isoformat(),
        "matches": matches,
        "mismatches": mismatches,
    }
    if mismatches:
        return _fail("applicant_dob_match", details)
    return _pass("applicant_dob_match", details)

_KYC_CHECKS = [
    check_pan_format,
    check_aadhaar_verhoeff,
    check_name_pan_aadhaar,
    check_applicant_name_match,
    check_dob_consistency,
    check_applicant_dob_match,
    check_aadhaar_address_present,
    check_image_quality,
    check_duplicate_file_hashes,
    check_ocr_quality,
]

_LOAN_CHECKS = [
    check_pan_format,
    check_aadhaar_verhoeff,
    check_name_pan_aadhaar,
    check_applicant_name_match,
    check_dob_consistency,
    check_applicant_dob_match,
    check_itr_pan_matches_id,
    check_itr_ay_sanity,
    check_payslip_period_coverage,
    check_bank_period_coverage,
    check_payslip_bank_reconciliation,
    check_employer_consistency,
    check_employment_letter_recency,
    check_bank_holder_name,
    check_credit_pan_matches_id,
    check_credit_score_threshold,
    check_income_consistency,
    check_address_consistency,
    check_ocr_quality,
    check_duplicate_file_hashes,
    check_dti_ratio,
    check_income_stability,
    check_bank_statement_bounces,
    check_itr_payslip_reconciliation,
    check_emi_burden,
]

def run_all_checks(app: dict, use_case: str = "loan") -> list[dict]:
    checks = _KYC_CHECKS if use_case == "kyc" else _LOAN_CHECKS
    results: list[dict] = []
    for fn in checks:
        try:
            r = fn(app)
        except Exception as e:
            r = _warn(fn.__name__.replace("check_", ""), {"error": str(e)})
        if r is not None:
            results.append(r)
    return results
