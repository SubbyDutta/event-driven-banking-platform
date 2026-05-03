"""Generate 5 scenario batches of realistic, multi-page, coloured KYC + loan PDFs.

Each batch lands under infra/fixtures/scenarios/<batch>/ with the full doc set:
  aadhaar.pdf (front + back, 2 pages, saffron/green)
  pan.pdf (Income Tax dept layout, blue)
  payslip-<ym>.pdf x3 (employer letterhead, earnings/deductions tables, signed)
  bank-statement-<ym>.pdf x3 (multi-page, transaction ledger, running balance)
  itr-form16.pdf (TRACES-style 2-page Form 16 / ITR ack)
  credit-report.pdf (CIBIL-style 2-page bureau report with score gauge)

Five scenarios:
  batch1_happy           -> APPROVED
  batch2_reject_initial  -> REJECTED (credit + income)
  batch3_resubmit_fixed  -> APPROVED (same user as batch2 with corrected docs)
  batch4_manual_review   -> MANUAL_REVIEW
  batch5_reject_full     -> REJECTED (credit + cross-doc name mismatch)

Usage: python infra/generate_scenarios.py
"""
from __future__ import annotations

import calendar
import random
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta
from pathlib import Path
from typing import Callable

from reportlab.lib import colors
from reportlab.lib.colors import HexColor
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import cm, mm
from reportlab.pdfgen import canvas
from reportlab.platypus import (
    BaseDocTemplate, Frame, PageBreak, PageTemplate, Paragraph, Spacer, Table,
    TableStyle,
)

OUT_ROOT = Path(__file__).parent / "fixtures" / "scenarios"

