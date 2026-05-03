package backend.backend.model;

/**
 * Fixed-domain purpose for a loan application. UI shows these as a dropdown;
 * the backend rejects anything outside the enum. Persisted as VARCHAR via
 * {@code @Enumerated(EnumType.STRING)} so new values are a code-only change.
 */
public enum LoanPurpose {
    MEDICAL,
    EDUCATION,
    HOME_RENOVATION,
    OTHER
}
