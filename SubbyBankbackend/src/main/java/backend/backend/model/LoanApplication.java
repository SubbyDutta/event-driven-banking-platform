package backend.backend.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * Root entity for a loan application. Pre-V4 this held only the minimal
 * "approve / repay" shape; V4 adds lifecycle tracking, findoc-verify
 * correlation, and ML risk outputs so the event-driven origination pipeline
 * has somewhere to persist its intermediate state.
 *
 * <p>The legacy fields (username, status, monthsRemaining, monthlyEmi,
 * approvedAt, nextDueDate) stay populated the way LoanRepayController expects
 * — {@link backend.backend.service.LoanFinalizationService} writes both the
 * new lifecycle fields AND the legacy ones in a single transaction when a
 * loan is approved, so downstream code never sees a partially-migrated row.
 */
@Entity
@Data
public class LoanApplication {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String username;
    private double amount;
    private double due_amount;
    private boolean approved = false;
    private String status;
    private int monthsRemaining = 6;
    private double monthlyEmi;
    private LocalDateTime approvedAt;
    private LocalDateTime nextDueDate;

    /**
     * Stable external identifier used as {@code correlationId} with findoc-verify
     * and SubbyPythonLoan, and as the path segment in S3 doc keys. Generated at
     * submit time and NEVER changes. UUID string.
     */
    @Column(name = "external_id", length = 64, unique = true)
    private String externalId;

    /**
     * Authoritative FK into {@code users}. The legacy {@link #username} column
     * is still written for backward compat but new code should prefer userId.
     */
    @Column(name = "user_id")
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle_status", nullable = false, length = 32)
    private LoanLifecycleStatus lifecycleStatus = LoanLifecycleStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", length = 32)
    private LoanPurpose purpose;

    @Column(name = "findoc_loan_application_id", length = 64)
    private String findocLoanApplicationId;

    /**
     * Full findoc-verify report envelope (compliance, cross-doc, fraud, the
     * nested report JSON). Stored as jsonb via {@code @JdbcTypeCode(JSON)} so
     * Postgres binds the String as jsonb instead of text. Mirror of User.kycReportJson.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "loan_report_json", columnDefinition = "jsonb")
    private String loanReportJson;

    /** Max fraud-signal score extracted from loanReportJson.fraudSignals[]. 0..1. */
    @Column(name = "fraud_score", precision = 5, scale = 3)
    private BigDecimal fraudScore;

    /** A..E. Drives the interest-rate ladder and the hard-reject on E. */
    @Column(name = "risk_band", length = 4)
    private String riskBand;

    /** probability_of_default from SubbyPythonLoan. 0..1. */
    @Column(name = "risk_probability", precision = 5, scale = 3)
    private BigDecimal riskProbability;

    /** Annual interest rate %, derived from risk_band at decision time. */
    @Column(name = "interest_rate", precision = 5, scale = 2)
    private BigDecimal interestRate;

    @Column(name = "decision_reason", columnDefinition = "TEXT")
    private String decisionReason;

    /** Raw ML recommendation captured at risk-evaluation time: "approve" / "reject" / "manual_review". */
    @Column(name = "ml_recommendation", length = 32)
    private String mlRecommendation;

    @Column(name = "doc_reeval_result", length = 32)
    private String docReevalResult;

    @Column(name = "doc_reeval_reason", columnDefinition = "TEXT")
    private String docReevalReason;

    @Column(name = "doc_reeval_run_number")
    private Integer docReevalRunNumber;

    @Column(name = "doc_reeval_at")
    private Instant docReevalAt;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;
}
