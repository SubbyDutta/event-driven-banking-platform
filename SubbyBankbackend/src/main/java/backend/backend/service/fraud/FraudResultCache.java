package backend.backend.service.fraud;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import backend.backend.model.Transaction;

/**
 * In-process Caffeine cache for recent fraud decisions. Local, not Redis —
 * the goal is to absorb retried/duplicate transfers within a single instance
 * before they ever leave the JVM. A 60s TTL keeps the cache useful for retry
 * storms (frontend retries, double-clicks, idempotent replays) without
 * masking model updates that take effect at the next miss.
 *
 * <p>Cache key is intentionally fuzzy on amount (bucketed to the nearest 100)
 * so a near-identical retry hits, but a meaningfully different amount misses.
 */
@Component
public class FraudResultCache {

    private final Cache<String, FraudCheckResult> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(60))
            .maximumSize(10_000)
            .build();

    public FraudCheckResult getOrLoad(Transaction t, Supplier<FraudCheckResult> loader) {
        String key = key(t);
        FraudCheckResult hit = cache.getIfPresent(key);
        if (hit != null) {
            return hit;
        }
        FraudCheckResult fresh = loader.get();

        if (fresh.status() == FraudCheckResult.Status.CHECKED) {
            cache.put(key, fresh);
        }
        return fresh;
    }

    public Optional<FraudCheckResult> peek(Transaction t) {
        return Optional.ofNullable(cache.getIfPresent(key(t)));
    }

    public void invalidate(Transaction t) {
        cache.invalidate(key(t));
    }

    private static String key(Transaction t) {
        long amountBucket = (long) Math.floor(t.getAmount() / 100.0);
        return t.getUserId() + ":" + t.getSenderAccount() + ":" + t.getReceiverAccount()
                + ":" + amountBucket + ":" + t.getHour();
    }
}
