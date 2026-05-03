package backend.backend.requests_response;

public record PagedResponse<T>(

        java.util.List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean last
) {}
