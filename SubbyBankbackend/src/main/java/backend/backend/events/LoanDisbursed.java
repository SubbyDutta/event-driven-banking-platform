package backend.backend.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@EventType("LoanDisbursed")
public class LoanDisbursed extends DomainEvent {

    private final String userId;
    private final String email;
    private final String loanAppId;
    private final BigDecimal amount;
    private final String accountNumberMasked;
    private final String occurredAtIso;

    @JsonCreator
    public LoanDisbursed(
            @JsonProperty("eventId") UUID eventId,
            @JsonProperty("occurredAt") Instant occurredAt,
            @JsonProperty("schemaVersion") int schemaVersion,
            @JsonProperty("correlationId") String correlationId,
            @JsonProperty("userId") String userId,
            @JsonProperty("email") String email,
            @JsonProperty("loanAppId") String loanAppId,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("accountNumberMasked") String accountNumberMasked,
            @JsonProperty("occurredAtIso") String occurredAtIso) {
        super(eventId, occurredAt, schemaVersion, correlationId);
        this.userId = userId;
        this.email = email;
        this.loanAppId = loanAppId;
        this.amount = amount;
        this.accountNumberMasked = accountNumberMasked;
        this.occurredAtIso = occurredAtIso;
    }

    public static LoanDisbursed forLoan(String userId, String email, String loanAppId,
                                        BigDecimal amount, String accountNumberMasked,
                                        Instant occurredAt) {
        return new LoanDisbursed(null, null, 1, "loan-" + loanAppId,
                userId, email, loanAppId, amount, accountNumberMasked,
                occurredAt == null ? null : occurredAt.toString());
    }

    public String getUserId() { return userId; }
    public String getEmail() { return email; }
    public String getLoanAppId() { return loanAppId; }
    public BigDecimal getAmount() { return amount; }
    public String getAccountNumberMasked() { return accountNumberMasked; }
    public String getOccurredAtIso() { return occurredAtIso; }

    @Override public String aggregateType() { return "loan_application"; }
    @Override public String aggregateId() { return loanAppId; }
}