# --- Verhoeff (Aadhaar checksum) -----------------------------------------------
_V_D = [
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
_V_P = [
    [0, 1, 2, 3, 4, 5, 6, 7, 8, 9],
    [1, 5, 7, 6, 2, 8, 3, 0, 9, 4],
    [5, 8, 0, 3, 7, 9, 6, 1, 4, 2],
    [8, 9, 1, 6, 0, 4, 3, 5, 2, 7],
    [9, 4, 5, 3, 1, 2, 6, 8, 7, 0],
    [4, 2, 8, 6, 5, 7, 3, 9, 0, 1],
    [2, 7, 9, 3, 8, 0, 6, 4, 1, 5],
    [7, 0, 4, 6, 9, 1, 3, 2, 5, 8],
]
_V_INV = [0, 4, 3, 2, 1, 5, 6, 7, 8, 9]

def verhoeff_check_digit(eleven: str) -> str:
    c = 0
    for i, d in enumerate(reversed(eleven)):
        c = _V_D[c][_V_P[(i + 1) % 8][int(d)]]
    return str(_V_INV[c])

def make_aadhaar(seed: int) -> str:
    rng = random.Random(seed)
    first = rng.randint(2, 9)
    rest = [rng.randint(0, 9) for _ in range(10)]
    eleven = str(first) + "".join(str(d) for d in rest)
    return eleven + verhoeff_check_digit(eleven)


# --- Colour palette ------------------------------------------------------------
SAFFRON     = HexColor("#FF9933")
INDIA_GREEN = HexColor("#138808")
NAVY_BLUE   = HexColor("#0B2A5C")
INK         = HexColor("#101622")
SUBTLE      = HexColor("#5B6470")
LINE        = HexColor("#C7CDD8")
CARD_BG     = HexColor("#F5F2E8")
BANK_TEAL   = HexColor("#0F766E")
BANK_BG     = HexColor("#F0FDFA")
EARN_BG     = HexColor("#ECFDF5")
DEDUCT_BG   = HexColor("#FEF2F2")
CIBIL_RED   = HexColor("#B91C1C")
CIBIL_AMBER = HexColor("#D97706")
CIBIL_GREEN = HexColor("#15803D")
TRACES_BLUE = HexColor("#1E3A8A")

styles = getSampleStyleSheet()
H_TITLE = ParagraphStyle("H", parent=styles["Heading1"], fontName="Helvetica-Bold", fontSize=18, leading=22, textColor=INK)
H_SUB   = ParagraphStyle("HS", parent=styles["Heading2"], fontName="Helvetica-Bold", fontSize=12, leading=15, textColor=NAVY_BLUE)
P_NORM  = ParagraphStyle("N",  parent=styles["BodyText"], fontName="Helvetica", fontSize=10, leading=13, textColor=INK)
P_SMALL = ParagraphStyle("S",  parent=styles["BodyText"], fontName="Helvetica", fontSize=8,  leading=10, textColor=SUBTLE)
P_TINY  = ParagraphStyle("T",  parent=styles["BodyText"], fontName="Helvetica", fontSize=7,  leading=9,  textColor=SUBTLE)


# --- Data class ---------------------------------------------------------------
@dataclass
class Persona:
    batch: str
    outcome: str               # APPROVED / REJECTED / MANUAL_REVIEW
    name: str
    dob: str                   # YYYY-MM-DD
    pan: str
    aadhaar: str
    mobile: str
    email: str
    username: str
    employer: str
    employee_id: str
    designation: str
    joining_date: str
    address: str
    bank_name: str
    bank_branch: str
    ifsc: str
    account_number: str
    gross_salary: float
    deductions_pf: float
    deductions_tax: float
    deductions_pt: float
    existing_emi: float
    credit_score: int
    bureau: str
    bank_holder_override: str | None = None   # for cross-doc-mismatch scenario
    notes: list[str] = field(default_factory=list)

    @property
    def net_salary(self) -> float:
        return self.gross_salary - self.deductions_pf - self.deductions_tax - self.deductions_pt

    @property
    def annual_gross(self) -> float:
        return self.gross_salary * 12

    @property
    def first_name(self) -> str:
        return self.name.split()[0]

    @property
    def last_name(self) -> str:
        return self.name.split()[-1]


# --- Helpers ------------------------------------------------------------------
def fmt_inr(amount: float) -> str:
    n = int(round(amount))
    s = str(n)
    if len(s) <= 3:
        return f"Rs. {s}.00"
    head, tail = s[:-3], s[-3:]
    parts = []
    while len(head) > 2:
        parts.insert(0, head[-2:])
        head = head[:-2]
    if head:
        parts.insert(0, head)
    return f"Rs. {','.join(parts)},{tail}.00"

def last_n_months(n: int) -> list[date]:
    today = date.today()
    out: list[date] = []
    y, m = today.year, today.month
    for _ in range(n):
        m -= 1
        if m == 0:
            m = 12
            y -= 1
        out.append(date(y, m, 1))
    return list(reversed(out))


# --- Aadhaar ------------------------------------------------------------------
def render_aadhaar(p: Persona, out: Path) -> None:
    """2-page Aadhaar (front + back), saffron/green tricolour styling."""
    c = canvas.Canvas(str(out), pagesize=A4)
    w, h = A4

    def draw_tricolour_band(y: float) -> None:
        c.setFillColor(SAFFRON)
        c.rect(0, y, w, 6 * mm, fill=1, stroke=0)
        c.setFillColor(colors.white)
        c.rect(0, y - 6 * mm, w, 6 * mm, fill=1, stroke=0)
        c.setFillColor(INDIA_GREEN)
        c.rect(0, y - 12 * mm, w, 6 * mm, fill=1, stroke=0)

    # --- FRONT --------------------------------------------------------------
    draw_tricolour_band(h - 6 * mm)
    c.setFillColor(colors.white)
    c.setFont("Helvetica-Bold", 14)
    c.drawString(20 * mm, h - 4.5 * mm, "GOVERNMENT OF INDIA")
    c.setFillColor(INK)
    c.setFont("Helvetica", 10)
    c.drawRightString(w - 20 * mm, h - 4.5 * mm, "भारत सरकार")

    # Card panel
    card_x, card_y, card_w, card_h = 20 * mm, h - 110 * mm, w - 40 * mm, 80 * mm
    c.setFillColor(CARD_BG)
    c.roundRect(card_x, card_y, card_w, card_h, 4 * mm, fill=1, stroke=0)
    c.setStrokeColor(SAFFRON)
    c.setLineWidth(1.2)
    c.roundRect(card_x, card_y, card_w, card_h, 4 * mm, fill=0, stroke=1)

    # UIDAI logo placeholder (top-left of card)
    c.setFillColor(NAVY_BLUE)
    c.circle(card_x + 14 * mm, card_y + card_h - 14 * mm, 8 * mm, fill=1, stroke=0)
    c.setFillColor(colors.white)
    c.setFont("Helvetica-Bold", 7)
    c.drawCentredString(card_x + 14 * mm, card_y + card_h - 16 * mm, "UIDAI")

    c.setFillColor(INK)
    c.setFont("Helvetica-Bold", 13)
    c.drawString(card_x + 30 * mm, card_y + card_h - 12 * mm,
                 "UNIQUE IDENTIFICATION AUTHORITY OF INDIA")
    c.setFont("Helvetica", 9)
    c.setFillColor(SUBTLE)
    c.drawString(card_x + 30 * mm, card_y + card_h - 18 * mm,
                 "भारतीय विशिष्ट पहचान प्राधिकरण")

    # Photo placeholder
    photo_x, photo_y = card_x + 8 * mm, card_y + 14 * mm
    c.setFillColor(HexColor("#D7DAE0"))
    c.rect(photo_x, photo_y, 28 * mm, 36 * mm, fill=1, stroke=1)
    c.setFillColor(SUBTLE)
    c.setFont("Helvetica-Oblique", 7)
    c.drawCentredString(photo_x + 14 * mm, photo_y + 18 * mm, "[ photograph ]")

    # Details column
    details_x = photo_x + 32 * mm
    y = card_y + card_h - 30 * mm
    c.setFillColor(INK)
    c.setFont("Helvetica-Bold", 12)
    c.drawString(details_x, y, p.name)
    c.setFont("Helvetica", 9); c.setFillColor(SUBTLE)
    c.drawString(details_x, y - 5 * mm, "नाम / Name")

    y -= 14 * mm
    c.setFillColor(INK); c.setFont("Helvetica", 11)
    c.drawString(details_x, y, f"DOB: {p.dob}")
    c.setFont("Helvetica", 9); c.setFillColor(SUBTLE)
    c.drawString(details_x, y - 5 * mm, "जन्म तिथि / Date of Birth")

    y -= 14 * mm
    c.setFillColor(INK); c.setFont("Helvetica", 11)
    c.drawString(details_x, y, "MALE")
    c.setFont("Helvetica", 9); c.setFillColor(SUBTLE)
    c.drawString(details_x, y - 5 * mm, "पुरुष / Gender")

    # Big Aadhaar number
    aad_pretty = f"{p.aadhaar[0:4]} {p.aadhaar[4:8]} {p.aadhaar[8:12]}"
    c.setFillColor(INK); c.setFont("Helvetica-Bold", 22)
    c.drawCentredString(card_x + card_w / 2, card_y + 14 * mm, aad_pretty)
    c.setFont("Helvetica", 9); c.setFillColor(SUBTLE)
    c.drawCentredString(card_x + card_w / 2, card_y + 8 * mm,
                        "Aadhaar Number: " + aad_pretty)

    c.setFont("Helvetica-Oblique", 8); c.setFillColor(SAFFRON)
    c.drawCentredString(w / 2, card_y - 8 * mm, "मेरा आधार, मेरी पहचान  /  My Aadhaar, My Identity")

    # QR placeholder bottom-right
    c.setFillColor(INK)
    c.rect(w - 50 * mm, card_y - 30 * mm, 22 * mm, 22 * mm, fill=1, stroke=0)
    for i in range(11):
        for j in range(11):
            if (i * 7 + j * 13 + sum(int(d) for d in p.aadhaar)) % 3 == 0:
                c.setFillColor(colors.white)
                c.rect(w - 50 * mm + j * 2 * mm, card_y - 30 * mm + i * 2 * mm,
                       2 * mm, 2 * mm, fill=1, stroke=0)
    c.setFont("Helvetica", 7); c.setFillColor(SUBTLE)
    c.drawString(w - 50 * mm, card_y - 33 * mm, "Scan QR to verify")

    # Footer
    c.setStrokeColor(LINE); c.line(20 * mm, 25 * mm, w - 20 * mm, 25 * mm)
    c.setFont("Helvetica", 7); c.setFillColor(SUBTLE)
    c.drawString(20 * mm, 18 * mm, "Help: 1947  |  help@uidai.gov.in  |  www.uidai.gov.in")
    c.drawString(20 * mm, 14 * mm, "Issued By: Government of India")
    c.drawRightString(w - 20 * mm, 14 * mm, f"Print Date: {date.today().isoformat()}")
    c.showPage()

    # --- BACK ---------------------------------------------------------------
    draw_tricolour_band(h - 6 * mm)
    c.setFillColor(colors.white)
    c.setFont("Helvetica-Bold", 14)
    c.drawString(20 * mm, h - 4.5 * mm, "GOVERNMENT OF INDIA")

    panel_x, panel_y, panel_w, panel_h = 20 * mm, h - 130 * mm, w - 40 * mm, 110 * mm
    c.setFillColor(CARD_BG)
    c.roundRect(panel_x, panel_y, panel_w, panel_h, 4 * mm, fill=1, stroke=0)
    c.setStrokeColor(INDIA_GREEN); c.setLineWidth(1.0)
    c.roundRect(panel_x, panel_y, panel_w, panel_h, 4 * mm, fill=0, stroke=1)

    c.setFillColor(INK); c.setFont("Helvetica-Bold", 12)
    c.drawString(panel_x + 8 * mm, panel_y + panel_h - 12 * mm, "Address / पता")

    addr_lines = [p.address[i:i + 60] for i in range(0, len(p.address), 60)]
    c.setFont("Helvetica", 10); ay = panel_y + panel_h - 22 * mm
    for line in addr_lines:
        c.drawString(panel_x + 8 * mm, ay, line); ay -= 5 * mm

    # Aadhaar number repeated
    c.setFont("Helvetica-Bold", 16)
    c.drawString(panel_x + 8 * mm, panel_y + 14 * mm, f"Aadhaar Number: {aad_pretty}")
    c.setFont("Helvetica", 8); c.setFillColor(SUBTLE)
    c.drawString(panel_x + 8 * mm, panel_y + 8 * mm,
                 "VID generated on demand at https://resident.uidai.gov.in")

    # Watermark
    c.saveState()
    c.setFillColor(HexColor("#FBE7C8"))
    c.setFont("Helvetica-Bold", 60)
    c.translate(w / 2, h / 2); c.rotate(30)
    c.drawCentredString(0, 0, "AADHAAR")
    c.restoreState()

    c.setFont("Helvetica", 7); c.setFillColor(SUBTLE)
    c.drawString(20 * mm, 18 * mm, "This card is the property of UIDAI. If found, kindly post to UIDAI.")
    c.drawRightString(w - 20 * mm, 18 * mm, f"Issue Date: {date.today().isoformat()}")
    c.showPage()
    c.save()


# --- PAN ----------------------------------------------------------------------
def render_pan(p: Persona, out: Path) -> None:
    c = canvas.Canvas(str(out), pagesize=A4)
    w, h = A4

    # Top blue band
    c.setFillColor(NAVY_BLUE)
    c.rect(0, h - 24 * mm, w, 24 * mm, fill=1, stroke=0)

    # Left logo block
    c.setFillColor(colors.white)
    c.circle(20 * mm, h - 12 * mm, 7 * mm, fill=1, stroke=0)
    c.setFillColor(NAVY_BLUE); c.setFont("Helvetica-Bold", 8)
    c.drawCentredString(20 * mm, h - 14 * mm, "IT")

    c.setFillColor(colors.white); c.setFont("Helvetica-Bold", 16)
    c.drawString(32 * mm, h - 11 * mm, "INCOME TAX DEPARTMENT")
    c.setFont("Helvetica", 10)
    c.drawString(32 * mm, h - 17 * mm, "Government of India  |  आयकर विभाग")

    # PAN card panel (resembles real card)
    card_x, card_y, card_w, card_h = 25 * mm, h - 130 * mm, w - 50 * mm, 92 * mm
    c.setFillColor(HexColor("#FFFBEA"))
    c.roundRect(card_x, card_y, card_w, card_h, 5 * mm, fill=1, stroke=0)
    c.setStrokeColor(NAVY_BLUE); c.setLineWidth(1.5)
    c.roundRect(card_x, card_y, card_w, card_h, 5 * mm, fill=0, stroke=1)

    c.setFillColor(NAVY_BLUE); c.setFont("Helvetica-Bold", 13)
    c.drawString(card_x + 8 * mm, card_y + card_h - 11 * mm,
                 "PERMANENT ACCOUNT NUMBER CARD")
    c.setFont("Helvetica", 8); c.setFillColor(SUBTLE)
    c.drawString(card_x + 8 * mm, card_y + card_h - 16 * mm,
                 "स्थायी लेखा संख्या कार्ड")

    # Photo
    photo_x, photo_y = card_x + 8 * mm, card_y + 18 * mm
    c.setFillColor(HexColor("#D7DAE0"))
    c.rect(photo_x, photo_y, 28 * mm, 36 * mm, fill=1, stroke=1)
    c.setFillColor(SUBTLE); c.setFont("Helvetica-Oblique", 7)
    c.drawCentredString(photo_x + 14 * mm, photo_y + 18 * mm, "[ photograph ]")

    # Details
    dx = photo_x + 34 * mm
    y = card_y + card_h - 25 * mm
    c.setFillColor(INK); c.setFont("Helvetica-Bold", 12)
    c.drawString(dx, y, p.name)
    c.setFont("Helvetica", 8); c.setFillColor(SUBTLE)
    c.drawString(dx, y - 4 * mm, "Name / नाम")

    y -= 12 * mm
    c.setFillColor(INK); c.setFont("Helvetica", 11)
    c.drawString(dx, y, f"Father's Name: Ramesh {p.last_name}")
    c.setFont("Helvetica", 8); c.setFillColor(SUBTLE)
    c.drawString(dx, y - 4 * mm, "पिता का नाम")

    y -= 12 * mm
    c.setFillColor(INK); c.setFont("Helvetica", 11)
    c.drawString(dx, y, f"Date of Birth: {p.dob}")

    # Big PAN
    c.setFillColor(NAVY_BLUE); c.setFont("Helvetica-Bold", 22)
    c.drawCentredString(card_x + card_w / 2, card_y + 12 * mm, p.pan)
    c.setFont("Helvetica", 9); c.setFillColor(SUBTLE)
    c.drawCentredString(card_x + card_w / 2, card_y + 6 * mm, f"PAN: {p.pan}")

    # Signature swirl
    c.setStrokeColor(INK); c.setLineWidth(0.6)
    sig_path = c.beginPath()
    sig_path.moveTo(w - 70 * mm, card_y + 32 * mm)
    sig_path.curveTo(w - 60 * mm, card_y + 38 * mm,
                     w - 55 * mm, card_y + 28 * mm,
                     w - 45 * mm, card_y + 35 * mm)
    sig_path.curveTo(w - 40 * mm, card_y + 38 * mm,
                     w - 38 * mm, card_y + 28 * mm,
                     w - 35 * mm, card_y + 32 * mm)
    c.drawPath(sig_path)
    c.setFont("Helvetica-Oblique", 7); c.setFillColor(SUBTLE)
    c.drawString(w - 70 * mm, card_y + 26 * mm, "Cardholder signature")

    # Footer
    c.setStrokeColor(LINE); c.line(20 * mm, 30 * mm, w - 20 * mm, 30 * mm)
    c.setFont("Helvetica", 7); c.setFillColor(SUBTLE)
    c.drawString(20 * mm, 22 * mm, "Issued By: Income Tax Department, Govt. of India")
    c.drawRightString(w - 20 * mm, 22 * mm, "https://incometaxindia.gov.in")
    c.drawString(20 * mm, 16 * mm,
                 "This card is valid only when produced with a photo identity.")
    c.showPage()
    c.save()


# --- Payslip ------------------------------------------------------------------
def render_payslip(p: Persona, period: date, out: Path) -> None:
    c = canvas.Canvas(str(out), pagesize=A4)
    w, h = A4
    last_day = calendar.monthrange(period.year, period.month)[1]

    # Header band
    c.setFillColor(NAVY_BLUE)
    c.rect(0, h - 22 * mm, w, 22 * mm, fill=1, stroke=0)
    c.setFillColor(colors.white); c.setFont("Helvetica-Bold", 15)
    c.drawString(20 * mm, h - 11 * mm, p.employer)
    c.setFont("Helvetica", 9)
    c.drawString(20 * mm, h - 17 * mm,
                 "CIN: U72200KA2018PTC123456 | TAN: BLRA12345B | GST: 29AAACR1234R1ZP")

    # Doc title bar
    c.setFillColor(SAFFRON); c.rect(0, h - 30 * mm, w, 8 * mm, fill=1, stroke=0)
    c.setFillColor(colors.white); c.setFont("Helvetica-Bold", 12)
    c.drawCentredString(w / 2, h - 27 * mm,
                        f"PAYSLIP — {period.strftime('%B %Y').upper()}")

    # Employee info table
    info = [
        ["Name:", p.name, "Pay Month:", period.strftime("%B %Y")],
        ["Employee ID:", p.employee_id, "Pay Period:",
         f"{period.isoformat()} to {period.replace(day=last_day).isoformat()}"],
        ["Designation:", p.designation, "Date of Joining:", p.joining_date],
        ["PAN:", p.pan, "Bank A/C:", p.account_number],
        ["IFSC:", p.ifsc, "Mode:", "NEFT Credit"],
    ]
    info_table = Table(info, colWidths=[28 * mm, 60 * mm, 28 * mm, 54 * mm])
    info_table.setStyle(TableStyle([
        ("FONT", (0, 0), (-1, -1), "Helvetica", 9),
        ("FONT", (0, 0), (0, -1), "Helvetica-Bold", 9),
        ("FONT", (2, 0), (2, -1), "Helvetica-Bold", 9),
        ("TEXTCOLOR", (0, 0), (-1, -1), INK),
        ("BOX", (0, 0), (-1, -1), 0.5, LINE),
        ("INNERGRID", (0, 0), (-1, -1), 0.3, LINE),
        ("BACKGROUND", (0, 0), (-1, -1), HexColor("#F8FAFC")),
        ("LEFTPADDING", (0, 0), (-1, -1), 6),
        ("RIGHTPADDING", (0, 0), (-1, -1), 6),
        ("TOPPADDING", (0, 0), (-1, -1), 4),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 4),
    ]))
    info_table.wrapOn(c, w - 40 * mm, 60 * mm)
    info_table.drawOn(c, 20 * mm, h - 70 * mm)

    # Earnings + deductions side-by-side
    basic = round(p.gross_salary * 0.50)
    hra = round(p.gross_salary * 0.30)
    special = round(p.gross_salary * 0.15)
    convey = round(p.gross_salary * 0.05)
    earn_rows = [
        ["EARNINGS", "AMOUNT (INR)"],
        ["Basic Salary", fmt_inr(basic).replace("Rs. ", "")],
        ["House Rent Allowance", fmt_inr(hra).replace("Rs. ", "")],
        ["Special Allowance", fmt_inr(special).replace("Rs. ", "")],
        ["Conveyance Allowance", fmt_inr(convey).replace("Rs. ", "")],
        ["Total Earnings", fmt_inr(p.gross_salary).replace("Rs. ", "")],
    ]
    earn_table = Table(earn_rows, colWidths=[55 * mm, 30 * mm])
    earn_table.setStyle(TableStyle([
        ("FONT", (0, 0), (-1, 0), "Helvetica-Bold", 10),
        ("FONT", (0, 1), (-1, -1), "Helvetica", 9),
        ("FONT", (0, -1), (-1, -1), "Helvetica-Bold", 10),
        ("BACKGROUND", (0, 0), (-1, 0), INDIA_GREEN),
        ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
        ("BACKGROUND", (0, 1), (-1, -2), EARN_BG),
        ("BACKGROUND", (0, -1), (-1, -1), HexColor("#D1FAE5")),
        ("ALIGN", (1, 0), (1, -1), "RIGHT"),
        ("BOX", (0, 0), (-1, -1), 0.5, INDIA_GREEN),
        ("INNERGRID", (0, 0), (-1, -1), 0.3, LINE),
        ("LEFTPADDING", (0, 0), (-1, -1), 6),
        ("RIGHTPADDING", (0, 0), (-1, -1), 6),
        ("TOPPADDING", (0, 0), (-1, -1), 4),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 4),
    ]))

    total_ded = p.deductions_pf + p.deductions_tax + p.deductions_pt
    ded_rows = [
        ["DEDUCTIONS", "AMOUNT (INR)"],
        ["Provident Fund (PF)", fmt_inr(p.deductions_pf).replace("Rs. ", "")],
        ["Income Tax (TDS)", fmt_inr(p.deductions_tax).replace("Rs. ", "")],
        ["Professional Tax", fmt_inr(p.deductions_pt).replace("Rs. ", "")],
        ["Insurance Premium", "0.00"],
        ["Total Deductions", fmt_inr(total_ded).replace("Rs. ", "")],
    ]
    ded_table = Table(ded_rows, colWidths=[55 * mm, 30 * mm])
    ded_table.setStyle(TableStyle([
        ("FONT", (0, 0), (-1, 0), "Helvetica-Bold", 10),
        ("FONT", (0, 1), (-1, -1), "Helvetica", 9),
        ("FONT", (0, -1), (-1, -1), "Helvetica-Bold", 10),
        ("BACKGROUND", (0, 0), (-1, 0), CIBIL_RED),
        ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
        ("BACKGROUND", (0, 1), (-1, -2), DEDUCT_BG),
        ("BACKGROUND", (0, -1), (-1, -1), HexColor("#FECACA")),
        ("ALIGN", (1, 0), (1, -1), "RIGHT"),
        ("BOX", (0, 0), (-1, -1), 0.5, CIBIL_RED),
        ("INNERGRID", (0, 0), (-1, -1), 0.3, LINE),
        ("LEFTPADDING", (0, 0), (-1, -1), 6),
        ("RIGHTPADDING", (0, 0), (-1, -1), 6),
        ("TOPPADDING", (0, 0), (-1, -1), 4),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 4),
    ]))

    earn_table.wrapOn(c, 90 * mm, 60 * mm)
    earn_table.drawOn(c, 20 * mm, h - 130 * mm)
    ded_table.wrapOn(c, 90 * mm, 60 * mm)
    ded_table.drawOn(c, 110 * mm, h - 130 * mm)

    # Big "Net Pay" callout box
    c.setFillColor(NAVY_BLUE)
    c.roundRect(20 * mm, h - 165 * mm, w - 40 * mm, 24 * mm, 3 * mm, fill=1, stroke=0)
    c.setFillColor(colors.white)
    c.setFont("Helvetica-Bold", 12)
    c.drawString(28 * mm, h - 152 * mm, "Gross Pay:")
    c.drawString(28 * mm, h - 160 * mm, "Net Pay:")
    c.setFont("Helvetica-Bold", 14)
    c.drawRightString(w - 28 * mm, h - 152 * mm, fmt_inr(p.gross_salary))
    c.setFont("Helvetica-Bold", 18)
    c.drawRightString(w - 28 * mm, h - 161 * mm, fmt_inr(p.net_salary))

    # Inline labels for the extractor regex (single-token labels)
    c.setFillColor(INK)
    c.setFont("Helvetica", 9)
    label_y = h - 178 * mm
    c.drawString(20 * mm, label_y,        f"Gross Salary: {fmt_inr(p.gross_salary)}")
    c.drawString(20 * mm, label_y - 5 * mm, f"Net Salary: {fmt_inr(p.net_salary)}")
    c.drawString(20 * mm, label_y - 10 * mm, f"Total Deductions: {fmt_inr(total_ded)}")
    if p.existing_emi > 0:
        c.drawString(20 * mm, label_y - 15 * mm, f"Existing EMI: {fmt_inr(p.existing_emi)}")

    c.setFont("Helvetica-Oblique", 9); c.setFillColor(SUBTLE)
    c.drawString(20 * mm, label_y - 24 * mm,
                 "Amount in words: Indian Rupees " + _amount_in_words(p.net_salary) + " only")

    # Signature block
    c.setStrokeColor(INK); c.setLineWidth(0.4)
    c.line(w - 75 * mm, 60 * mm, w - 25 * mm, 60 * mm)
    c.setFont("Helvetica", 9); c.setFillColor(INK)
    c.drawCentredString(w - 50 * mm, 55 * mm, "Authorised Signatory")
    c.setFont("Helvetica-Oblique", 8); c.setFillColor(SUBTLE)
    c.drawCentredString(w - 50 * mm, 51 * mm, "Head of Payroll & Compensation")

    # Footer
    c.setStrokeColor(LINE); c.line(20 * mm, 30 * mm, w - 20 * mm, 30 * mm)
    c.setFont("Helvetica", 7); c.setFillColor(SUBTLE)
    c.drawString(20 * mm, 24 * mm,
                 "This is a system-generated payslip and does not require a physical signature.")
    c.drawString(20 * mm, 19 * mm,
                 f"Registered Office: 12 Outer Ring Road, Bengaluru 560103, India")
    c.drawRightString(w - 20 * mm, 24 * mm,
                      f"Generated: {date.today().isoformat()}  |  Page 1 of 1")
    c.showPage()
    c.save()


