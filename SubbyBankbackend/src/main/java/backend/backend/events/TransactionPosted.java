package backend.backend.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@EventType("TransactionPosted")
public class TransactionPosted extends DomainEvent {

    public enum Direction { CREDIT, DEBIT }

    public enum Category { TRANSFER, TOPUP, DISBURSEMENT, EMI, OTHER }

    private final String userId;
    private final String email;
    private final Direction direction;
    private final BigDecimal amount;
    private final String currency;
    private final String txnRef;
    private final BigDecimal balanceAfter;
    private final String counterparty;
    private final Category category;
    private final String occurredAtIso;

    @JsonCreator
    public TransactionPosted(
            @JsonProperty("eventId") UUID eventId,
            @JsonProperty("occurredAt") Instant occurredAt,
            @JsonProperty("schemaVersion") int schemaVersion,
            @JsonProperty("correlationId") String correlationId,
            @JsonProperty("userId") String userId,
            @JsonProperty("email") String email,
            @JsonProperty("direction") Direction direction,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("currency") String currency,
            @JsonProperty("txnRef") String txnRef,
            @JsonProperty("balanceAfter") BigDecimal balanceAfter,
            @JsonProperty("counterparty") String counterparty,
            @JsonProperty("category") Category category,
            @JsonProperty("occurredAtIso") String occurredAtIso) {
        super(eventId, occurredAt, schemaVersion, correlationId);
        this.userId = userId;
        this.email = email;
        this.direction = direction;
        this.amount = amount;
        this.currency = currency;
        this.txnRef = txnRef;
        this.balanceAfter = balanceAfter;
        this.counterparty = counterparty;
        this.category = category;
        this.occurredAtIso = occurredAtIso;
    }

    public static TransactionPosted forUser(String userId, String email, Direction direction,
                                            BigDecimal amount, String currency, String txnRef,
                                            BigDecimal balanceAfter, String counterparty,
                                            Category category, Instant occurredAt) {
        return new TransactionPosted(null, null, 1, "txn-" + txnRef,
                userId, email, direction, amount, currency, txnRef, balanceAfter,
                counterparty, category,
                occurredAt == null ? null : occurredAt.toString());
    }

    public String getUserId() { return userId; }
    public String getEmail() { return email; }
    public Direction getDirection() { return direction; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getTxnRef() { return txnRef; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public String getCounterparty() { return counterparty; }
    public Category getCategory() { return category; }
    public String getOccurredAtIso() { return occurredAtIso; }

    @Override public String aggregateType() { return "transaction"; }
    @Override public String aggregateId() { return txnRef; }
}
