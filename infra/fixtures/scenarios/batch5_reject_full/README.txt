Scenario: batch5_reject_full
Expected outcome: REJECTED

----- Signup payload (POST /api/auth/signup) -----
{
  "username": "rajeshkumar",
  "firstname": "Rajesh",
  "lastname":  "Kumar",
  "email":     "rajesh.kumar@example.com",
  "mobile":    "9876500004",
  "password":  "Test@1234",
  "role":      "USER",
  "dob":       "1988-11-30"
}

----- KYC PII (must match docs exactly) -----
  Full name:      Rajesh Kumar
  DOB:            1988-11-30
  Aadhaar number: 360819152613
  PAN:            DRPSK4321M
  Mobile:         9876500004
  Email:          rajesh.kumar@example.com

----- Notes -----
  - Outcome: REJECTED. Multi-failure path:
  - - Credit 480 < 600 (`credit_score_below_threshold`)
  - - Cross-doc name mismatch: bank statement holder = 'Suresh Mehta' vs declared 'Rajesh Kumar'
  -   (`rule_name_matrix` fails → `cross_doc_failures`)
  - - DTI ≈ 0.71 (over_indebted)