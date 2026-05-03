package backend.backend.Dtos;

public record BuisnessLoggingResponseDto (
        Long id,
        String username,
        String action,
        String details,
        java.time.Instant timestamp
){}
