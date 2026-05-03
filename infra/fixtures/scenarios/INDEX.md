# Underwriting test scenarios

## `batch1_happy` — Subham Dutta → **APPROVED**
- DOB: `1995-05-15`  |  PAN: `AXZPD5678K`  |  Aadhaar: `585708393474`
- Username: `subhamdutta`  |  Email: `subham.dutta@example.com`  |  Mobile: `9876500001`
- Net salary: ₹75,400/mo  |  EMI: ₹0/mo  |  Credit: 780
  - Outcome: APPROVED. Credit ≥ 700, DTI = 0, no warnings.

## `batch2_reject_initial` — Ananya Sharma → **REJECTED**
- DOB: `1996-09-22`  |  PAN: `BRPSA1234L`  |  Aadhaar: `876336067701`
- Username: `ananyasharma`  |  Email: `ananya.sharma@example.com`  |  Mobile: `9876500002`
- Net salary: ₹24,440/mo  |  EMI: ₹16,000/mo  |  Credit: 540
  - Outcome: REJECTED. Credit score 540 < 600 (hard reject by `credit_score_below_threshold`).
  - Plus DTI ≈ 16k/24.4k ≈ 0.66 (over_indebted) and high credit utilisation tradelines.
  - User then re-applies in batch3_resubmit_fixed with corrected docs.

## `batch3_resubmit_fixed` — Ananya Sharma → **APPROVED**
- DOB: `1996-09-22`  |  PAN: `BRPSA1234L`  |  Aadhaar: `876336067701`
- Username: `ananyasharma`  |  Email: `ananya.sharma@example.com`  |  Mobile: `9876500002`
- Net salary: ₹70,740/mo  |  EMI: ₹0/mo  |  Credit: 760
  - Same applicant as batch2; resubmission after promotion + cleared loans.
  - Outcome: APPROVED. Credit 760, no EMI, DTI = 0.

## `batch4_manual_review` — Vikram Reddy → **MANUAL_REVIEW**
- DOB: `1990-03-08`  |  PAN: `CHRPV8765J`  |  Aadhaar: `233199498622`
- Username: `vikramreddy`  |  Email: `vikram.reddy@example.com`  |  Mobile: `9876500003`
- Net salary: ₹56,440/mo  |  EMI: ₹0/mo  |  Credit: 670
  - Outcome: MANUAL_REVIEW. Credit 670 is below CREDIT_APPROVE_MIN=700 but above
  - CREDIT_FAIL_MAX=600, so the auto-decision falls into the manual-review band.
  - DTI=0 and no warnings, so it cannot auto-approve and cannot auto-reject either.

## `batch5_reject_full` — Rajesh Kumar → **REJECTED**
- DOB: `1988-11-30`  |  PAN: `DRPSK4321M`  |  Aadhaar: `360819152613`
- Username: `rajeshkumar`  |  Email: `rajesh.kumar@example.com`  |  Mobile: `9876500004`
- Net salary: ₹27,960/mo  |  EMI: ₹20,000/mo  |  Credit: 480
  - Outcome: REJECTED. Multi-failure path:
  - - Credit 480 < 600 (`credit_score_below_threshold`)
  - - Cross-doc name mismatch: bank statement holder = 'Suresh Mehta' vs declared 'Rajesh Kumar'
  -   (`rule_name_matrix` fails → `cross_doc_failures`)
  - - DTI ≈ 0.71 (over_indebted)
