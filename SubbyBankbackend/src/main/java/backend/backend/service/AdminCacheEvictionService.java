package backend.backend.service;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;

/**
 * Centralised Redis-cache invalidation hooks for admin-driven mutations.
 * Cache annotations live on service methods (Spring AOP advises bean
 * methods, not controller methods directly). Call from controllers / other
 * services after a state change so subsequent reads pull fresh data.
 */
@Service
public class AdminCacheEvictionService {

    /**
     * Invoke after any loan-state mutation that should make the admin loan
     * list, the user-pending list, and the user-approved list re-query.
     */
    @Caching(evict = {
            @CacheEvict(value = "banking:admin:loans:list", allEntries = true),
            @CacheEvict(value = "banking:loans:list", allEntries = true),
            @CacheEvict(value = "banking:loans:pending", allEntries = true),
            @CacheEvict(value = "banking:loans:userApproved", allEntries = true)
    })
    public void evictLoanCaches() {

    }

    /**
     * Invoke after a KYC-status mutation (admin override). Drops the admin
     * KYC list cache and the per-user/users-list caches so the next read
     * sees the new status.
     */
    @Caching(evict = {
            @CacheEvict(value = "banking:admin:kyc:users", allEntries = true),
            @CacheEvict(value = "banking:user:byId", allEntries = true),
            @CacheEvict(value = "banking:users:list", allEntries = true)
    })
    public void evictKycCaches() {

    }
}
