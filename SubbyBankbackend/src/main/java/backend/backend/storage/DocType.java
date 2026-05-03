package backend.backend.storage;

/**
 * Document categories accepted by the document staging pipeline. The name is
 * used as a path segment in the S3 key, so values must be URL-safe and stable.
 *
 * <p>Prompts 4 (KYC) and 5 (loan) may extend this enum — keep values lowercase
 * and hyphen-separated.
 */
public enum DocType {

    AADHAAR,
    PAN,
    PASSPORT,
    DRIVERS_LICENSE,
    VOTER_ID,
    SELFIE,

    BANK_STATEMENT,
    BANK_STATEMENT_1,
    BANK_STATEMENT_2,
    BANK_STATEMENT_3,
    SALARY_SLIP,
    PAYSLIP_1,
    PAYSLIP_2,
    PAYSLIP_3,
    EMPLOYMENT_LETTER,
    ITR,
    FORM_16,
    CREDIT_REPORT,
    PROPERTY_DEED,
    OTHER
}