def _amount_in_words(n: float) -> str:
    """Cheap rupee-to-words for the cosmetics line. Doesn't need to be perfect."""
    n = int(round(n))
    if n < 1000:
        return f"{n} rupees"
    if n < 100000:
        return f"{n // 1000} thousand {n % 1000} rupees"
    lakhs = n // 100000
    rem = n % 100000
    return f"{lakhs} lakh {rem // 1000} thousand"


# --- Bank statement -----------------------------------------------------------
def render_bank_statement(p: Persona, period: date, out: Path) -> None:
    """Multi-page bank statement (typically 2 pages) with realistic ledger."""
    holder_name = p.bank_holder_override or p.name
    last_day = calendar.monthrange(period.year, period.month)[1]
    period_start = period.isoformat()
    period_end = period.replace(day=last_day).isoformat()
    salary_credit = p.net_salary
    opening = round(50000 + (period.month % 3) * 8000, 2)

    txns: list[tuple[str, str, str, str, float, float, float]] = []
    bal = opening
    rng = random.Random(period.toordinal() + len(p.name))
    salary_day = 28
    narrations_debit = [
        "UPI/PAYTM-MERCHANT/BIGBASKET", "POS/SWIGGY/FOODORDER",
        "NEFT/AMAZONIN-RETAIL/ORDER", "ATM-WDL/SBIATM-MGRD/CASH",
        "BIL-PAY/AIRTEL-MOBILE", "BIL-PAY/BESCOM-ELEC",
        "UPI/UBER-RIDES/COMMUTE", "POS/RELIANCE-FRESH",
        "UPI/RAPIDO/RIDE-FARE", "NEFT/HDFC-LIFE-INS",
    ]
    narrations_credit = [
        "NEFT/REFUND-AMAZON", "UPI/PAYTM-CASHBACK",
        "INT-CR/SAV-INTEREST", "IMPS/FRIEND-TRF",
    ]

    days = list(range(1, last_day + 1))
    rng.shuffle(days)
    days_chosen = sorted(days[:18])
    salary_in = False

    for day in days_chosen:
        d = date(period.year, period.month, day).isoformat()
        if day >= salary_day and not salary_in:
            txns.append((d, "NEFT/SALARY-CR/" + p.employer.split()[0].upper(),
                         f"SAL-{period.strftime('%b%y').upper()}", "CR",
                         salary_credit, 0.0, bal + salary_credit))
            bal += salary_credit
            salary_in = True
            if p.existing_emi > 0:
                emi_d = date(period.year, period.month, min(salary_day + 2, last_day)).isoformat()
                txns.append((emi_d, "ECS-DR/HDFC-PERSONAL-LOAN-EMI", "EMI-AUTO-DEBIT", "DR",
                             0.0, p.existing_emi, bal - p.existing_emi))
                bal -= p.existing_emi
                continue
        if rng.random() < 0.18:
            amt = round(rng.uniform(200, 4500), 2)
            txns.append((d, rng.choice(narrations_credit), f"CR{day:02d}", "CR",
                         amt, 0.0, bal + amt))
            bal += amt
        else:
            amt = round(rng.uniform(150, 6000), 2)
            txns.append((d, rng.choice(narrations_debit), f"DR{day:02d}", "DR",
                         0.0, amt, bal - amt))
            bal -= amt

    if not salary_in:
        d = date(period.year, period.month, salary_day).isoformat()
        txns.append((d, "NEFT/SALARY-CR/" + p.employer.split()[0].upper(),
                     f"SAL-{period.strftime('%b%y').upper()}", "CR",
                     salary_credit, 0.0, bal + salary_credit))
        bal += salary_credit
        if p.existing_emi > 0:
            txns.append((d, "ECS-DR/HDFC-PERSONAL-LOAN-EMI", "EMI-AUTO-DEBIT", "DR",
                         0.0, p.existing_emi, bal - p.existing_emi))
            bal -= p.existing_emi

    txns.sort(key=lambda r: r[0])
    closing = txns[-1][6] if txns else opening
    total_credits = sum(r[4] for r in txns)
    total_debits = sum(r[5] for r in txns)

    doc = BaseDocTemplate(str(out), pagesize=A4,
                          leftMargin=15 * mm, rightMargin=15 * mm,
                          topMargin=24 * mm, bottomMargin=22 * mm)

    def page_decor(canv, _doc):
        # Header
        canv.setFillColor(BANK_TEAL)
        canv.rect(0, A4[1] - 18 * mm, A4[0], 18 * mm, fill=1, stroke=0)
        canv.setFillColor(colors.white); canv.setFont("Helvetica-Bold", 14)
        canv.drawString(15 * mm, A4[1] - 11 * mm, p.bank_name.upper())
        canv.setFont("Helvetica", 9)
        canv.drawString(15 * mm, A4[1] - 16 * mm,
                        f"{p.bank_branch} Branch  |  IFSC: {p.ifsc}  |  customercare@{p.bank_name.lower().replace(' ', '')}.in")
        canv.setFillColor(colors.white); canv.setFont("Helvetica-Bold", 10)
        canv.drawRightString(A4[0] - 15 * mm, A4[1] - 11 * mm,
                             f"Statement Period: {period_start} to {period_end}")
        canv.drawRightString(A4[0] - 15 * mm, A4[1] - 16 * mm,
                             f"Statement Month: {period.strftime('%B %Y')}")
        # Footer
        canv.setStrokeColor(LINE); canv.line(15 * mm, 18 * mm, A4[0] - 15 * mm, 18 * mm)
        canv.setFont("Helvetica", 7); canv.setFillColor(SUBTLE)
        canv.drawString(15 * mm, 13 * mm,
                        f"{p.bank_name} is a scheduled commercial bank. RBI Lic: 1234. CIN: L65190WB2005PLC102345.")
        canv.drawString(15 * mm, 9 * mm,
                        "Statement generated electronically. No signature required.")
        canv.drawRightString(A4[0] - 15 * mm, 13 * mm,
                             f"Page {canv.getPageNumber()}")
        canv.drawRightString(A4[0] - 15 * mm, 9 * mm,
                             f"Generated: {date.today().isoformat()}")

    frame = Frame(15 * mm, 22 * mm, A4[0] - 30 * mm, A4[1] - 46 * mm,
                  showBoundary=0, leftPadding=0, rightPadding=0,
                  topPadding=0, bottomPadding=0)
    doc.addPageTemplates([PageTemplate(id="main", frames=[frame], onPage=page_decor)])

    flow: list = []
    flow.append(Paragraph("STATEMENT OF ACCOUNT", H_TITLE))
    flow.append(Spacer(1, 4 * mm))

    # Inline single-line summary block — placed before any Tables so pdfminer
    # / Document AI text extraction sees `Label: Value` adjacency (Tables get
    # serialised column-by-column and break the regex's `[:\s]*` matcher).
    inline_top = (
        f"Account Holder: {holder_name}<br/>"
        f"Account Number: {p.account_number}<br/>"
        f"IFSC: {p.ifsc}<br/>"
        f"Branch: {p.bank_branch}<br/>"
        f"Statement Period: {period_start} to {period_end}<br/>"
        f"Statement Month: {period.strftime('%B %Y')}<br/>"
        f"Opening Balance: {fmt_inr(opening)}<br/>"
        f"Total Credits: {fmt_inr(total_credits)}<br/>"
        f"Total Debits: {fmt_inr(total_debits)}<br/>"
        f"Monthly Credits: {fmt_inr(salary_credit)}<br/>"
        f"Closing Balance: {fmt_inr(closing)}"
    )
    flow.append(Paragraph(inline_top, P_NORM))
    flow.append(Spacer(1, 5 * mm))

    summary_rows = [
        ["Account Holder:",      holder_name,
         "Statement Period:",    f"{period_start} to {period_end}"],
        ["Account Number:",      p.account_number,
         "Statement Month:",     period.strftime("%B %Y")],
        ["IFSC:",                p.ifsc,
         "Currency:",            "INR"],
        ["Branch:",              p.bank_branch,
         "Statement Type:",      "Savings A/C - Detailed"],
    ]
    summary = Table(summary_rows, colWidths=[34 * mm, 56 * mm, 34 * mm, 56 * mm])
    summary.setStyle(TableStyle([
        ("FONT", (0, 0), (-1, -1), "Helvetica", 9),
        ("FONT", (0, 0), (0, -1), "Helvetica-Bold", 9),
        ("FONT", (2, 0), (2, -1), "Helvetica-Bold", 9),
        ("BOX", (0, 0), (-1, -1), 0.5, LINE),
        ("INNERGRID", (0, 0), (-1, -1), 0.3, LINE),
        ("BACKGROUND", (0, 0), (-1, -1), BANK_BG),
        ("LEFTPADDING", (0, 0), (-1, -1), 6),
        ("RIGHTPADDING", (0, 0), (-1, -1), 6),
        ("TOPPADDING", (0, 0), (-1, -1), 4),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 4),
    ]))
    flow.append(summary)
    flow.append(Spacer(1, 5 * mm))

    # Visual snapshot table — uses non-regex-conflicting labels so the
    # column-major pdfminer dump of the table doesn't capture a stale
    # "Closing Balance: Rs. <opening>" pairing. The canonical KV block above
    # is what the extractor actually reads.
    bal_rows = [
        ["Period Start Bal.", fmt_inr(opening)],
        ["Inflow Total",      fmt_inr(total_credits)],
        ["Outflow Total",     fmt_inr(total_debits)],
        ["Salary Inflow",     fmt_inr(salary_credit)],
        ["Period End Bal.",   fmt_inr(closing)],
    ]
    bal_table = Table(bal_rows, colWidths=[60 * mm, 50 * mm])
    bal_table.setStyle(TableStyle([
        ("FONT", (0, 0), (-1, -1), "Helvetica-Bold", 10),
        ("BOX", (0, 0), (-1, -1), 0.6, BANK_TEAL),
        ("INNERGRID", (0, 0), (-1, -1), 0.3, LINE),
        ("BACKGROUND", (0, 0), (-1, -1), BANK_BG),
        ("ALIGN", (1, 0), (1, -1), "RIGHT"),
        ("LEFTPADDING", (0, 0), (-1, -1), 8),
        ("RIGHTPADDING", (0, 0), (-1, -1), 8),
        ("TOPPADDING", (0, 0), (-1, -1), 5),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
    ]))
    flow.append(bal_table)
    flow.append(Spacer(1, 6 * mm))
    flow.append(Paragraph("Transaction Ledger", H_SUB))
    flow.append(Spacer(1, 2 * mm))

    rows: list[list[str]] = [["Date", "Narration", "Ref/Cheque", "Type", "Debit", "Credit", "Balance"]]
    for d, narr, ref, typ, cr, dr, bb in txns:
        rows.append([d, narr, ref, typ,
                     fmt_inr(dr).replace("Rs. ", "") if dr else "",
                     fmt_inr(cr).replace("Rs. ", "") if cr else "",
                     fmt_inr(bb).replace("Rs. ", "")])

    txn_table = Table(rows, colWidths=[20 * mm, 60 * mm, 24 * mm, 12 * mm, 22 * mm, 22 * mm, 24 * mm], repeatRows=1)
    style_cmds = [
        ("FONT", (0, 0), (-1, 0), "Helvetica-Bold", 9),
        ("FONT", (0, 1), (-1, -1), "Helvetica", 8),
        ("BACKGROUND", (0, 0), (-1, 0), BANK_TEAL),
        ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
        ("ALIGN", (4, 1), (6, -1), "RIGHT"),
        ("ALIGN", (3, 1), (3, -1), "CENTER"),
        ("BOX", (0, 0), (-1, -1), 0.4, LINE),
        ("INNERGRID", (0, 0), (-1, -1), 0.2, LINE),
        ("LEFTPADDING", (0, 0), (-1, -1), 4),
        ("RIGHTPADDING", (0, 0), (-1, -1), 4),
        ("TOPPADDING", (0, 0), (-1, -1), 3),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 3),
    ]
    for i in range(1, len(rows)):
        if i % 2 == 0:
            style_cmds.append(("BACKGROUND", (0, i), (-1, i), HexColor("#F8FAFC")))
    txn_table.setStyle(TableStyle(style_cmds))
    flow.append(txn_table)
    flow.append(Spacer(1, 4 * mm))

    legend = (
        "<b>Legend</b>: NEFT - National Electronic Funds Transfer  |  "
        "IMPS - Immediate Payment Service  |  ECS - Electronic Clearing Service  |  "
        "POS - Point Of Sale  |  UPI - Unified Payments Interface."
    )
    flow.append(Paragraph(legend, P_TINY))

    doc.build(flow)


