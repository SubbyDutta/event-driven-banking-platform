"""Risk scoring + LoanReport assembly.

Combines compliance checks, cross-doc results, fraud signals, and financial
aggregates into a final LoanReport dict suitable for `loan_reports.report_json`.
"""
from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

from src.pipeline.fraud import aggregate_fraud_score

DTI_COMFORTABLE = 0.30
DTI_ACCEPTABLE = 0.40
DTI_MAX = 0.45
DTI_OVER = 0.55

CREDIT_APPROVE_MIN = 700
CREDIT_FAIL_MAX = 600

FRAUD_CLEAN_MAX = 0.30
FRAUD_REVIEW_MAX = 0.60

def _to_float(v: Any) -> float | None:
    if v is None:
        return None
    try:
        return float(str(v).replace(",", "").replace(" ", ""))
    except ValueError:
        return None

def _field(d: dict | None, k: str) -> Any:
    if not d:
        return None
    return (d.get("fields") or {}).get(k)

def compute_income(app: dict) -> dict:
    pays = app["documents"].get("payslips", [])
    banks = app["documents"].get("bank_statements", [])
    itr = app["documents"].get("itr") or {}

    nets = [n for n in (_to_float(_field(p, "net_salary")) for p in pays) if n]
    grosses = [n for n in (_to_float(_field(p, "gross_salary")) for p in pays) if n]
    bank_credits = [c for c in (_to_float(_field(b, "monthly_credits")) for b in banks) if c]

    monthly_payslip = sum(nets) / len(nets) if nets else None
    annual_payslip = monthly_payslip * 12 if monthly_payslip else None
    monthly_gross = sum(grosses) / len(grosses) if grosses else None

    monthly_bank = sum(bank_credits) / len(bank_credits) if bank_credits else None
    annual_bank = monthly_bank * 12 if monthly_bank else None

    annual_itr = _to_float(_field(itr, "gross_income") or _field(itr, "taxable_income"))

    candidates = [x for x in (annual_payslip, annual_bank, annual_itr) if x]
    declared_annual = max(candidates) if candidates else None
    declared_monthly = declared_annual / 12 if declared_annual else None

    return {
        "monthly_payslip_net": monthly_payslip,
        "monthly_payslip_gross": monthly_gross,
        "annual_from_payslip": annual_payslip,
        "monthly_from_bank": monthly_bank,
        "annual_from_bank": annual_bank,
        "annual_from_itr": annual_itr,
        "declared_monthly_inr": declared_monthly,
        "declared_annual_inr": declared_annual,
    }

def compute_dti(app: dict, monthly_income: float | None) -> dict:
    existing_emi = _to_float(_field(app["documents"].get("credit_report"), "existing_emi"))
    if existing_emi is None:
        totals = []
        for p in app["documents"].get("payslips", []):
            v = _to_float(_field(p, "existing_emi"))
            if v:
                totals.append(v)
        existing_emi = sum(totals) / len(totals) if totals else 0.0

    if monthly_income and monthly_income > 0:
        dti = existing_emi / monthly_income
    else:
        dti = None

    status = None
    if dti is None:
        status = "unknown"
    elif dti <= DTI_COMFORTABLE:
        status = "comfortable"
    elif dti <= DTI_ACCEPTABLE:
        status = "acceptable"
    elif dti <= DTI_MAX:
        status = "maximum"
    elif dti <= DTI_OVER:
        status = "stretched"
    else:
        status = "over_indebted"

    return {"existing_emi_inr": existing_emi, "dti_ratio": dti, "dti_status": status}

def recommend(
    compliance: list[dict],
    cross_doc: list[dict],
    fraud_signals: list[dict],
    credit_score: int | None,
    dti: float | None,
    thresholds: dict[str, float] | None = None,
) -> tuple[str, float, dict]:
    fraud_score = aggregate_fraud_score(fraud_signals)
    t = thresholds or {}
    approve_min = float(t.get("recommendation_approve_min_score", 0.85))
    reject_max = float(t.get("recommendation_reject_max_score", 0.45))

    comp_fails = [c for c in compliance if c["status"] == "fail"]
    comp_warns = [c for c in compliance if c["status"] == "warning"]
    cross_fails = [c for c in cross_doc if c["status"] == "fail"]
    cross_warns = [c for c in cross_doc if c["status"] == "warning"]

    reasons: dict[str, Any] = {
        "compliance_fails": [c["name"] for c in comp_fails],
        "compliance_warnings": [c["name"] for c in comp_warns],
        "cross_doc_fails": [c["rule_name"] for c in cross_fails],
        "cross_doc_warnings": [c["rule_name"] for c in cross_warns],
        "fraud_score": fraud_score,
        "credit_score": credit_score,
        "dti_ratio": dti,
        "approve_min_score": approve_min,
        "reject_max_score": reject_max,
    }

    overall_pct = _overall_score(fraud_score, len(comp_fails), len(cross_fails), dti)
    confidence = max(0.0, 1.0 - overall_pct / 100.0)
    reasons["confidence"] = round(confidence, 4)

    if comp_fails:
        reasons["reject_reason"] = "compliance_failures"
        return "reject", overall_pct, reasons
    if cross_fails:
        reasons["reject_reason"] = "cross_doc_failures"
        return "reject", _overall_score(fraud_score, 0, len(cross_fails), dti), reasons
    if fraud_score > FRAUD_REVIEW_MAX:
        reasons["reject_reason"] = "high_fraud_score"
        return "reject", _overall_score(fraud_score, 0, 0, dti), reasons
    if credit_score is not None and credit_score < CREDIT_FAIL_MAX:
        reasons["reject_reason"] = "credit_score_below_threshold"
        return "reject", _overall_score(fraud_score, 0, 0, dti), reasons

    if confidence < reject_max:
        reasons["reject_reason"] = "low_confidence_below_threshold"
        return "reject", _overall_score(fraud_score, 0, 0, dti), reasons

    if (
        confidence >= approve_min
        and fraud_score < FRAUD_CLEAN_MAX
        and (credit_score is None or credit_score >= CREDIT_APPROVE_MIN)
        and (dti is None or dti < 0.40)
        and not comp_warns
        and not cross_warns
    ):
        return "approve", _overall_score(fraud_score, 0, 0, dti), reasons

    return "manual_review", _overall_score(fraud_score, 0, len(cross_warns), dti, warn=True), reasons

