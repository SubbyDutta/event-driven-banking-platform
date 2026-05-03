Scenario: batch1_happy
Expected outcome: APPROVED

----- Signup payload (POST /api/auth/signup) -----
{
  "username": "subhamdutta",
  "firstname": "Subham",
  "lastname":  "Dutta",
  "email":     "subham.dutta@example.com",
  "mobile":    "9876500001",
  "password":  "Test@1234",
  "role":      "USER",
  "dob":       "1995-05-15"
}

----- KYC PII (must match docs exactly) -----
  Full name:      Subham Dutta
  DOB:            1995-05-15
  Aadhaar number: 585708393474
  PAN:            AXZPD5678K
  Mobile:         9876500001
  Email:          subham.dutta@example.com

----- Notes -----
  - Outcome: APPROVED. Credit ≥ 700, DTI = 0, no warnings.