# --- Employment letter --------------------------------------------------------
def render_employment_letter(p: Persona, out: Path) -> None:
    """Single-page letter on company letterhead — confirms employment, salary,
    joining date, and is dated within the last 30 days so the recency check
    (180-day window) passes."""
    c = canvas.Canvas(str(out), pagesize=A4)
    w, h = A4
    letter_date = (date.today() - timedelta(days=14)).isoformat()

    # Letterhead band — top corporate colour bar
    c.setFillColor(NAVY_BLUE)
    c.rect(0, h - 26 * mm, w, 26 * mm, fill=1, stroke=0)
    c.setFillColor(SAFFRON)
    c.rect(0, h - 28 * mm, w, 2 * mm, fill=1, stroke=0)

    # Logo placeholder (initials in a circle)
    initials = "".join(s[0] for s in p.employer.split()[:2]).upper()
    c.setFillColor(colors.white); c.circle(20 * mm, h - 13 * mm, 7 * mm, fill=1, stroke=0)
    c.setFillColor(NAVY_BLUE); c.setFont("Helvetica-Bold", 9)
    c.drawCentredString(20 * mm, h - 15 * mm, initials)

    c.setFillColor(colors.white); c.setFont("Helvetica-Bold", 16)
    c.drawString(32 * mm, h - 11 * mm, p.employer.upper())
    c.setFont("Helvetica", 9)
    c.drawString(32 * mm, h - 17 * mm,
                 "12 Outer Ring Road, Bengaluru 560103, Karnataka, India")
    c.drawString(32 * mm, h - 22 * mm,
                 f"CIN: U72200KA2018PTC123456  |  www.{p.employer.split()[0].lower()}.com  |  hr@{p.employer.split()[0].lower()}.com")

    # Letter date — first date on the page so extract_first_date catches it.
    c.setFillColor(INK); c.setFont("Helvetica-Bold", 10)
    c.drawRightString(w - 20 * mm, h - 38 * mm, f"Letter Date: {letter_date}")
    c.setFont("Helvetica", 9); c.setFillColor(SUBTLE)
    c.drawRightString(w - 20 * mm, h - 43 * mm, f"Ref: HR/EMP/{p.employee_id}/{date.today().year}")

    # Recipient block
    c.setFillColor(INK); c.setFont("Helvetica", 10)
    y = h - 55 * mm
    c.drawString(20 * mm, y, "To,")
    y -= 5 * mm; c.setFont("Helvetica-Bold", 11)
    c.drawString(20 * mm, y, p.name)
    y -= 5 * mm; c.setFont("Helvetica", 10)
    addr_lines = [p.address[i:i + 70] for i in range(0, len(p.address), 70)]
    for line in addr_lines:
        c.drawString(20 * mm, y, line); y -= 5 * mm

    # Subject line
    y -= 4 * mm
    c.setFont("Helvetica-Bold", 11)
    c.drawString(20 * mm, y,
                 "Subject: Letter of Employment & Salary Confirmation")
    y -= 8 * mm

    # Salutation + body — narrative form, with key facts repeated as inline KV
    # lines further down so the regex extractor latches cleanly.
    c.setFont("Helvetica", 10)
    c.drawString(20 * mm, y, "Dear Sir/Madam,")
    y -= 8 * mm

    # Body avoids the literal word "employee" because NAME_LINE_RE in
    # findoc-verify's extractor includes `employee(?:\s+name)?` — when a soft
    # wrap puts "employee" at the start of a line, the regex captures the
    # following 2-80 chars as a name candidate. Use "associate" / "individual"
    # in the prose; the inline KV block below carries the regex-friendly Name.
    body = (
        f"This is to certify that <b>{p.name}</b> is currently working with "
        f"<b>{p.employer}</b> as a <b>{p.designation}</b> since "
        f"<b>{p.joining_date}</b>. The associate is on the rolls of the company "
        f"on a permanent basis and is in good standing. As per the latest "
        f"compensation review, the gross monthly salary is "
        f"<b>{fmt_inr(p.gross_salary)}</b>, translating to an annual cost-to-company "
        f"of <b>{fmt_inr(p.annual_gross)}</b>. This letter is being issued at the "
        f"request of the individual for the purpose of loan / financial verification."
    )
    para = Paragraph(body, P_NORM)
    para.wrapOn(c, w - 40 * mm, 60 * mm)
    para.drawOn(c, 20 * mm, y - 36 * mm)
    y -= 44 * mm

    # Inline KV block — required for regex extractors
    c.setFont("Helvetica", 10); c.setFillColor(INK)
    # Address last — ADDRESS_LINE_RE allows alphanumerics and continues across
    # whitespace, so anything appearing on the next visual line gets concatenated
    # into the captured value. Putting PAN before Address keeps the address
    # capture clean.
    inline = [
        f"Name: {p.name}",
        f"Employer: {p.employer}",
        f"Company Name: {p.employer}",
        f"Designation: {p.designation}",
        f"Date of Joining: {p.joining_date}",
        f"Annual CTC: {fmt_inr(p.annual_gross)}",
        f"Gross Salary: {fmt_inr(p.gross_salary)}",
        f"PAN: {p.pan}",
        f"Address: {p.address}",
    ]
    for line in inline:
        c.drawString(20 * mm, y, line); y -= 5 * mm

    # Signature block
    y -= 8 * mm
    c.setFont("Helvetica", 10)
    c.drawString(20 * mm, y, "For and on behalf of " + p.employer + ",")
    y -= 22 * mm

    c.setStrokeColor(INK); c.setLineWidth(0.6)
    sig_path = c.beginPath()
    sig_path.moveTo(20 * mm, y + 2 * mm)
    sig_path.curveTo(28 * mm, y + 8 * mm, 35 * mm, y - 4 * mm,
                     45 * mm, y + 4 * mm)
    sig_path.curveTo(50 * mm, y + 6 * mm, 53 * mm, y - 2 * mm,
                     58 * mm, y + 2 * mm)
    c.drawPath(sig_path)
    c.setStrokeColor(LINE); c.line(20 * mm, y - 4 * mm, 80 * mm, y - 4 * mm)
    c.setFont("Helvetica-Bold", 10); c.setFillColor(INK)
    c.drawString(20 * mm, y - 9 * mm, "Priya Krishnan")
    c.setFont("Helvetica", 9); c.setFillColor(SUBTLE)
    c.drawString(20 * mm, y - 13 * mm, "Head — Human Resources")
    c.drawString(20 * mm, y - 17 * mm, p.employer)

    # Company seal placeholder (top-right of signature)
    c.setStrokeColor(INDIA_GREEN); c.setLineWidth(1.2)
    c.circle(w - 45 * mm, y + 3 * mm, 14 * mm, fill=0, stroke=1)
    c.circle(w - 45 * mm, y + 3 * mm, 11 * mm, fill=0, stroke=1)
    c.setFillColor(INDIA_GREEN); c.setFont("Helvetica-Bold", 6)
    c.drawCentredString(w - 45 * mm, y + 6 * mm, p.employer.split()[0].upper())
    c.drawCentredString(w - 45 * mm, y + 1 * mm, "HR DEPARTMENT")
    c.drawCentredString(w - 45 * mm, y - 4 * mm, "OFFICIAL SEAL")

    # Footer
    c.setStrokeColor(LINE); c.line(20 * mm, 24 * mm, w - 20 * mm, 24 * mm)
    c.setFont("Helvetica", 7); c.setFillColor(SUBTLE)
    c.drawString(20 * mm, 18 * mm,
                 f"Registered Office: 12 Outer Ring Road, Bengaluru 560103, Karnataka  |  "
                 f"Tel: +91 80 4567 8900")
    c.drawString(20 * mm, 14 * mm,
                 "This letter is being issued for verification purposes only and is valid for 6 months from the date of issue.")
    c.drawRightString(w - 20 * mm, 18 * mm,
                      f"Generated: {date.today().isoformat()}  |  Page 1 of 1")
    c.showPage()
    c.save()


