"""Generate 5 scenario segments under infra/fixtures/segments/.

Each segment is a folder with kyc/ and loan/ subfolders, populated with
the full doc set for one test identity. Run after `purge-data` to start
a fresh E2E pass.

    pip install reportlab
    python infra/generate_segments.py
"""
from __future__ import annotations

import calendar
from dataclasses import dataclass
from datetime import date, timedelta
from pathlib import Path
from typing import Optional

from reportlab.lib.pagesizes import A4
from reportlab.pdfgen import canvas

OUT = Path(__file__).parent / "fixtures" / "segments"
OUT.mkdir(parents=True, exist_ok=True)

_D = [
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
_P = [
    [0, 1, 2, 3, 4, 5, 6, 7, 8, 9],
    [1, 5, 7, 6, 2, 8, 3, 0, 9, 4],
    [5, 8, 0, 3, 7, 9, 6, 1, 4, 2],
    [8, 9, 1, 6, 0, 4, 3, 5, 2, 7],
    [9, 4, 5, 3, 1, 2, 6, 8, 7, 0],
    [4, 2, 8, 6, 5, 7, 3, 9, 0, 1],
    [2, 7, 9, 3, 8, 0, 6, 4, 1, 5],
    [7, 0, 4, 6, 9, 1, 3, 2, 5, 8],
]
_INV = [0, 4, 3, 2, 1, 5, 6, 7, 8, 9]


def verhoeff_make(prefix11: str) -> str:
    if len(prefix11) != 11 or not prefix11.isdigit():
        raise ValueError("prefix11 must be 11 digits")
    c = 0
    for i, d in enumerate(reversed(prefix11), start=1):
        c = _D[c][_P[i % 8][int(d)]]
    return prefix11 + str(_INV[c])


def last_n_months(n: int) -> list[date]:
    today = date.today()
    months: list[date] = []
    y, m = today.year, today.month
    for _ in range(n):
        m -= 1
        if m == 0:
            m, y = 12, y - 1
        months.append(date(y, m, 1))
    return list(reversed(months))


@dataclass
class Identity:
    name: str
    dob: str
    pan: str
    aadhaar: str
    mobile: str
    email: str
    address: str
    employer: str
    designation: str
    doj: str
    annual_ctc: str
    gross_pay: str
    net_pay: str
    bank_account: str
    ifsc: str
    opening_bal: str
    monthly_credits: list[str]
    closing_bal: str
    credit_score: str
    active_loans: str
    bounce_count: int = 0


@dataclass
class Segment:
    folder: str
    expected: str
    notes: str
    kyc_identity: Identity
    loan_identity: Optional[Identity] = None


def render(path: Path, lines: list[tuple[str, str]], title: str) -> None:
    try:
        c = canvas.Canvas(str(path), pagesize=A4)
    except PermissionError:
        path = path.with_suffix(path.suffix + ".new")
        c = canvas.Canvas(str(path), pagesize=A4)
    width, height = A4
    c.setFont("Helvetica-Bold", 16)
    c.drawString(72, height - 72, title)
    c.setFont("Helvetica", 11)
    y = height - 110
    for label, value in lines:
        c.drawString(72, y, f"{label}: {value}")
        y -= 22
    c.setFont("Helvetica-Oblique", 8)
    c.drawString(72, 40, "DEMO FIXTURE — for local smoke tests only.")
    c.showPage()
    c.save()


def write_set(folder: Path, who: Identity) -> None:
    folder.mkdir(parents=True, exist_ok=True)
    period_months = last_n_months(3)
    employment_letter_date = (date.today() - timedelta(days=30)).isoformat()

    render(folder / "aadhaar.pdf", [
        ("Name", who.name), ("DOB", who.dob), ("Aadhaar Number", who.aadhaar),
        ("Gender", "MALE"), ("Issued By", "Government of India"),
        ("Address", who.address),
    ], "Aadhaar Card")

    render(folder / "pan.pdf", [
        ("Name", who.name), ("DOB", who.dob), ("PAN", who.pan),
        ("Issued By", "Income Tax Department"),
    ], "Permanent Account Number Card")

    for idx, dm in enumerate(period_months, start=1):
        month_label = dm.strftime("%B %Y")
        last_day = calendar.monthrange(dm.year, dm.month)[1]
        period_str = f"{dm.isoformat()} to {dm.replace(day=last_day).isoformat()}"
        bounce_line: list[tuple[str, str]] = []
        if who.bounce_count > 0 and idx <= who.bounce_count:
            bounce_line = [("Note", "ECS RETURN on 12th — INSUFFICIENT FUNDS")]
        credit_amount = who.monthly_credits[idx - 1] if idx <= len(who.monthly_credits) else who.monthly_credits[-1]
        render(folder / f"bank-statement-{idx:02d}.pdf", [
            ("Account Holder", who.name),
            ("Account Number", who.bank_account),
            ("IFSC", who.ifsc),
            ("Statement Period", period_str),
            ("Statement Month", month_label),
            ("Opening Balance", f"Rs. {who.opening_bal}"),
            ("Total Credits", f"Rs. {credit_amount}"),
            ("Total Debits", "Rs. 0.00"),
            ("Closing Balance", f"Rs. {who.closing_bal}"),
            *bounce_line,
        ], f"Bank Statement — {month_label}")

    for idx, dm in enumerate(period_months, start=1):
        month_label = dm.strftime("%B %Y")
        last_day = calendar.monthrange(dm.year, dm.month)[1]
        render(folder / f"payslip-{idx:02d}.pdf", [
            ("Name", who.name),
            ("Employee ID", "EMP-7788"),
            ("Employer", who.employer),
            ("Pay Period", f"{dm.isoformat()} to {dm.replace(day=last_day).isoformat()}"),
            ("Pay Month", month_label),
            ("Gross Pay", f"Rs. {who.gross_pay} per month"),
            ("Net Pay", f"Rs. {who.net_pay} per month"),
            ("PAN", who.pan),
        ], f"Payslip — {month_label}")

    render(folder / "employment-letter.pdf", [
        ("Letter Date", employment_letter_date),
        ("Name", who.name),
        ("Employer", who.employer),
        ("Designation", who.designation),
        ("Date of Joining", who.doj),
        ("Annual CTC", f"Rs. {who.annual_ctc}"),
    ], "Employment Verification Letter")

    render(folder / "itr.pdf", [
        ("Name", who.name), ("PAN", who.pan), ("Assessment Year", "2025-26"),
        ("Gross Total Income", f"Rs. {who.annual_ctc}"),
        ("Tax Paid", "Rs. 42,500"),
    ], "ITR Acknowledgement AY 2025-26")

    render(folder / "credit-report.pdf", [
        ("Name", who.name), ("PAN", who.pan),
        ("Bureau", "TransUnion CIBIL"), ("Score", who.credit_score),
        ("Active Loans", who.active_loans),
        ("Credit Cards", "2 (utilization 22%)"),
        ("Enquiries (last 6mo)", "1"),
    ], "Credit Bureau Report")


SUBHAM = Identity(
    name="Subham Dutta", dob="1995-05-15",
    pan="AXZPD5678K", aadhaar=verhoeff_make("23411234123"),
    mobile="9876543210", email="subham.dutta@example.com",
    address="12 MG Road, Bengaluru 560001, Karnataka",
    employer="Acme Corp India Pvt Ltd", designation="Senior Engineer",
    doj="2022-06-01", annual_ctc="9,00,000",
    gross_pay="75,000", net_pay="68,250",
    bank_account="1234567890", ifsc="ACME0000123",
    opening_bal="1,20,000.00",
    monthly_credits=["75,000.00", "75,000.00", "75,000.00"],
    closing_bal="1,95,000.00",
    credit_score="752", active_loans="0",
)

RAJAT = Identity(
    name="Rajat Sharma", dob="1992-08-20",
    pan="BRSPS4567L", aadhaar=verhoeff_make("47823456712"),
    mobile="9123456780", email="rajat.sharma@example.com",
    address="55 Park Street, Kolkata 700016, West Bengal",
    employer="Globex Solutions Pvt Ltd", designation="Software Engineer",
    doj="2023-01-15", annual_ctc="7,20,000",
    gross_pay="60,000", net_pay="54,000",
    bank_account="9988776655", ifsc="GLBX0000456",
    opening_bal="80,000.00",
    monthly_credits=["54,000.00", "54,000.00", "54,000.00"],
    closing_bal="1,15,000.00",
    credit_score="540", active_loans="2",
    bounce_count=2,
)

PRIYA = Identity(
    name="Priya Mehta", dob="1996-03-10",
    pan="CPMTH3456M", aadhaar=verhoeff_make("63411289011"),
    mobile="9001122334", email="priya.mehta@example.com",
    address="22 Linking Road, Mumbai 400050, Maharashtra",
    employer="Initech Technologies", designation="Engineer",
    doj="2023-09-01", annual_ctc="6,60,000",
    gross_pay="55,000", net_pay="49,500",
    bank_account="1112223334", ifsc="INTC0000789",
    opening_bal="65,000.00",
    monthly_credits=["49,500.00", "49,500.00", "49,500.00"],
    closing_bal="1,05,000.00",
    credit_score="710", active_loans="0",
)

VIKRAM = Identity(
    name="Vikram Iyer", dob="1990-11-22",
    pan="BVIYP9012J", aadhaar=verhoeff_make("85567789011"),
    mobile="9445566778", email="vikram.iyer@example.com",
    address="9 TT Krishnamachari Rd, Chennai 600006, Tamil Nadu",
    employer="Initrode Industries", designation="Principal Engineer",
    doj="2018-04-01", annual_ctc="24,00,000",
    gross_pay="2,00,000", net_pay="1,75,000",
    bank_account="7654321098", ifsc="INTR0000999",
    opening_bal="8,50,000.00",
    monthly_credits=["1,75,000.00", "1,75,000.00", "1,75,000.00"],
    closing_bal="11,25,000.00",
    credit_score="820", active_loans="0",
)

ARJUN = Identity(
    name="Arjun Verma", dob="1993-12-05",
    pan="CARPR2345N", aadhaar=verhoeff_make("32145678902"),
    mobile="9776655443", email="arjun.verma@example.com",
    address="100 Sector 17, Chandigarh 160017",
    employer="Hooli India Pvt Ltd", designation="Senior Engineer",
    doj="2021-07-12", annual_ctc="9,60,000",
    gross_pay="80,000", net_pay="72,000",
    bank_account="2233445566", ifsc="HOOL0000234",
    opening_bal="1,40,000.00",
    monthly_credits=["72,000.00", "48,000.00", "95,000.00"],
    closing_bal="1,85,000.00",
    credit_score="680", active_loans="1",
)

NEHA = Identity(
    name="Neha Pillai", dob="1997-07-18",
    pan="DNHPL6789E", aadhaar=verhoeff_make("70123456781"),
    mobile="9112233445", email="neha.pillai@example.com",
    address="7 Marine Drive, Kochi 682011, Kerala",
    employer="Pied Piper Software", designation="Junior Engineer",
    doj="2024-08-20", annual_ctc="4,20,000",
    gross_pay="35,000", net_pay="31,500",
    bank_account="5544332211", ifsc="PIED0000567",
    opening_bal="40,000.00",
    monthly_credits=["31,500.00", "31,500.00", "31,500.00"],
    closing_bal="65,000.00",
    credit_score="720", active_loans="0",
)


SEGMENTS = [
    Segment(
        folder="1_subham_approved",
        expected="APPROVED",
        notes="Baseline. Strong credit (752), stable income, no bounces. Should auto-approve through ML risk band B/C.",
        kyc_identity=SUBHAM,
    ),
    Segment(
        folder="2_rajat_rejected_credit",
        expected="REJECTED",
        notes="Credit score 540 (below 650 threshold), 2 bank bounces, 2 active loans. Multiple compliance fails — auto-reject before ML step.",
        kyc_identity=RAJAT,
    ),
    Segment(
        folder="3_priya_kyc_vikram_loan",
        expected="REJECTED (cross-doc / KycIdentityGuard)",
        notes="Priya does KYC with own docs. Loan submission uses Vikram's bank statements/payslips/ITR/credit report. Cross-doc fails AND KycIdentityGuard hard-rejects on PAN mismatch. Showpiece scenario.",
        kyc_identity=PRIYA,
        loan_identity=VIKRAM,
    ),
    Segment(
        folder="4_arjun_manual_review",
        expected="MANUAL_REVIEW",
        notes="Credit 680 (borderline), variable monthly credits 72k/48k/95k → income_stability warns, 1 active loan. Routes to admin override queue.",
        kyc_identity=ARJUN,
    ),
    Segment(
        folder="5_neha_dti_too_high",
        expected="REJECTED (DTI / EMI burden)",
        notes="Income only 31.5k/month. Request a 6,00,000 loan in the UI to trip emi_burden + dti_ratio thresholds. Same fixtures + smaller request (e.g. 80,000) flips to APPROVED.",
        kyc_identity=NEHA,
    ),
]


def main() -> None:
    print(f"Generating segments under {OUT}")
    for seg in SEGMENTS:
        seg_dir = OUT / seg.folder
        kyc_dir = seg_dir / "kyc"
        loan_dir = seg_dir / "loan"

        write_set(kyc_dir, seg.kyc_identity)
        loan_who = seg.loan_identity if seg.loan_identity else seg.kyc_identity
        write_set(loan_dir, loan_who)

        readme = seg_dir / "README.md"
        readme.write_text(
            f"# Segment {seg.folder}\n\n"
            f"**Expected:** {seg.expected}\n\n"
            f"**Notes:** {seg.notes}\n\n"
            f"## KYC docs\n\n"
            f"From `kyc/`: aadhaar.pdf, pan.pdf\n\n"
            f"KYC identity: **{seg.kyc_identity.name}**, "
            f"PAN `{seg.kyc_identity.pan}`, "
            f"Aadhaar `{seg.kyc_identity.aadhaar}`\n\n"
            f"## Loan docs\n\n"
            f"From `loan/`: bank-statement-01..03.pdf, payslip-01..03.pdf, "
            f"employment-letter.pdf, itr.pdf, credit-report.pdf\n\n"
            f"Loan identity on docs: **{loan_who.name}**, PAN `{loan_who.pan}`\n",
            encoding="utf-8",
        )
        print(f"  {seg.folder}/  -> {seg.expected}")
    print("Done.")


if __name__ == "__main__":
    main()
