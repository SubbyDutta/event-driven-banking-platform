package backend.backend.messaging;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Multi-replica safe claim: select unpublished rows whose lease is missing
     * or expired, and lock them with {@code FOR UPDATE SKIP LOCKED}. Two relays
     * never see the same row in the same poll cycle. The caller is expected to
     * stamp {@code lease_id} + {@code lease_expires_at} on each returned row in
     * the same transaction so the lock surface narrows to "no row, no contention."
     *
     * <p>Locks release at commit, so per-row publish updates run in their own
     * transactions without deadlocking.
     */
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE published_at IS NULL
              AND attempt_count < :maxAttempts
              AND (lease_id IS NULL OR lease_expires_at < NOW())
            ORDER BY id
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> claimBatch(@Param("maxAttempts") int maxAttempts,
                                 @Param("batchSize") int batchSize);

    long countByPublishedAtIsNullAndAttemptCountGreaterThanEqual(int attemptCount);

    List<OutboxEvent> findByPublishedAtIsNullAndAttemptCountGreaterThanEqual(
            int attemptCount, Pageable pageable);
}