def _overall_score(fraud: float, comp_fails: int, cross_fails: int, dti: float | None, warn: bool = False) -> float:
    score = 0.0
    score += fraud * 40
    score += min(comp_fails * 15, 45)
    score += min(cross_fails * 10, 30)
    if dti:
        score += min(max((dti - 0.3) * 80, 0), 15)
    if warn:
        score += 5
    return round(min(score, 100), 2)

def _recommend_kyc(
    compliance: list[dict],
    cross_doc: list[dict],
    fraud_signals: list[dict],
) -> tuple[str, float, dict]:
    fraud_score = aggregate_fraud_score(fraud_signals)
    comp_fails = [c for c in compliance if c["status"] == "fail"]
    comp_warns = [c for c in compliance if c["status"] == "warning"]
    cross_fails = [c for c in cross_doc if c["status"] == "fail"]
    cross_warns = [c for c in cross_doc if c["status"] == "warning"]

    reasons: dict[str, Any] = {
        "compliance_fails": [c["name"] for c in comp_fails],
        "compliance_warnings": [c["name"] for c in comp_warns],
        "cross_doc_fails": [c["rule_name"] for c in cross_fails],
        "cross_doc_warnings": [c["rule_name"] for c in cross_warns],
        "fraud_score": fraud_score,
    }

    score = 0.0
    score += fraud_score * 40
    score += min(len(comp_fails) * 15, 45)
    score += min(len(cross_fails) * 10, 30)
    score = round(min(score, 100), 2)

    if comp_fails or cross_fails or fraud_score > FRAUD_REVIEW_MAX:
        reasons["reject_reason"] = "kyc_mismatch_or_fraud"
        return "reject", score, reasons
    if not comp_warns and not cross_warns and fraud_score < FRAUD_CLEAN_MAX:
        return "verified", score, reasons
    return "manual_review", score + 5, reasons

def assemble_kyc_report(
    application: dict,
    compliance_results: list[dict],
    cross_doc_results: list[dict],
    fraud_signals: list[dict],
) -> dict:
    rec, overall, reasons = _recommend_kyc(compliance_results, cross_doc_results, fraud_signals)
    fraud_score = aggregate_fraud_score(fraud_signals)
    return {
        "schemaVersion": 1,
        "useCase": "kyc",
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "applicationId": str(application.get("application_id")),
        "correlationId": application.get("external_id"),
        "applicant": {
            "name": application.get("applicant_name"),
            "email": application.get("email"),
            "phone": application.get("phone"),
        },
        "recommendation": rec,
        "overall_score": overall,
        "fraud": {"overall_score": fraud_score, "signals": fraud_signals},
        "compliance_checks": compliance_results,
        "cross_doc_validations": cross_doc_results,
        "reasons": reasons,
    }

def assemble_loan_report(
    application: dict,
    compliance_results: list[dict],
    cross_doc_results: list[dict],
    fraud_signals: list[dict],
) -> dict:
    income = compute_income(application)
    credit_score_raw = _to_float(_field(application["documents"].get("credit_report"), "credit_score"))
    credit_score = int(credit_score_raw) if credit_score_raw is not None else None

    dti_data = compute_dti(application, income.get("declared_monthly_inr"))
    rec, overall, reasons = recommend(
        compliance_results, cross_doc_results, fraud_signals, credit_score, dti_data["dti_ratio"],
        thresholds=application.get("_thresholds"),
    )
    fraud_score = aggregate_fraud_score(fraud_signals)

    return {
        "schemaVersion": 1,
        "useCase": "loan",
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "applicationId": str(application.get("application_id")),
        "correlationId": application.get("external_id"),
        "applicant": {
            "name": application.get("applicant_name"),
            "email": application.get("email"),
            "phone": application.get("phone"),
        },
        "recommendation": rec,
        "overall_score": overall,
        "income": income,
        "debt": dti_data,
        "credit_score": credit_score,
        "fraud": {"overall_score": fraud_score, "signals": fraud_signals},
        "compliance_checks": compliance_results,
        "cross_doc_validations": cross_doc_results,
        "reasons": reasons,
    }
