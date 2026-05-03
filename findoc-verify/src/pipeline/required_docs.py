from __future__ import annotations

from dataclasses import dataclass

from src.db.models import DocType, UseCase

@dataclass
class SubmissionValidation:
    ok: bool
    missing: dict[str, str]

LOAN_REQUIRED_SLOTS = {
    "bank_statement_1": DocType.bank_statement,
    "bank_statement_2": DocType.bank_statement,
    "bank_statement_3": DocType.bank_statement,
    "payslip_1": DocType.payslip,
    "payslip_2": DocType.payslip,
    "payslip_3": DocType.payslip,
    "employment_letter": DocType.employment_letter,
    "itr": DocType.itr,
    "credit_report": DocType.credit_report,
}
LOAN_ID_SLOTS = {
    "aadhaar": DocType.aadhaar,
    "pan": DocType.pan,
}

KYC_REQUIRED_SLOTS = {
    "aadhaar": DocType.aadhaar,
    "pan": DocType.pan,
}

def validate_loan_submission(provided: dict[str, bool]) -> SubmissionValidation:
    missing: dict[str, str] = {}
    for field in LOAN_REQUIRED_SLOTS:
        if not provided.get(field):
            missing[field] = f"{field.replace('_', ' ')} is required"
    if not (provided.get("aadhaar") or provided.get("pan")):
        missing["aadhaar_or_pan"] = "At least one of Aadhaar or PAN is required"
    return SubmissionValidation(ok=not missing, missing=missing)

def validate_kyc_submission(provided: dict[str, bool]) -> SubmissionValidation:
    missing: dict[str, str] = {}
    for field in KYC_REQUIRED_SLOTS:
        if not provided.get(field):
            missing[field] = f"{field} is required for KYC"
    return SubmissionValidation(ok=not missing, missing=missing)

def slots_for(use_case: UseCase) -> dict[str, DocType]:
    if use_case == UseCase.kyc:
        return KYC_REQUIRED_SLOTS
    return {**LOAN_ID_SLOTS, **LOAN_REQUIRED_SLOTS}
