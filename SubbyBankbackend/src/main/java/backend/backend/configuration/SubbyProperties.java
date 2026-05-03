package backend.backend.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed binding for the {@code subby.*} section in application.yml — topic names,
 * queue names, S3 bucket, and outbox tuning. Injected wherever the messaging or
 * storage layers need a configurable name instead of a hardcoded string.
 */
@ConfigurationProperties(prefix = "subby")
public record SubbyProperties(
        S3 s3,
        Topics topics,
        Queues queues,
        Outbox outbox,
        Findoc findoc
) {
    public record S3(String bucket, long presignedTtlSeconds, long maxUploadBytes) {}

    public record Topics(
            String kycEvents,
            String loanEvents,
            String notifications,
            String riskRequested
    ) {}

    public record Queues(
            String kycSubmitted,
            String kycFindocResults,
            String kycDecision,
            String loanSubmitted,
            String loanFindocResults,
            String loanRiskResults,
            String loanDecision,
            String emailNotify,
            String smsNotify,
            String auditLog,
            String passwordResetEmail,
            String adminLoanPending,
            String adminKycReview,
            String welcomeEmail,
            String transactionEmail,
            String loanDisbursedEmail,
            String passwordChangedEmail
    ) {}

    public record Outbox(
            long pollIntervalMs,
            int batchSize,
            int maxAttempts,
            int maxEventPayloadBytes
    ) {}

    public record Findoc(String baseUrl, String apiKey, int timeoutSeconds) {}
}
