Scenario: batch4_manual_review
Expected outcome: MANUAL_REVIEW

----- Signup payload (POST /api/auth/signup) -----
{
  "username": "vikramreddy",
  "firstname": "Vikram",
  "lastname":  "Reddy",
  "email":     "vikram.reddy@example.com",
  "mobile":    "9876500003",
  "password":  "Test@1234",
  "role":      "USER",
  "dob":       "1990-03-08"
}

----- KYC PII (must match docs exactly) -----
  Full name:      Vikram Reddy
  DOB:            1990-03-08
  Aadhaar number: 233199498622
  PAN:            CHRPV8765J
  Mobile:         9876500003
  Email:          vikram.reddy@example.com

----- Notes -----
  - Outcome: MANUAL_REVIEW. Credit 670 is below CREDIT_APPROVE_MIN=700 but above
  - CREDIT_FAIL_MAX=600, so the auto-decision falls into the manual-review band.
  - DTI=0 and no warnings, so it cannot auto-approve and cannot auto-reject either.