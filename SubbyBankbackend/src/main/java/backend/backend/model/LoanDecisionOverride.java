package backend.backend.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * Append-only audit row. Written every time an admin changes a loan
 * application's decision via {@code POST /api/admin/loans/{id}/override}.
 * Mirrors {@link KycDecisionOverride} one-to-one — same shape, different
 * subject, never updated.
 */
@Entity
@Table(name = "loan_decision_overrides")
@Data
public class LoanDecisionOverride {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "loan_application_id", nullable = false)
    private Long loanApplicationId;

    @Column(name = "original_decision", length = 32)
    private String originalDecision;

    @Column(name = "new_decision", length = 32)
    private String newDecision;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @Column(name = "overridden_by", length = 128)
    private String overriddenBy;

    @Column(name = "notify_findoc", nullable = false)
    private boolean notifyFindoc;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;
}
