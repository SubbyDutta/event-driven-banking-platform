from src.pipeline import extractor as ex


def test_aadhaar_valid_match():
    c = ex.extract_aadhaar("Aadhaar No: 2345 6789 1234")
    assert c is not None and c.value == "234567891234"


def test_aadhaar_no_match():
    assert ex.extract_aadhaar("No aadhaar here") is None


def test_pan_format():
    c = ex.extract_pan("PAN: ABCDE1234F is assigned")
    assert c is not None and c.value == "ABCDE1234F"


def test_pan_no_match():
    assert ex.extract_pan("ABCD1234F") is None  # only 4 letters before 4 digits


def test_gross_salary_simple():
    c = ex.extract_gross_salary("Gross Salary: 75,000")
    assert c is not None
    assert abs(float(c.value) - 75000.0) < 0.01


def test_net_salary_with_rs_prefix():
    c = ex.extract_net_salary("Net pay this month Rs. 48,500.00")
    assert c is not None
    assert abs(float(c.value) - 48500.0) < 0.01


def test_credit_score():
    c = ex.extract_credit_score("CIBIL TransUnion Score: 742")
    assert c is not None and c.value == "742"


def test_credit_score_out_of_range():
    assert ex.extract_credit_score("Score: 125") is None


def test_taxable_income():
    c = ex.extract_taxable_income("Total Taxable Income: 8,50,000")
    assert c is not None
    assert abs(float(c.value) - 850000.0) < 0.01


def test_extract_for_payslip_composite():
    text = "Employer: Acme Corp\nGross Salary: 80000\nNet pay: 65000\nEmployee Name: Raj Kumar"
    cands = ex.extract_for_payslip(text)
    names = {c.field_name for c in cands}
    assert "gross_salary" in names
    assert "net_salary" in names
    assert "employer_name" in names


def test_try_parse_date_formats():
    assert ex.try_parse_date("2024-03-01").isoformat() == "2024-03-01"
    assert ex.try_parse_date("01/03/2024").isoformat() == "2024-03-01"
    assert ex.try_parse_date("1 March 2024").isoformat() == "2024-03-01"