# --- ITR / Form 16 ------------------------------------------------------------
def render_itr(p: Persona, out: Path, ay_year: int) -> None:
    """2-page Form 16 + ITR acknowledgement style document."""
    c = canvas.Canvas(str(out), pagesize=A4)
    w, h = A4

    # Page 1 — Form 16 Part A
    c.setFillColor(TRACES_BLUE); c.rect(0, h - 22 * mm, w, 22 * mm, fill=1, stroke=0)
    c.setFillColor(colors.white); c.setFont("Helvetica-Bold", 14)
    c.drawString(15 * mm, h - 11 * mm, "TRACES")
    c.setFont("Helvetica", 9)
    c.drawString(15 * mm, h - 17 * mm,
                 "TDS Reconciliation Analysis and Correction Enabling System  |  Income Tax Department")
    c.setFont("Helvetica-Bold", 11)
    c.drawRightString(w - 15 * mm, h - 11 * mm, "FORM No. 16 — Part A")
    c.setFont("Helvetica", 9)
    c.drawRightString(w - 15 * mm, h - 17 * mm, f"Assessment Year: {ay_year}-{ay_year + 1}")

    c.setFillColor(INK); c.setFont("Helvetica-Bold", 13)
    c.drawCentredString(w / 2, h - 32 * mm,
                        "Certificate Under Section 203 of the Income-Tax Act, 1961")
    c.setFont("Helvetica", 9); c.setFillColor(SUBTLE)
    c.drawCentredString(w / 2, h - 38 * mm,
                        "for tax deducted at source on salary paid to an employee")

    # Inline canonical key-value block — must appear BEFORE any Tables so the
    # regex extractor latches onto these adjacencies and not the column-major
    # serialisation pdfminer produces from Tables (which would otherwise pair
    # "Date of Birth:" with the value of the *next* table column).
    annual_gross_pre = p.gross_salary * 12
    annual_pf_pre    = p.deductions_pf * 12
    annual_pt_pre    = p.deductions_pt * 12
    annual_tds_pre   = p.deductions_tax * 12
    taxable_pre      = max(0, annual_gross_pre - 50000 - annual_pf_pre)

    c.setFont("Helvetica", 10); c.setFillColor(INK)
    base_y = h - 50 * mm
    inline_lines = [
        f"Name: {p.name}",
        f"PAN: {p.pan}",
        f"Date of Birth: {p.dob}",
        f"Assessment Year: {ay_year}-{(ay_year + 1) % 100:02d}",
        f"Gross Total Income: {fmt_inr(annual_gross_pre)}",
        f"Total Taxable Income: {fmt_inr(taxable_pre)}",
        f"Total Tax Paid: {fmt_inr(annual_tds_pre)}",
    ]
    for i, line in enumerate(inline_lines):
        c.drawString(15 * mm, base_y - i * 5 * mm, line)

    # Deductor / deductee blocks (use non-conflicting table labels: "Employee
    # DOB" instead of "Date of Birth", "Tax Year (AY)" instead of "Assessment
    # Year" — keeps the visual document realistic without re-triggering the
    # extractor regex on the table cells).
    rows1 = [
        ["Name and Address of the Employer (Deductor):",
         f"{p.employer}, 12 Outer Ring Road, Bengaluru 560103, KA"],
        ["PAN of the Deductor:", "AABCR1234R"],
        ["TAN of the Deductor:", "BLRA12345B"],
        ["CIT (TDS) Address:",  "Office of CIT (TDS), Bengaluru"],
        ["Tax Year (AY):",      f"{ay_year}-{(ay_year + 1) % 100:02d}"],
        ["Period with Employer:", f"01-04-{ay_year - 1} to 31-03-{ay_year}"],
    ]
    rows2 = [
        ["Name of Employee (Deductee):", p.name],
        ["Permanent Account No.:", p.pan],
        ["Employee DOB:", p.dob],
        ["Designation:", p.designation],
        ["Date of Joining:", p.joining_date],
    ]

    def make_kv(rows: list[list[str]], header_color) -> Table:
        t = Table(rows, colWidths=[68 * mm, 110 * mm])
        t.setStyle(TableStyle([
            ("FONT", (0, 0), (-1, -1), "Helvetica", 9),
            ("FONT", (0, 0), (0, -1), "Helvetica-Bold", 9),
            ("BOX", (0, 0), (-1, -1), 0.5, header_color),
            ("INNERGRID", (0, 0), (-1, -1), 0.3, LINE),
            ("BACKGROUND", (0, 0), (0, -1), HexColor("#EFF6FF")),
            ("LEFTPADDING", (0, 0), (-1, -1), 6),
            ("RIGHTPADDING", (0, 0), (-1, -1), 6),
            ("TOPPADDING", (0, 0), (-1, -1), 4),
            ("BOTTOMPADDING", (0, 0), (-1, -1), 4),
        ]))
        return t

    t1 = make_kv(rows1, TRACES_BLUE); t1.wrapOn(c, w, 70 * mm); t1.drawOn(c, 15 * mm, h - 100 * mm)
    t2 = make_kv(rows2, TRACES_BLUE); t2.wrapOn(c, w, 60 * mm); t2.drawOn(c, 15 * mm, h - 158 * mm)

    # Salary computation table (Part B preview)
    annual_gross = p.gross_salary * 12
    annual_pf    = p.deductions_pf * 12
    annual_pt    = p.deductions_pt * 12
    annual_tds   = p.deductions_tax * 12
    taxable      = annual_gross - 50000 - annual_pf
    if taxable < 0: taxable = 0

    # Use non-conflicting labels for the visual table — pdfminer serialises
    # column 1 entirely before column 2, so a row label "Total Taxable Income"
    # would steal the regex match against the *next* line's number ("7." in the
    # original layout). Cosmetics-only relabel.
    sal_rows = [
        ["Item", "Amount (INR)"],
        ["1. Salary (basic + allowances)", fmt_inr(annual_gross).replace("Rs. ", "")],
        ["2. Less: Standard Deduction u/s 16(ia)", "50,000.00"],
        ["3. Less: Professional Tax", fmt_inr(annual_pt).replace("Rs. ", "")],
        ["4. Salary Chargeable", fmt_inr(annual_gross - 50000 - annual_pt).replace("Rs. ", "")],
        ["5. Less: Chapter VI-A (PF etc.)", fmt_inr(annual_pf).replace("Rs. ", "")],
        ["6. Net Taxable Salary", fmt_inr(taxable).replace("Rs. ", "")],
        ["7. TDS Deducted", fmt_inr(annual_tds).replace("Rs. ", "")],
    ]
    sal_t = Table(sal_rows, colWidths=[110 * mm, 60 * mm])
    sal_t.setStyle(TableStyle([
        ("FONT", (0, 0), (-1, 0), "Helvetica-Bold", 10),
        ("FONT", (0, 1), (-1, -1), "Helvetica", 9),
        ("BACKGROUND", (0, 0), (-1, 0), TRACES_BLUE),
        ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
        ("BACKGROUND", (0, -1), (-1, -1), HexColor("#DBEAFE")),
        ("FONT", (0, -1), (-1, -1), "Helvetica-Bold", 10),
        ("ALIGN", (1, 0), (1, -1), "RIGHT"),
        ("BOX", (0, 0), (-1, -1), 0.5, TRACES_BLUE),
        ("INNERGRID", (0, 0), (-1, -1), 0.3, LINE),
        ("LEFTPADDING", (0, 0), (-1, -1), 6),
        ("RIGHTPADDING", (0, 0), (-1, -1), 6),
        ("TOPPADDING", (0, 0), (-1, -1), 4),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 4),
    ]))
    sal_t.wrapOn(c, w, 60 * mm); sal_t.drawOn(c, 15 * mm, 60 * mm)

    # Page footer + watermark
    c.saveState()
    c.setFillColor(HexColor("#DBEAFE"))
    c.setFont("Helvetica-Bold", 70)
    c.translate(w / 2, h / 2); c.rotate(35)
    c.drawCentredString(0, 0, "TRACES")
    c.restoreState()

    c.setStrokeColor(LINE); c.line(15 * mm, 20 * mm, w - 15 * mm, 20 * mm)
    c.setFont("Helvetica", 7); c.setFillColor(SUBTLE)
    c.drawString(15 * mm, 14 * mm,
                 "This is a TRACES-generated document. Verify at https://www.tdscpc.gov.in")
    c.drawRightString(w - 15 * mm, 14 * mm, "Page 1 of 2")
    c.showPage()

    # Page 2 — ITR Acknowledgement
    c.setFillColor(TRACES_BLUE); c.rect(0, h - 22 * mm, w, 22 * mm, fill=1, stroke=0)
    c.setFillColor(colors.white); c.setFont("Helvetica-Bold", 14)
    c.drawString(15 * mm, h - 11 * mm, "INCOME TAX RETURN ACKNOWLEDGEMENT")
    c.setFont("Helvetica", 9)
    c.drawString(15 * mm, h - 17 * mm,
                 f"ITR-1 / SAHAJ  |  Assessment Year {ay_year}-{(ay_year + 1) % 100:02d}")
    c.setFont("Helvetica-Bold", 11)
    c.drawRightString(w - 15 * mm, h - 11 * mm, "ITR-V")
    c.setFont("Helvetica", 9)
    c.drawRightString(w - 15 * mm, h - 17 * mm, f"e-Filing Acknowledgement Number: 9{p.aadhaar[2:]}")

    # Same column-major-serialisation hazard as page 1 — keep table labels
    # innocuous and put the regex-friendly KV lines inline before the table.
    c.setFont("Helvetica", 10); c.setFillColor(INK)
    base2 = h - 36 * mm
    inline2 = [
        f"Name: {p.name}",
        f"PAN: {p.pan}",
        f"Date of Birth: {p.dob}",
        f"Assessment Year: {ay_year}-{(ay_year + 1) % 100:02d}",
        f"Mobile: {p.mobile}",
        f"Email: {p.email}",
    ]
    for i, line in enumerate(inline2):
        c.drawString(15 * mm, base2 - i * 5 * mm, line)

    rows3 = [
        ["Assessee Name:",       p.name],
        ["Permanent Account No.:", p.pan],
        ["Birth Date:",          p.dob],
        ["Tax Year (AY):",       f"{ay_year}-{(ay_year + 1) % 100:02d}"],
        ["Filing Status:",       "Originally filed (electronically) under section 139(1)"],
        ["Aadhaar (masked):",    f"XXXX XXXX {p.aadhaar[-4:]}"],
        ["Contact Mobile:",      p.mobile],
        ["Contact Email:",       p.email],
        ["Residential Address:", p.address],
    ]
    t3 = make_kv(rows3, TRACES_BLUE); t3.wrapOn(c, w, 90 * mm); t3.drawOn(c, 15 * mm, h - 145 * mm)

    summary_rows = [
        ["Computation of Income and Tax", "Amount (INR)"],
        ["A. Income from Salary",           fmt_inr(annual_gross - 50000 - annual_pt).replace("Rs. ", "")],
        ["B. Income from House Property",   "0.00"],
        ["C. Income from Other Sources",    "0.00"],
        ["D. Gross of all incomes",         fmt_inr(annual_gross).replace("Rs. ", "")],
        ["E. Chapter VI-A Deductions",      fmt_inr(annual_pf).replace("Rs. ", "")],
        ["F. Net Taxable Salary",           fmt_inr(taxable).replace("Rs. ", "")],
        ["G. Tax Payable",                  fmt_inr(annual_tds).replace("Rs. ", "")],
        ["H. Tax Already Paid",             fmt_inr(annual_tds).replace("Rs. ", "")],
        ["I. Refund / (Payable)",           "0.00"],
    ]
    sum_t = Table(summary_rows, colWidths=[110 * mm, 60 * mm])
    sum_t.setStyle(TableStyle([
        ("FONT", (0, 0), (-1, 0), "Helvetica-Bold", 10),
        ("FONT", (0, 1), (-1, -1), "Helvetica", 9),
        ("BACKGROUND", (0, 0), (-1, 0), TRACES_BLUE),
        ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
        ("ALIGN", (1, 0), (1, -1), "RIGHT"),
        ("BOX", (0, 0), (-1, -1), 0.5, TRACES_BLUE),
        ("INNERGRID", (0, 0), (-1, -1), 0.3, LINE),
        ("LEFTPADDING", (0, 0), (-1, -1), 6),
        ("RIGHTPADDING", (0, 0), (-1, -1), 6),
        ("TOPPADDING", (0, 0), (-1, -1), 4),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 4),
    ]))
    sum_t.wrapOn(c, w, 90 * mm); sum_t.drawOn(c, 15 * mm, 60 * mm)

    c.setFont("Helvetica-Oblique", 8); c.setFillColor(SUBTLE)
    c.drawString(15 * mm, 50 * mm,
                 "Verified electronically using Aadhaar OTP. No physical signature required.")
    c.setStrokeColor(LINE); c.line(15 * mm, 20 * mm, w - 15 * mm, 20 * mm)
    c.setFont("Helvetica", 7)
    c.drawString(15 * mm, 14 * mm, "Generated via incometax.gov.in e-Filing Portal")
    c.drawRightString(w - 15 * mm, 14 * mm, "Page 2 of 2")
    c.showPage()
    c.save()


