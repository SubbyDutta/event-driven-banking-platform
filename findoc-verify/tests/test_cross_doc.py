from src.pipeline import cross_doc as x


def test_name_matrix_pass():
    app = {"documents": {
        "aadhaar": {"fields": {"full_name": "Raj Kumar"}},
        "pan": {"fields": {"full_name": "Raj Kumar"}},
        "itr": {"fields": {"full_name": "Raj  Kumar "}},
        "payslips": [], "bank_statements": [],
    }}
    r = x.rule_name_matrix(app)
    assert r["status"] in ("pass", "warning")


def test_name_matrix_fail():
    app = {"documents": {
        "aadhaar": {"fields": {"full_name": "Raj Kumar"}},
        "pan": {"fields": {"full_name": "Priya Sharma"}},
        "payslips": [], "bank_statements": [],
    }}
    r = x.rule_name_matrix(app)
    assert r["status"] == "fail"


def test_pan_matrix_match():
    app = {"documents": {
        "pan": {"fields": {"pan_number": "ABCPE1234F"}},
        "itr": {"fields": {"pan_number": "abcpe1234f"}},
        "credit_report": {"fields": {"pan_number": "ABCPE1234F"}},
        "payslips": [], "bank_statements": [],
    }}
    assert x.rule_pan_matrix(app)["status"] == "pass"


def test_pan_matrix_mismatch():
    app = {"documents": {
        "pan": {"fields": {"pan_number": "ABCPE1234F"}},
        "itr": {"fields": {"pan_number": "XYZPE9999F"}},
        "payslips": [], "bank_statements": [],
    }}
    assert x.rule_pan_matrix(app)["status"] == "fail"


def test_period_overlap_pass():
    app = {"documents": {
        "payslips": [
            {"period_month": "2026-01-01"},
            {"period_month": "2026-02-01"},
            {"period_month": "2026-03-01"},
        ],
        "bank_statements": [
            {"period_month": "2026-01-01"},
            {"period_month": "2026-02-01"},
            {"period_month": "2026-03-01"},
        ],
    }}
    assert x.rule_period_overlap(app)["status"] == "pass"


def test_period_overlap_fail():
    app = {"documents": {
        "payslips": [{"period_month": "2026-03-01"}],
        "bank_statements": [{"period_month": "2026-02-01"}],
    }}
    assert x.rule_period_overlap(app)["status"] == "fail"


def test_payslip_bank_amount_pass():
    app = {"documents": {
        "payslips": [
            {"period_month": "2026-01-01", "fields": {"net_salary": "50000"}},
            {"period_month": "2026-02-01", "fields": {"net_salary": "50500"}},
        ],
        "bank_statements": [
            {"period_month": "2026-01-01", "fields": {"monthly_credits": "50000"}},
            {"period_month": "2026-02-01", "fields": {"monthly_credits": "50500"}},
        ],
    }}
    assert x.rule_payslip_bank_amount(app)["status"] == "pass"


def test_payslip_bank_amount_fail():
    app = {"documents": {
        "payslips": [{"period_month": "2026-01-01", "fields": {"net_salary": "50000"}}],
        "bank_statements": [{"period_month": "2026-01-01", "fields": {"monthly_credits": "100000"}}],
    }}
    assert x.rule_payslip_bank_amount(app)["status"] == "fail"
