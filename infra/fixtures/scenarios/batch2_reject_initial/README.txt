Scenario: batch2_reject_initial
Expected outcome: REJECTED

----- Signup payload (POST /api/auth/signup) -----
{
  "username": "ananyasharma",
  "firstname": "Ananya",
  "lastname":  "Sharma",
  "email":     "ananya.sharma@example.com",
  "mobile":    "9876500002",
  "password":  "Test@1234",
  "role":      "USER",
  "dob":       "1996-09-22"
}

----- KYC PII (must match docs exactly) -----
  Full name:      Ananya Sharma
  DOB:            1996-09-22
  Aadhaar number: 876336067701
  PAN:            BRPSA1234L
  Mobile:         9876500002
  Email:          ananya.sharma@example.com

----- Notes -----
  - Outcome: REJECTED. Credit score 540 < 600 (hard reject by `credit_score_below_threshold`).
  - Plus DTI ≈ 16k/24.4k ≈ 0.66 (over_indebted) and high credit utilisation tradelines.
  - User then re-applies in batch3_resubmit_fixed with corrected docs.