# --- Credit report ------------------------------------------------------------
def render_credit_report(p: Persona, out: Path) -> None:
    """2-page CIBIL-style report with prominent score gauge + tradelines."""
    c = canvas.Canvas(str(out), pagesize=A4)
    w, h = A4
    score = p.credit_score
    if score >= 750: band, band_color = "EXCELLENT", CIBIL_GREEN
    elif score >= 700: band, band_color = "GOOD", HexColor("#65A30D")
    elif score >= 650: band, band_color = "FAIR", CIBIL_AMBER
    elif score >= 600: band, band_color = "POOR", HexColor("#EA580C")
    else: band, band_color = "VERY POOR", CIBIL_RED

    # Header
    c.setFillColor(HexColor("#0E7490"))
    c.rect(0, h - 22 * mm, w, 22 * mm, fill=1, stroke=0)
    c.setFillColor(colors.white); c.setFont("Helvetica-Bold", 16)
    c.drawString(15 * mm, h - 11 * mm, p.bureau.upper())
    c.setFont("Helvetica", 9)
    c.drawString(15 * mm, h - 17 * mm,
                 "Credit Information Bureau (India) Limited  |  Member of TransUnion")
    c.setFont("Helvetica-Bold", 11)
    c.drawRightString(w - 15 * mm, h - 11 * mm, "CONSUMER CREDIT INFORMATION REPORT")
    c.setFont("Helvetica", 9)
    c.drawRightString(w - 15 * mm, h - 17 * mm,
                      f"Report Date: {date.today().isoformat()}  |  Report ID: CIR-{p.aadhaar[-6:]}")

    # Applicant block
    rows1 = [
        ["Consumer Name:",   p.name],
        ["PAN:",             p.pan],
        ["Date of Birth:",   p.dob],
        ["Aadhaar (masked):", f"XXXX XXXX {p.aadhaar[-4:]}"],
        ["Mobile:",          p.mobile],
        ["Email:",           p.email],
        ["Address on File:", p.address],
    ]
    t1 = Table(rows1, colWidths=[40 * mm, 138 * mm])
    t1.setStyle(TableStyle([
        ("FONT", (0, 0), (-1, -1), "Helvetica", 9),
        ("FONT", (0, 0), (0, -1), "Helvetica-Bold", 9),
        ("BOX", (0, 0), (-1, -1), 0.5, HexColor("#0E7490")),
        ("INNERGRID", (0, 0), (-1, -1), 0.3, LINE),
        ("BACKGROUND", (0, 0), (0, -1), HexColor("#ECFEFF")),
        ("LEFTPADDING", (0, 0), (-1, -1), 6),
        ("RIGHTPADDING", (0, 0), (-1, -1), 6),
        ("TOPPADDING", (0, 0), (-1, -1), 4),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 4),
    ]))
    t1.wrapOn(c, w, 80 * mm); t1.drawOn(c, 15 * mm, h - 100 * mm)

    # Score gauge box
    box_x, box_y, box_w, box_h = 15 * mm, h - 175 * mm, w - 30 * mm, 60 * mm
    c.setStrokeColor(band_color); c.setLineWidth(2)
    c.roundRect(box_x, box_y, box_w, box_h, 4 * mm, fill=0, stroke=1)
    c.setFillColor(band_color); c.setFont("Helvetica-Bold", 64)
    c.drawString(box_x + 15 * mm, box_y + 14 * mm, str(score))
    c.setFillColor(SUBTLE); c.setFont("Helvetica", 9)
    c.drawString(box_x + 15 * mm, box_y + 9 * mm, "out of 900")
    c.setFillColor(INK); c.setFont("Helvetica-Bold", 14)
    c.drawString(box_x + 90 * mm, box_y + 40 * mm, "TransUnion CIBIL Score")
    c.setFillColor(band_color); c.setFont("Helvetica-Bold", 24)
    c.drawString(box_x + 90 * mm, box_y + 28 * mm, band)
    c.setFillColor(INK); c.setFont("Helvetica", 9)
    c.drawString(box_x + 90 * mm, box_y + 20 * mm,
                 "CIBIL Score: " + str(score) + "  |  Range: 300 to 900")
    c.drawString(box_x + 90 * mm, box_y + 14 * mm,
                 "Score reflects the last 24 months of credit behaviour.")

    # Inline regex-friendly lines
    c.setFont("Helvetica", 10); c.setFillColor(INK)
    yy = box_y - 8 * mm
    c.drawString(15 * mm, yy,        f"CIBIL Score: {score}")
    c.drawString(15 * mm, yy - 5 * mm, f"Credit Score: {score}")
    c.drawString(15 * mm, yy - 10 * mm, f"PAN: {p.pan}")
    c.drawString(15 * mm, yy - 15 * mm, f"Name: {p.name}")

    c.setStrokeColor(LINE); c.line(15 * mm, 22 * mm, w - 15 * mm, 22 * mm)
    c.setFont("Helvetica", 7); c.setFillColor(SUBTLE)
    c.drawString(15 * mm, 16 * mm,
                 "TransUnion CIBIL Limited, One Indiabulls Centre, Tower 2A, Senapati Bapat Marg, Mumbai 400013.")
    c.drawString(15 * mm, 12 * mm,
                 "Disputes: dispute@cibil.com  |  Helpline: 22-66384600  |  www.cibil.com")
    c.drawRightString(w - 15 * mm, 16 * mm, "Page 1 of 2")
    c.showPage()

    # Page 2 — Tradelines
    c.setFillColor(HexColor("#0E7490")); c.rect(0, h - 22 * mm, w, 22 * mm, fill=1, stroke=0)
    c.setFillColor(colors.white); c.setFont("Helvetica-Bold", 14)
    c.drawString(15 * mm, h - 11 * mm, p.bureau.upper())
    c.setFont("Helvetica-Bold", 11)
    c.drawRightString(w - 15 * mm, h - 11 * mm, "ACCOUNTS, BALANCES & ENQUIRIES")

    # Tradelines
    rng = random.Random(int(p.aadhaar))
    tradelines: list[list[str]] = [["Type", "Lender", "Account Last 4", "Sanctioned", "Outstanding", "Status", "Opened"]]
    if score >= 700:
        tradelines += [
            ["Credit Card", "HDFC Bank Ltd", "**** 4521",
             fmt_inr(150000).replace("Rs. ", ""), fmt_inr(rng.randint(0, 12000)).replace("Rs. ", ""),
             "Active", "2022-04-10"],
            ["Credit Card", "ICICI Bank", "**** 7788",
             fmt_inr(80000).replace("Rs. ", ""), fmt_inr(0).replace("Rs. ", ""),
             "Active", "2023-08-22"],
        ]
    elif score >= 600:
        tradelines += [
            ["Credit Card", "Axis Bank", "**** 9912",
             fmt_inr(70000).replace("Rs. ", ""), fmt_inr(rng.randint(15000, 38000)).replace("Rs. ", ""),
             "Active (current)", "2021-11-02"],
            ["Personal Loan", "Bajaj Finserv", "**** 3344",
             fmt_inr(300000).replace("Rs. ", ""), fmt_inr(rng.randint(60000, 120000)).replace("Rs. ", ""),
             "Active (1-30 DPD)", "2022-09-15"],
        ]
    else:
        tradelines += [
            ["Credit Card", "Kotak Mahindra", "**** 1188",
             fmt_inr(50000).replace("Rs. ", ""), fmt_inr(rng.randint(30000, 49000)).replace("Rs. ", ""),
             "Overdue (60+ DPD)", "2020-06-18"],
            ["Personal Loan", "HDFC Bank Ltd", "**** 5567",
             fmt_inr(400000).replace("Rs. ", ""), fmt_inr(rng.randint(120000, 250000)).replace("Rs. ", ""),
             "Sub-standard", "2021-03-09"],
            ["Consumer Loan", "Bajaj Finserv", "**** 6701",
             fmt_inr(75000).replace("Rs. ", ""), fmt_inr(rng.randint(20000, 60000)).replace("Rs. ", ""),
             "Overdue (90+ DPD)", "2022-12-03"],
        ]

    tl_table = Table(tradelines, colWidths=[26 * mm, 32 * mm, 22 * mm, 26 * mm, 26 * mm, 26 * mm, 24 * mm])
    tl_table.setStyle(TableStyle([
        ("FONT", (0, 0), (-1, 0), "Helvetica-Bold", 9),
        ("FONT", (0, 1), (-1, -1), "Helvetica", 8),
        ("BACKGROUND", (0, 0), (-1, 0), HexColor("#0E7490")),
        ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
        ("ROWBACKGROUNDS", (0, 1), (-1, -1), [colors.white, HexColor("#F0FDFA")]),
        ("BOX", (0, 0), (-1, -1), 0.4, LINE),
        ("INNERGRID", (0, 0), (-1, -1), 0.2, LINE),
        ("ALIGN", (3, 1), (4, -1), "RIGHT"),
        ("LEFTPADDING", (0, 0), (-1, -1), 4),
        ("RIGHTPADDING", (0, 0), (-1, -1), 4),
        ("TOPPADDING", (0, 0), (-1, -1), 4),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 4),
    ]))
    tl_table.wrapOn(c, w, 60 * mm); tl_table.drawOn(c, 15 * mm, h - 100 * mm)

    # Summary box (existing EMI etc.)
    total_outstanding = sum(int(r[4].replace(",", "").replace(".00", "")) for r in tradelines[1:])
    summary_rows = [
        ["Active Loans", str(len([r for r in tradelines[1:] if "Loan" in r[0]]))],
        ["Active Credit Cards", str(len([r for r in tradelines[1:] if "Card" in r[0]]))],
        ["Total Outstanding", fmt_inr(total_outstanding)],
        ["Existing EMI (monthly)", fmt_inr(p.existing_emi)],
        ["Enquiries (last 6 mo)", str(rng.randint(0, 4))],
        ["Days Past Due (worst)", "0" if score >= 700 else "30" if score >= 600 else "90"],
    ]
    sm_t = Table(summary_rows, colWidths=[60 * mm, 40 * mm])
    sm_t.setStyle(TableStyle([
        ("FONT", (0, 0), (-1, -1), "Helvetica-Bold", 10),
        ("BOX", (0, 0), (-1, -1), 0.5, HexColor("#0E7490")),
        ("INNERGRID", (0, 0), (-1, -1), 0.3, LINE),
        ("BACKGROUND", (0, 0), (-1, -1), HexColor("#ECFEFF")),
        ("ALIGN", (1, 0), (1, -1), "RIGHT"),
        ("LEFTPADDING", (0, 0), (-1, -1), 6),
        ("RIGHTPADDING", (0, 0), (-1, -1), 6),
        ("TOPPADDING", (0, 0), (-1, -1), 5),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
    ]))
    sm_t.wrapOn(c, w, 60 * mm); sm_t.drawOn(c, 15 * mm, 60 * mm)

    # Inline regex-friendly lines
    c.setFont("Helvetica", 10); c.setFillColor(INK)
    c.drawString(120 * mm, 90 * mm, f"Total Outstanding: {fmt_inr(total_outstanding)}")
    c.drawString(120 * mm, 84 * mm, f"Existing EMI: {fmt_inr(p.existing_emi)}")
    c.drawString(120 * mm, 78 * mm, f"DPD: {'0' if score >= 700 else '30' if score >= 600 else '90'}")

    c.setStrokeColor(LINE); c.line(15 * mm, 22 * mm, w - 15 * mm, 22 * mm)
    c.setFont("Helvetica", 7); c.setFillColor(SUBTLE)
    c.drawString(15 * mm, 16 * mm,
                 "Information sourced from credit institutions; updated as of report date. "
                 "Disputes can be raised at dispute@cibil.com.")
    c.drawRightString(w - 15 * mm, 16 * mm, "Page 2 of 2")
    c.showPage()
    c.save()


