Scenario: batch3_resubmit_fixed
Expected outcome: APPROVED

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
  - Same applicant as batch2; resubmission after promotion + cleared loans.
  - Outcome: APPROVED. Credit 760, no EMI, DTI = 0.