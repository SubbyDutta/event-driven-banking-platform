from datetime import date, timedelta

import pytest

from src.pipeline import compliance as c


def test_verhoeff_valid_known_good():
    assert c.verhoeff_valid("234123412346") is True


def test_verhoeff_invalid():
    assert c.verhoeff_valid("234123412340") is False


def test_verhoeff_wrong_length():
    assert c.verhoeff_valid("1234") is False


def test_pan_format_pass():
    app = {"documents": {"pan": {"fields": {"pan_number": "ABCPE1234F"}}}}
    r = c.check_pan_format(app)
    assert r["status"] == "pass"


def test_pan_format_fail_bad_pattern():
    app = {"documents": {"pan": {"fields": {"pan_number": "AB1DE1234F"}}}}
    r = c.check_pan_format(app)
    assert r["status"] == "fail"


def test_pan_format_fail_bad_category():
    app = {"documents": {"pan": {"fields": {"pan_number": "ABCZE1234F"}}}}
    r = c.check_pan_format(app)
    assert r["status"] == "fail"


def test_name_pan_aadhaar_pass_fuzzy():
    app = {"documents": {
        "pan": {"fields": {"full_name": "Raj Kumar Singh"}},
        "aadhaar": {"fields": {"full_name": "Raj K. Singh"}},
    }}
    r = c.check_name_pan_aadhaar(app)
    assert r["status"] in ("pass", "warning")


def test_name_pan_aadhaar_fail_totally_different():
    app = {"documents": {
        "pan": {"fields": {"full_name": "Raj Kumar"}},
        "aadhaar": {"fields": {"full_name": "Priya Sharma"}},
    }}
    r = c.check_name_pan_aadhaar(app)
    assert r["status"] == "fail"


def test_dob_consistency_pass():
    app = {"documents": {
        "aadhaar": {"fields": {"dob": "1990-05-12"}},
        "pan": {"fields": {"dob": "1990-05-12"}},
    }}
    assert c.check_dob_consistency(app)["status"] == "pass"


def test_dob_consistency_fail():
    app = {"documents": {
        "aadhaar": {"fields": {"dob": "1990-05-12"}},
        "pan": {"fields": {"dob": "1991-05-12"}},
    }}
    assert c.check_dob_consistency(app)["status"] == "fail"


def test_itr_pan_match_pass():
    app = {"documents": {
        "pan": {"fields": {"pan_number": "ABCPE1234F"}},
        "itr": {"fields": {"pan_number": "ABCPE1234F"}},
    }}
    assert c.check_itr_pan_matches_id(app)["status"] == "pass"


def test_itr_pan_match_fail():
    app = {"documents": {
        "pan": {"fields": {"pan_number": "ABCPE1234F"}},
        "itr": {"fields": {"pan_number": "XYZPE9999F"}},
    }}
    assert c.check_itr_pan_matches_id(app)["status"] == "fail"


def _payslip(m):
    return {"period_month": m.isoformat()}


def test_payslip_period_coverage_pass():
    today = date.today()
    m1 = date(today.year, today.month, 1)
    prev1 = date(m1.year, m1.month - 1 if m1.month > 1 else 12, 1) if m1.month > 1 else date(m1.year - 1, 12, 1)
    prev2 = date(prev1.year, prev1.month - 1 if prev1.month > 1 else 12, 1) if prev1.month > 1 else date(prev1.year - 1, 12, 1)
    app = {"documents": {"payslips": [_payslip(prev2), _payslip(prev1), _payslip(m1)]}}
    assert c.check_payslip_period_coverage(app)["status"] == "pass"


def test_payslip_period_coverage_fail_missing():
    app = {"documents": {"payslips": [_payslip(date(2024, 1, 1))]}}
    assert c.check_payslip_period_coverage(app)["status"] == "fail"


def test_credit_score_pass():
    app = {"documents": {"credit_report": {"fields": {"credit_score": "780"}}}}
    assert c.check_credit_score_threshold(app)["status"] == "pass"


def test_credit_score_warning_band():
    app = {"documents": {"credit_report": {"fields": {"credit_score": "620"}}}}
    assert c.check_credit_score_threshold(app)["status"] == "warning"


def test_credit_score_fail():
    app = {"documents": {"credit_report": {"fields": {"credit_score": "540"}}}}
    assert c.check_credit_score_threshold(app)["status"] == "fail"


def test_duplicate_file_hash_fail():
    app = {"documents": {
        "aadhaar": {"file_hash": "abc", "fields": {}},
        "pan": {"file_hash": "abc", "fields": {}},
    }}
    assert c.check_duplicate_file_hashes(app)["status"] == "fail"


def test_duplicate_file_hash_pass():
    app = {"documents": {
        "aadhaar": {"file_hash": "abc", "fields": {}},
        "pan": {"file_hash": "def", "fields": {}},
    }}
    assert c.check_duplicate_file_hashes(app)["status"] == "pass"


def test_employment_letter_recent_pass():
    today = date.today()
    app = {"documents": {"employment_letter": {"fields": {"letter_date": (today - timedelta(days=30)).isoformat()}}}}
    assert c.check_employment_letter_recency(app)["status"] == "pass"


def test_employment_letter_too_old_fail():
    today = date.today()
    app = {"documents": {"employment_letter": {"fields": {"letter_date": (today - timedelta(days=400)).isoformat()}}}}
    assert c.check_employment_letter_recency(app)["status"] == "fail"


def test_dti_ratio_pass():
    app = {
        "documents": {
            "payslips": [
                {"fields": {"net_salary": "100000"}},
                {"fields": {"net_salary": "100000"}},
                {"fields": {"net_salary": "100000"}},
            ],
            "credit_report": {"fields": {"existing_emi": "10000"}},
        },
        "proposed_emi": "15000",
    }
    r = c.check_dti_ratio(app)
    assert r is not None
    assert r["status"] == "pass"
    assert r["details"]["ratio"] == 0.25


def test_threshold_store_falls_back_to_default(monkeypatch):
    import asyncio
    from src.policy import thresholds as th

    store = th.ThresholdStore()
    store._cache = {"dti_max_ratio": 0.6}

    async def _noop(self):
        return None

    monkeypatch.setattr(th.ThresholdStore, "_refresh_if_stale", _noop)

    async def run():
        return await store.get("dti_max_ratio", 0.55), await store.get("missing_key", 1.23)

    db_value, fallback = asyncio.run(run())
    assert db_value == 0.6
    assert fallback == 1.23