# --- Scenario definitions -----------------------------------------------------
COMMON_ADDR = "Flat 4B, 12 Brigade Road, Indiranagar, Bengaluru 560038, Karnataka, India"

PERSONAS: list[Persona] = [
    Persona(
        batch="batch1_happy", outcome="APPROVED",
        name="Subham Dutta", dob="1995-05-15",
        pan="AXZPD5678K", aadhaar=make_aadhaar(101),
        mobile="9876500001", email="subham.dutta@example.com",
        username="subhamdutta",
        employer="Acme Technologies India Pvt Ltd",
        employee_id="ACME-7788", designation="Senior Software Engineer",
        joining_date="2022-06-01",
        address=COMMON_ADDR,
        bank_name="HDFC Bank Ltd", bank_branch="Indiranagar",
        ifsc="HDFC0000123", account_number="50100123456789",
        gross_salary=95000, deductions_pf=11400, deductions_tax=8000, deductions_pt=200,
        existing_emi=0, credit_score=780, bureau="CIBIL TransUnion",
        notes=["Outcome: APPROVED. Credit ≥ 700, DTI = 0, no warnings."],
    ),
    Persona(
        batch="batch2_reject_initial", outcome="REJECTED",
        name="Ananya Sharma", dob="1996-09-22",
        pan="BRPSA1234L", aadhaar=make_aadhaar(202),
        mobile="9876500002", email="ananya.sharma@example.com",
        username="ananyasharma",
        employer="Lumen Tech Services Pvt Ltd",
        employee_id="LUMEN-2211", designation="Junior Associate",
        joining_date="2024-02-15",
        address="Plot 7, 2nd Cross Rd, Koramangala 4th Block, Bengaluru 560034, Karnataka",
        bank_name="State Bank of India", bank_branch="Koramangala",
        ifsc="SBIN0001234", account_number="38901234567",
        gross_salary=28000, deductions_pf=3360, deductions_tax=0, deductions_pt=200,
        existing_emi=16000, credit_score=540, bureau="CIBIL TransUnion",
        notes=[
            "Outcome: REJECTED. Credit score 540 < 600 (hard reject by `credit_score_below_threshold`).",
            "Plus DTI ≈ 16k/24.4k ≈ 0.66 (over_indebted) and high credit utilisation tradelines.",
            "User then re-applies in batch3_resubmit_fixed with corrected docs.",
        ],
    ),
    Persona(
        batch="batch3_resubmit_fixed", outcome="APPROVED",
        name="Ananya Sharma", dob="1996-09-22",
        pan="BRPSA1234L", aadhaar=make_aadhaar(202),
        mobile="9876500002", email="ananya.sharma@example.com",
        username="ananyasharma",
        employer="Lumen Tech Services Pvt Ltd",
        employee_id="LUMEN-2211", designation="Senior Associate",
        joining_date="2024-02-15",
        address="Plot 7, 2nd Cross Rd, Koramangala 4th Block, Bengaluru 560034, Karnataka",
        bank_name="State Bank of India", bank_branch="Koramangala",
        ifsc="SBIN0001234", account_number="38901234567",
        gross_salary=88000, deductions_pf=10560, deductions_tax=6500, deductions_pt=200,
        existing_emi=0, credit_score=760, bureau="CIBIL TransUnion",
        notes=[
            "Same applicant as batch2; resubmission after promotion + cleared loans.",
            "Outcome: APPROVED. Credit 760, no EMI, DTI = 0.",
        ],
    ),
    Persona(
        batch="batch4_manual_review", outcome="MANUAL_REVIEW",
        name="Vikram Reddy", dob="1990-03-08",
        pan="CHRPV8765J", aadhaar=make_aadhaar(303),
        mobile="9876500003", email="vikram.reddy@example.com",
        username="vikramreddy",
        employer="Northstar Analytics Pvt Ltd",
        employee_id="NORTH-1099", designation="Lead Data Analyst",
        joining_date="2019-09-12",
        address="Villa 22, Whitefield Greens, Bengaluru 560066, Karnataka",
        bank_name="Axis Bank Ltd", bank_branch="Whitefield",
        ifsc="UTIB0000567", account_number="912010012345678",
        gross_salary=68000, deductions_pf=8160, deductions_tax=3200, deductions_pt=200,
        existing_emi=0, credit_score=670, bureau="CIBIL TransUnion",
        notes=[
            "Outcome: MANUAL_REVIEW. Credit 670 is below CREDIT_APPROVE_MIN=700 but above",
            "CREDIT_FAIL_MAX=600, so the auto-decision falls into the manual-review band.",
            "DTI=0 and no warnings, so it cannot auto-approve and cannot auto-reject either.",
        ],
    ),
    Persona(
        batch="batch5_reject_full", outcome="REJECTED",
        name="Rajesh Kumar", dob="1988-11-30",
        pan="DRPSK4321M", aadhaar=make_aadhaar(404),
        mobile="9876500004", email="rajesh.kumar@example.com",
        username="rajeshkumar",
        employer="Globex Manufacturing Ltd",
        employee_id="GLBX-5544", designation="Floor Supervisor",
        joining_date="2018-04-01",
        address="House 14, MG Road, Yelahanka New Town, Bengaluru 560064, Karnataka",
        bank_name="Punjab National Bank", bank_branch="Yelahanka",
        ifsc="PUNB0123450", account_number="0123456789012",
        gross_salary=32000, deductions_pf=3840, deductions_tax=0, deductions_pt=200,
        existing_emi=20000, credit_score=480, bureau="CIBIL TransUnion",
        bank_holder_override="Suresh Mehta",
        notes=[
            "Outcome: REJECTED. Multi-failure path:",
            "- Credit 480 < 600 (`credit_score_below_threshold`)",
            "- Cross-doc name mismatch: bank statement holder = 'Suresh Mehta' vs declared 'Rajesh Kumar'",
            "  (`rule_name_matrix` fails → `cross_doc_failures`)",
            "- DTI ≈ 0.71 (over_indebted)",
        ],
    ),
]


