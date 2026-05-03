package backend.backend.repository;

import backend.backend.model.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface  TransactionRepository extends JpaRepository<Transaction, Long> {

    Optional<Transaction> findTopByUserIdOrderByTimestampDesc(int userId);

    Page<Transaction> findByUserIdOrderByTimestampDesc(int userId, Pageable pageable);

    Page<Transaction> findAllByOrderByTimestampDesc(Pageable pageable);

    List<Transaction> findByUserId(int userId);

    List<Transaction> findBySenderAccountOrReceiverAccountOrderByTimestampDesc(
            String senderAccount,
            String receiverAccount
    );
    @Query("SELECT t FROM Transaction t WHERE t.userId = :userId ORDER BY t.timestamp DESC")
    List<Transaction> findRecentTransactions(@Param("userId") int userId, Pageable pageable);

    @Query("""
    SELECT COUNT(t)
    FROM Transaction t
    WHERE t.userId = :userId
      AND t.timestamp >= COALESCE(:from, t.timestamp)
      AND t.timestamp <= COALESCE(:to, t.timestamp)
      AND t.amount >= COALESCE(:minAmount, t.amount)
      AND t.amount <= COALESCE(:maxAmount, t.amount)
""")
    long countByUserWithFilters(
            @Param("userId") int userId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("minAmount") Double minAmount,
            @Param("maxAmount") Double maxAmount
    );

    @Query("""
   SELECT t
   FROM Transaction t
   WHERE t.userId = :userId
     AND t.timestamp >= COALESCE(:from, t.timestamp)
     AND t.timestamp <= COALESCE(:to, t.timestamp)
     AND t.amount >= COALESCE(:minAmount, t.amount)
     AND t.amount <= COALESCE(:maxAmount, t.amount)
   ORDER BY t.timestamp DESC
""")
    Page<Transaction> searchByUserWithFilters(
            @Param("userId") int userId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("minAmount") Double minAmount,
            @Param("maxAmount") Double maxAmount,
            Pageable pageable
    );}
