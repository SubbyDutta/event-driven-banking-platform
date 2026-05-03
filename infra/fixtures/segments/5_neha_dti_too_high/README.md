# Segment 5_neha_dti_too_high

**Expected:** REJECTED (DTI / EMI burden)

**Notes:** Income only 31.5k/month. Request a 6,00,000 loan in the UI to trip emi_burden + dti_ratio thresholds. Same fixtures + smaller request (e.g. 80,000) flips to APPROVED.

## KYC docs

From `kyc/`: aadhaar.pdf, pan.pdf

KYC identity: **Neha Pillai**, PAN `DNHPL6789E`, Aadhaar `701234567819`

## Loan docs

From `loan/`: bank-statement-01..03.pdf, payslip-01..03.pdf, employment-letter.pdf, itr.pdf, credit-report.pdf

Loan identity on docs: **Neha Pillai**, PAN `DNHPL6789E`
