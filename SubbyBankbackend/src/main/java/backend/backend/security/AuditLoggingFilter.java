package backend.backend.security;

import backend.backend.model.AuditLog;
import backend.backend.repository.AuditLogRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

@Component
public class AuditLoggingFilter extends OncePerRequestFilter {

    private final AuditLogRepository auditLogRepository;

    public AuditLoggingFilter(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();

        return uri.startsWith("/auth")

                || "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        long start = System.currentTimeMillis();

        try {
            filterChain.doFilter(request, response);
        } finally {
            try {
                var auth = SecurityContextHolder.getContext().getAuthentication();

                AuditLog log = new AuditLog();
                log.setUsername(auth != null ? auth.getName() : "ANONYMOUS");
                log.setEndpoint(request.getRequestURI());
                log.setMethod(request.getMethod());
                log.setStatusCode(response.getStatus());

                log.setTimestamp(LocalDateTime.now());

                CompletableFuture.runAsync(() -> auditLogRepository.save(log));

            } catch (Exception e) {

                LoggerFactory.getLogger(getClass())
                        .warn("Audit logging failed", e);
            }
        }
    }
}
