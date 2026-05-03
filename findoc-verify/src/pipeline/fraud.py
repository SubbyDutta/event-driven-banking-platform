"""Fraud signal detection.

Produces a list of {signal_name, severity, score, details}. An aggregate
overall_fraud_score is computed by callers (risk.py) from the weighted sum.
"""
from __future__ import annotations

from typing import Any

SIGNAL_WEIGHTS: dict[str, float] = {
    "ocr_confidence": 0.10,
    "payslip_arithmetic": 0.20,
    "round_number_income": 0.05,
    "missing_sections": 0.10,
    "duplicate_file_hash": 0.25,
    "name_address_mismatch": 0.10,
    "dpd_inconsistency": 0.10,
    "font_inconsistency": 0.05,
    "file_metadata_gap": 0.05,
}

HIGH = "high"
MED = "med"
LOW = "low"

def _sig(name: str, severity: str, score: float, details: dict | None = None) -> dict:
    return {"signal_name": name, "severity": severity, "score": round(score, 4), "details": details or {}}

def _field(d: dict | None, k: str) -> Any:
    if not d:
        return None
    return (d.get("fields") or {}).get(k)

def _to_float(v: Any) -> float | None:
    if v is None:
        return None
    try:
        return float(str(v).replace(",", "").replace(" ", ""))
    except ValueError:
        return None

def sig_ocr_confidence(app: dict) -> dict | None:
    confs: list[float] = []
    for doc in app["documents"].values():
        if isinstance(doc, list):
            for d in doc:
                c = d.get("ocr_avg_confidence")
                if c is not None:
                    confs.append(float(c))
        elif isinstance(doc, dict):
            c = doc.get("ocr_avg_confidence")
            if c is not None:
                confs.append(float(c))
    if not confs:
        return None
    avg = sum(confs) / len(confs)
    if avg >= 0.85:
        return _sig("ocr_confidence", LOW, 0.0, {"avg": avg})
    if avg >= 0.7:
        return _sig("ocr_confidence", LOW, 0.3, {"avg": avg})
    if avg >= 0.55:
        return _sig("ocr_confidence", MED, 0.6, {"avg": avg})
    return _sig("ocr_confidence", HIGH, 0.9, {"avg": avg})

def sig_payslip_arithmetic(app: dict) -> dict | None:
    pays = app["documents"].get("payslips", [])
    if not pays:
        return None
    problems: list[dict] = []
    for i, p in enumerate(pays):
        gross = _to_float(_field(p, "gross_salary"))
        ded = _to_float(_field(p, "total_deductions"))
        net = _to_float(_field(p, "net_salary"))
        if not (gross and net):
            continue
        if ded is None:
            implied = gross - net
            if implied < 0:
                problems.append({"payslip": i + 1, "issue": "net > gross", "gross": gross, "net": net})
            continue
        expected = gross - ded
        diff = abs(expected - net) / max(net, 1)
        if diff > 0.03:
            problems.append({"payslip": i + 1, "gross": gross, "ded": ded, "net": net, "diff_pct": round(diff, 4)})
    if not problems:
        return _sig("payslip_arithmetic", LOW, 0.0, {"problems": []})
    severity = HIGH if len(problems) >= 2 else MED
    return _sig("payslip_arithmetic", severity, min(1.0, 0.4 * len(problems)), {"problems": problems})

def sig_round_number_income(app: dict) -> dict | None:
    pays = app["documents"].get("payslips", [])
    rounds = 0
    total = 0
    for p in pays:
        g = _to_float(_field(p, "gross_salary"))
        if g is None:
            continue
        total += 1
        if g == round(g, -3) and g > 0:
            rounds += 1
    if total == 0:
        return None
    if rounds == 0:
        return _sig("round_number_income", LOW, 0.0)
    ratio = rounds / total
    return _sig("round_number_income", LOW if ratio < 1 else MED, min(0.5, ratio * 0.5), {"ratio": ratio})

def sig_missing_sections(app: dict) -> dict | None:
    missing: list[str] = []
    for i, b in enumerate(app["documents"].get("bank_statements", [])):
        if not _field(b, "opening_balance") or not _field(b, "closing_balance"):
            missing.append(f"bank_{i + 1}_balance")
        if not _field(b, "total_credits") or not _field(b, "total_debits"):
            missing.append(f"bank_{i + 1}_totals")
    for i, p in enumerate(app["documents"].get("payslips", [])):
        if not _field(p, "gross_salary") or not _field(p, "net_salary"):
            missing.append(f"payslip_{i + 1}_salary")
    if not missing:
        return _sig("missing_sections", LOW, 0.0)
    return _sig("missing_sections", MED if len(missing) <= 2 else HIGH, min(1.0, 0.2 * len(missing)), {"missing": missing})

def sig_duplicate_file_hash(app: dict) -> dict | None:
    seen: dict[str, list[str]] = {}
    for dt, doc in app["documents"].items():
        if isinstance(doc, list):
            for i, d in enumerate(doc):
                h = d.get("file_hash")
                if h:
                    seen.setdefault(h, []).append(f"{dt}[{i}]")
        elif isinstance(doc, dict) and doc.get("file_hash"):
            seen.setdefault(doc["file_hash"], []).append(dt)
    dups = [refs for refs in seen.values() if len(refs) > 1]
    if not dups:
        return _sig("duplicate_file_hash", LOW, 0.0)
    return _sig("duplicate_file_hash", HIGH, 1.0, {"duplicate_groups": dups})

def sig_name_address_mismatch(app: dict, cross_doc_results: list[dict] | None = None) -> dict | None:
    if not cross_doc_results:
        return None
    mismatches: list[str] = []
    for r in cross_doc_results:
        if r["rule_name"] in ("name_matrix",) and r["status"] == "fail":
            mismatches.append("name")
    if not mismatches:
        return _sig("name_address_mismatch", LOW, 0.0)
    return _sig("name_address_mismatch", HIGH, 0.8, {"mismatches": mismatches})

def sig_dpd_inconsistency(app: dict) -> dict | None:
    cr = app["documents"].get("credit_report")
    if not cr:
        return None
    score = _to_float(_field(cr, "credit_score"))
    dpd = _to_float(_field(cr, "dpd_flag"))
    if score is None and dpd is None:
        return None
    if score and dpd and score >= 750 and dpd >= 60:
        return _sig("dpd_inconsistency", HIGH, 0.8, {"score": score, "dpd": dpd})
    return _sig("dpd_inconsistency", LOW, 0.0, {"score": score, "dpd": dpd})

ALL_SIGNALS = [
    sig_ocr_confidence,
    sig_payslip_arithmetic,
    sig_round_number_income,
    sig_missing_sections,
    sig_duplicate_file_hash,
    sig_dpd_inconsistency,
]

def run_all_signals(app: dict, cross_doc_results: list[dict] | None = None) -> list[dict]:
    out: list[dict] = []
    for fn in ALL_SIGNALS:
        try:
            r = fn(app)
        except Exception as e:
            r = _sig(fn.__name__.replace("sig_", ""), LOW, 0.0, {"error": str(e)})
        if r is not None:
            out.append(r)
    r = sig_name_address_mismatch(app, cross_doc_results)
    if r is not None:
        out.append(r)
    return out

def aggregate_fraud_score(signals: list[dict]) -> float:
    total_w = 0.0
    weighted = 0.0
    for s in signals:
        w = SIGNAL_WEIGHTS.get(s["signal_name"], 0.05)
        weighted += w * float(s["score"])
        total_w += w
    if total_w == 0:
        return 0.0
    return round(weighted / total_w, 4)
