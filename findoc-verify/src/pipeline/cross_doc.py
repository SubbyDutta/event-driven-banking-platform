"""Cross-doc validation rules.

Inputs same as compliance.py. Outputs list of
{rule_name, status, involved_doc_types, details}.
"""
from __future__ import annotations

import re
from typing import Any

from rapidfuzz import fuzz

AMOUNT_TOL_PCT = 0.05
ANNUAL_TOL_PCT = 0.20

def _pass(r: str, docs: list[str], d: dict | None = None) -> dict:
    return {"rule_name": r, "status": "pass", "involved_doc_types": docs, "details": d or {}}

def _fail(r: str, docs: list[str], d: dict | None = None) -> dict:
    return {"rule_name": r, "status": "fail", "involved_doc_types": docs, "details": d or {}}

def _warn(r: str, docs: list[str], d: dict | None = None) -> dict:
    return {"rule_name": r, "status": "warning", "involved_doc_types": docs, "details": d or {}}

def _norm(s: str | None) -> str:
    if not s:
        return ""
    s = re.sub(r"[^A-Za-z\s]", " ", s)
    return re.sub(r"\s+", " ", s).strip().upper()

def _field(doc: dict | None, key: str) -> Any:
    if not doc:
        return None
    return (doc.get("fields") or {}).get(key)

def _to_float(v: Any) -> float | None:
    if v is None:
        return None
    try:
        return float(str(v).replace(",", "").replace(" ", ""))
    except ValueError:
        return None

def rule_name_matrix(app: dict) -> dict:
    names: dict[str, str] = {}
    declared = _norm(app.get("applicant_name"))
    if declared:
        names["declared"] = declared
    docs = app["documents"]
    for dt in ("aadhaar", "pan", "employment_letter", "itr", "credit_report"):
        v = _field(docs.get(dt), "full_name")
        if v:
            names[dt] = _norm(v)
    for i, p in enumerate(docs.get("payslips", [])):
        v = _field(p, "full_name") or _field(p, "employee_name")
        if v:
            names[f"payslip_{i + 1}"] = _norm(v)
    for i, b in enumerate(docs.get("bank_statements", [])):
        v = _field(b, "account_holder") or _field(b, "full_name")
        if v:
            names[f"bank_{i + 1}"] = _norm(v)
    if len(names) < 2:
        return _warn("name_matrix", list(names.keys()), {"reason": "need at least 2 sources"})
    keys = list(names)
    pairs = []
    for i in range(len(keys)):
        for j in range(i + 1, len(keys)):
            score = float(fuzz.token_sort_ratio(names[keys[i]], names[keys[j]]))
            pairs.append({"a": keys[i], "b": keys[j], "score": score})
    min_score = min(p["score"] for p in pairs)
    avg = sum(p["score"] for p in pairs) / len(pairs)
    if min_score >= 80:
        return _pass("name_matrix", keys, {"avg": avg, "min": min_score})
    if min_score >= 65:
        return _warn("name_matrix", keys, {"avg": avg, "min": min_score, "pairs": pairs})
    return _fail("name_matrix", keys, {"avg": avg, "min": min_score, "pairs": pairs})

def rule_pan_matrix(app: dict) -> dict | None:
    pans: dict[str, str] = {}
    docs = app["documents"]
    for dt in ("pan", "itr", "credit_report"):
        v = _field(docs.get(dt), "pan_number")
        if v:
            pans[dt] = v.strip().upper()
    for i, p in enumerate(docs.get("payslips", [])):
        v = _field(p, "pan_number")
        if v:
            pans[f"payslip_{i + 1}"] = v.strip().upper()
    if len(pans) < 2:
        return None
    unique = set(pans.values())
    if len(unique) == 1:
        return _pass("pan_matrix", list(pans.keys()), {"pan_last": next(iter(unique))[-4:]})
    return _fail("pan_matrix", list(pans.keys()), {"unique_count": len(unique)})

def rule_dob_matrix(app: dict) -> dict | None:
    dobs: dict[str, str] = {}
    declared = app.get("applicant_dob")
    if declared:
        dobs["declared"] = str(declared).strip()
    for dt in ("aadhaar", "pan", "itr", "credit_report"):
        v = _field(app["documents"].get(dt), "dob")
        if v:
            dobs[dt] = str(v).strip()
    if len(dobs) < 2:
        return None
    unique = set(dobs.values())
    if len(unique) == 1:
        return _pass("dob_matrix", list(dobs.keys()), {"dob": next(iter(unique))})
    return _fail("dob_matrix", list(dobs.keys()), {"dobs": dobs})

