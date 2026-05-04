package backend.backend.service.fraud;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import backend.backend.configuration.FraudMlProperties;
import backend.backend.model.Transaction;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import jakarta.annotation.PostConstruct;

/**
 * HTTP client for FraudPython's sync /predict endpoint. One bean owns the
 * outbound call so the circuit breaker, bulkhead, retry, and fallback are
 * applied uniformly — without this, every caller would have to remember to
 * wrap the RestTemplate by hand and most wouldn't.
 *
 * <p>Resilience layers applied (configured under {@code resilience4j.*.fraud}
 * in application.yml):
 * <ul>
 *   <li><b>HTTP timeouts</b> — connect 500ms / read 1500ms on the dedicated
 *       {@code fraudRestTemplate}. Acts as the per-call time budget without
 *       requiring async @TimeLimiter.</li>
 *   <li><b>@Bulkhead</b> — semaphore-bounded; caps concurrent fraud calls
 *       so a hung downstream can't drain Tomcat's thread pool.</li>
 *   <li><b>@Retry</b> — short retry on transient I/O failures (timeout, 5xx).
 *       Skipped on bulkhead-full and circuit-open.</li>
 *   <li><b>@CircuitBreaker</b> — opens after configured failure rate; once
 *       open, calls short-circuit straight to {@link #fallback} until the
 *       half-open probe succeeds.</li>
 *   <li><b>Fallback policy</b> — degraded mode for low-risk transfers,
 *       hard-fail for high-risk. See {@link FraudCheckResult#status}.</li>
 * </ul>
 */
@Component
public class FraudClient {

    private static final Logger log = LoggerFactory.getLogger(FraudClient.class);

    private final FraudMlProperties properties;
    private final RestTemplate restTemplate;
    private String predictUrl;

    public FraudClient(FraudMlProperties properties,
                       @Qualifier("fraudRestTemplate") RestTemplate restTemplate) {
        this.properties = properties;
        this.restTemplate = restTemplate;
    }

    @PostConstruct
    void init() {

        String url = properties.geturl();
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("fraud.url is not configured");
        }
        this.predictUrl = url.endsWith("/predict") ? url : url.replaceAll("/$", "") + "/predict";
        log.info("FraudClient initialised with predictUrl={}", predictUrl);
    }

    /**
     * Score one transaction. The input/output schema mirrors what the legacy
     * inline code in {@code TransactionService.checkFraud} sent — we don't
     * reshape the payload here so the FraudPython contract is unchanged.
     */
    @CircuitBreaker(name = "fraud", fallbackMethod = "fallback")
    @Bulkhead(name = "fraud")
    @Retry(name = "fraud")
    public FraudCheckResult score(Transaction transaction) {

        Map<String, Object> features = new HashMap<>();
        features.put("amount", transaction.getAmount());
        features.put("hour", transaction.getHour());
        features.put("is_foreign", transaction.getIsForeign());
        features.put("is_high_risk", transaction.getIsHighRisk());
        features.put("userId", transaction.getUserId());
        features.put("balance", transaction.getBalance());
        features.put("avg_amount", transaction.getAvg_amount());

        Map<String, Object> body = new HashMap<>();
        body.put("transactions", Collections.singletonList(features));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String cid = MDC.get("correlationId");
        if (cid != null && !cid.isBlank()) {
            headers.add("X-Correlation-Id", cid);
        }

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        @SuppressWarnings("rawtypes")
        ResponseEntity<Map> response = restTemplate.postForEntity(predictUrl, entity, Map.class);

        if (response.getBody() == null) {
            throw new IllegalStateException("fraud-python returned empty body");
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) response.getBody().get("results");
        if (results == null || results.isEmpty()) {
            throw new IllegalStateException("fraud-python returned no results");
        }
        Map<String, Object> r = results.get(0);

        double prob = toDouble(r.get("fraud_probability"));
        int isFraud = toInt(r.get("is_fraud"));

        return FraudCheckResult.checked(prob, isFraud);
    }

    /**
     * Fallback when the fraud service is unavailable (timeout, 5xx, circuit
     * open, bulkhead full). The policy is intentionally split:
     * <ul>
     *   <li>Low-risk transfer (small amount, known account): allow with
     *       status=DEGRADED so an audit can find these later. Refusing every
     *       transfer when fraud is down makes the outage worse for users.</li>
     *   <li>High-risk transfer (large amount, foreign, etc.): hard-fail —
     *       a missed fraud check on a big transfer is the costlier mistake.</li>
     * </ul>
     */
    @SuppressWarnings("unused")
    private FraudCheckResult fallback(Transaction transaction, Throwable t) {
        boolean foreignOrHigh = transaction.getIsForeign() == 1
                || transaction.getIsHighRisk() == 1;

        if (foreignOrHigh) {
            log.error("fraud-python unavailable; rejecting foreign/high-risk transfer (amount={}, cause={})",
                    transaction.getAmount(), t.toString());
            return FraudCheckResult.unavailable(t);
        }

        log.warn("fraud-python unavailable; allowing transfer in DEGRADED mode (amount={}, cause={})",
                transaction.getAmount(), t.toString());
        return FraudCheckResult.degraded();
    }

    private static double toDouble(Object o) {
        if (o == null) return 0.0;
        if (o instanceof Number n) return n.doubleValue();
        return Double.parseDouble(o.toString());
    }

    private static int toInt(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.intValue();
        return Integer.parseInt(o.toString());
    }
}
