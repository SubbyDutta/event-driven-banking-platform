package backend.backend.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private final JwtUtil jwtService;

    private final Map<String, Bucket> cache = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String path = request.getRequestURI();
        String key = resolveKey(request);
        Bucket bucket = cache.computeIfAbsent(
                key + ":" + path,
                k -> createBucket(path)
        );

        if (bucket.tryConsume(1)) {
            filterChain.doFilter(request, response);
        } else {
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write("""
                {
                  "error": "Too many requests",
                  "message": "Rate limit exceeded. Please try again later."
                }
            """);
        }
    }

    private String resolveKey(HttpServletRequest request) {

        String authHeader = request.getHeader("Authorization");

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            try {
                String token = authHeader.substring(7);
                return "USER_" + jwtService.extractUsername(token);
            } catch (Exception e) {
                return "ANON_" + request.getRemoteAddr();
            }
        }

        return "ANON_" + request.getRemoteAddr();
    }

    private Bucket createBucket(String path) {

        if (path.startsWith("/api/auth/login")) {
            return Bucket.builder()
                    .addLimit(Bandwidth.classic(
                            2,
                            Refill.intervally(5, Duration.ofMinutes(1))
                    ))
                    .build();
        }

        if (path.contains("/transfer")) {
            return Bucket.builder()
                    .addLimit(Bandwidth.classic(
                            3,
                            Refill.intervally(3, Duration.ofMinutes(1))
                    ))
                    .build();
        }

        return Bucket.builder()
                .addLimit(Bandwidth.classic(
                        30,
                        Refill.intervally(30, Duration.ofMinutes(1))
                ))
                .build();
    }
}