def rule_employer_matrix(app: dict) -> dict | None:
    docs = app["documents"]
    letter = _field(docs.get("employment_letter"), "employer_name")
    payslip_emps = [_field(p, "employer_name") for p in docs.get("payslips", []) if _field(p, "employer_name")]
    if not letter and not payslip_emps:
        return None
    sources: dict[str, str] = {}
    if letter:
        sources["employment_letter"] = _norm(letter)
    for i, e in enumerate(payslip_emps):
        sources[f"payslip_{i + 1}"] = _norm(e)
    if len(sources) < 2:
        return None
    keys = list(sources)
    scores = []
    for i in range(len(keys)):
        for j in range(i + 1, len(keys)):
            scores.append(float(fuzz.token_sort_ratio(sources[keys[i]], sources[keys[j]])))
    min_s = min(scores)
    avg_s = sum(scores) / len(scores)
    if min_s >= 90:
        return _pass("employer_matrix", keys, {"avg": avg_s, "min": min_s})
    if min_s >= 75:
        return _warn("employer_matrix", keys, {"avg": avg_s, "min": min_s})
    return _fail("employer_matrix", keys, {"avg": avg_s, "min": min_s})

def rule_period_overlap(app: dict) -> dict | None:
    docs = app["documents"]
    pay_months = {str(p.get("period_month"))[:7] for p in docs.get("payslips", []) if p.get("period_month")}
    bank_months = {str(b.get("period_month"))[:7] for b in docs.get("bank_statements", []) if b.get("period_month")}
    if not pay_months or not bank_months:
        return None
    missing = pay_months - bank_months
    if not missing:
        return _pass("period_overlap", ["payslips", "bank_statements"], {"months": sorted(pay_months)})
    return _fail(
        "period_overlap",
        ["payslips", "bank_statements"],
        {"payslip_months_missing_in_bank": sorted(missing)},
    )

def rule_payslip_bank_amount(app: dict) -> dict | None:
    pays = app["documents"].get("payslips", [])
    banks = app["documents"].get("bank_statements", [])
    if not pays or not banks:
        return None
    per: list[dict] = []
    bank_by_month = {str(b.get("period_month"))[:7]: b for b in banks if b.get("period_month")}
    for p in pays:
        mk = str(p.get("period_month"))[:7]
        bk = bank_by_month.get(mk)
        if not bk:
            continue
        net = _to_float(_field(p, "net_salary"))
        cr = _to_float(_field(bk, "monthly_credits"))
        if net and cr:
            diff = abs(net - cr) / max(net, cr)
            per.append({"month": mk, "diff_pct": round(diff, 4), "pass": diff <= AMOUNT_TOL_PCT})
    if not per:
        return None
    all_pass = all(x["pass"] for x in per)
    any_fail = any(x["diff_pct"] > 0.15 for x in per)
    if all_pass:
        return _pass("payslip_bank_amount", ["payslips", "bank_statements"], {"per_month": per})
    if any_fail:
        return _fail("payslip_bank_amount", ["payslips", "bank_statements"], {"per_month": per})
    return _warn("payslip_bank_amount", ["payslips", "bank_statements"], {"per_month": per})

def rule_annualised_income(app: dict) -> dict | None:
    pays = app["documents"].get("payslips", [])
    itr = app["documents"].get("itr")
    banks = app["documents"].get("bank_statements", [])
    nets = [n for n in (_to_float(_field(p, "net_salary")) for p in pays) if n]
    if not nets:
        return None
    annual_pay = (sum(nets) / len(nets)) * 12
    annual_itr = _to_float(_field(itr, "gross_income") or _field(itr, "taxable_income"))
    bank_credits = [c for c in (_to_float(_field(b, "monthly_credits")) for b in banks) if c]
    annual_bank = (sum(bank_credits) / len(bank_credits)) * 12 if bank_credits else None

    problems = []
    if annual_itr:
        diff = abs(annual_pay - annual_itr) / max(annual_pay, annual_itr)
        if diff > ANNUAL_TOL_PCT:
            problems.append({"source": "itr", "diff_pct": round(diff, 4)})
    if annual_bank:
        diff = abs(annual_pay - annual_bank) / max(annual_pay, annual_bank)
        if diff > ANNUAL_TOL_PCT:
            problems.append({"source": "bank", "diff_pct": round(diff, 4)})

    details = {"annual_payslip": annual_pay, "annual_itr": annual_itr, "annual_bank": annual_bank}
    involved = ["payslips", "itr", "bank_statements"]
    if not problems:
        return _pass("annualised_income_match", involved, details)
    return _fail("annualised_income_match", involved, {**details, "problems": problems})

_KYC_RULES = [
    rule_name_matrix,
    rule_pan_matrix,
    rule_dob_matrix,
]

_LOAN_RULES = [
    rule_name_matrix,
    rule_pan_matrix,
    rule_dob_matrix,
    rule_employer_matrix,
    rule_period_overlap,
    rule_payslip_bank_amount,
    rule_annualised_income,
]

def run_all_rules(app: dict, use_case: str = "loan") -> list[dict]:
    rules = _KYC_RULES if use_case == "kyc" else _LOAN_RULES
    out: list[dict] = []
    for fn in rules:
        try:
            r = fn(app)
        except Exception as e:
            r = _warn(fn.__name__.replace("rule_", ""), [], {"error": str(e)})
        if r is not None:
            out.append(r)
    return out
