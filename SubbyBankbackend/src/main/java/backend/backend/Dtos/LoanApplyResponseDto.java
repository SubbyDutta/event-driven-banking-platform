package backend.backend.Dtos;

/**
 * 202-ACCEPTED response returned by {@code POST /api/loans/apply}. The client
 * polls {@link #statusUrl} to observe the pipeline progress.
 */
public record LoanApplyResponseDto(
        String loanAppId,
        String externalId,
        int tenureMonths,
        String lifecycleStatus,
        String statusUrl
) {}
