# Segment 3_priya_kyc_vikram_loan

**Expected:** REJECTED (cross-doc / KycIdentityGuard)

**Notes:** Priya does KYC with own docs. Loan submission uses Vikram's bank statements/payslips/ITR/credit report. Cross-doc fails AND KycIdentityGuard hard-rejects on PAN mismatch. Showpiece scenario.

## KYC docs

From `kyc/`: aadhaar.pdf, pan.pdf

KYC identity: **Priya Mehta**, PAN `CPMTH3456M`, Aadhaar `634112890110`

## Loan docs

From `loan/`: bank-statement-01..03.pdf, payslip-01..03.pdf, employment-letter.pdf, itr.pdf, credit-report.pdf

Loan identity on docs: **Vikram Iyer**, PAN `BVIYP9012J`
