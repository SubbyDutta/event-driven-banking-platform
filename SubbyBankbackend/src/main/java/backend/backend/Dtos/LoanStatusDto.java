package backend.backend.Dtos;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * Polling response for {@code GET /api/loans/{loanAppId}/status}. Drives the
 * frontend pipeline-progress tracker and terminal-state cards. Fields are
 * nullable — the client expects partial shapes while the pipeline runs.
 */
public record LoanStatusDto(
        String loanAppId,
        String lifecycleStatus,
        double amount,
        int tenureMonths,
        String purpose,
        Instant submittedAt,
        Instant decidedAt,

        String decision,
        String decisionReason,

        BigDecimal fraudScore,
        Integer creditScore,
        String riskBand,
        BigDecimal riskProbability,
        BigDecimal interestRate,

        Double monthlyEmi,
        LocalDateTime firstDueDate,
        Long loanId,

        JsonNode loanReportPreview
) {}