def render_batch(p: Persona) -> Path:
    out_dir = OUT_ROOT / p.batch
    out_dir.mkdir(parents=True, exist_ok=True)

    months = last_n_months(3)
    print(f"\n[{p.batch}] {p.name} -> {p.outcome}")
    render_aadhaar(p, out_dir / "aadhaar.pdf"); print(f"  - aadhaar.pdf")
    render_pan(p, out_dir / "pan.pdf"); print(f"  - pan.pdf")
    for i, m in enumerate(months, start=1):
        f = out_dir / f"payslip-{m.strftime('%Y-%m')}.pdf"
        render_payslip(p, m, f); print(f"  - {f.name}")
    for i, m in enumerate(months, start=1):
        f = out_dir / f"bank-statement-{m.strftime('%Y-%m')}.pdf"
        render_bank_statement(p, m, f); print(f"  - {f.name}")
    render_employment_letter(p, out_dir / "employment-letter.pdf"); print(f"  - employment-letter.pdf")
    ay_year = date.today().year if date.today().month >= 4 else date.today().year - 1
    render_itr(p, out_dir / "itr-form16.pdf", ay_year); print(f"  - itr-form16.pdf")
    render_credit_report(p, out_dir / "credit-report.pdf"); print(f"  - credit-report.pdf")

    # Per-batch README
    readme = out_dir / "README.txt"
    lines = [
        f"Scenario: {p.batch}",
        f"Expected outcome: {p.outcome}",
        "",
        "----- Signup payload (POST /api/auth/signup) -----",
        "{",
        f'  "username": "{p.username}",',
        f'  "firstname": "{p.first_name}",',
        f'  "lastname":  "{p.last_name}",',
        f'  "email":     "{p.email}",',
        f'  "mobile":    "{p.mobile}",',
        f'  "password":  "Test@1234",',
        f'  "role":      "USER",',
        f'  "dob":       "{p.dob}"',
        "}",
        "",
        "----- KYC PII (must match docs exactly) -----",
        f"  Full name:      {p.name}",
        f"  DOB:            {p.dob}",
        f"  Aadhaar number: {p.aadhaar}",
        f"  PAN:            {p.pan}",
        f"  Mobile:         {p.mobile}",
        f"  Email:          {p.email}",
        "",
        "----- Notes -----",
    ] + [f"  - {n}" for n in p.notes]
    readme.write_text("\n".join(lines), encoding="utf-8")
    print(f"  - README.txt")
    return out_dir


def main() -> None:
    OUT_ROOT.mkdir(parents=True, exist_ok=True)
    print(f"Generating scenarios under {OUT_ROOT}")
    for p in PERSONAS:
        render_batch(p)

    # Top-level index
    idx = OUT_ROOT / "INDEX.md"
    body = ["# Underwriting test scenarios\n"]
    for p in PERSONAS:
        body.append(f"## `{p.batch}` — {p.name} → **{p.outcome}**")
        body.append(f"- DOB: `{p.dob}`  |  PAN: `{p.pan}`  |  Aadhaar: `{p.aadhaar}`")
        body.append(f"- Username: `{p.username}`  |  Email: `{p.email}`  |  Mobile: `{p.mobile}`")
        body.append(f"- Net salary: ₹{int(p.net_salary):,}/mo  |  EMI: ₹{int(p.existing_emi):,}/mo  |  Credit: {p.credit_score}")
        for n in p.notes:
            body.append(f"  - {n}")
        body.append("")
    idx.write_text("\n".join(body), encoding="utf-8")
    print(f"\nIndex written to {idx}")
    print("Done.")


if __name__ == "__main__":
    main()
