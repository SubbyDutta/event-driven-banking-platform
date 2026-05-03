package backend.backend.repository;

import backend.backend.model.LoanApplication;
import backend.backend.model.LoanLifecycleStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface LoanApplicationRepository
        extends JpaRepository<LoanApplication, Long>, JpaSpecificationExecutor<LoanApplication> {

    List<LoanApplication> findByUsername(String username);
    List<LoanApplication> findByUsernameAndStatus(String username, String status);

    @Query("""
           SELECT l FROM LoanApplication l
           WHERE (:username IS NULL OR LOWER(l.username) LIKE LOWER(CONCAT('%', :username, '%')))
           AND (:minAmount IS NULL OR l.amount >= :minAmount)
           """)
    Page<LoanApplication> searchLoans(
            @Param("username") String username,
            @Param("minAmount") Double minAmount,
            Pageable pageable
    );

    Page<LoanApplication> findByApprovedFalseAndStatusNotIgnoreCase(String status, Pageable pageable);

    Optional<LoanApplication> findByExternalId(String externalId);

    List<LoanApplication> findByUserIdOrderByIdDesc(Long userId);

    boolean existsByUserIdAndLifecycleStatusIn(Long userId, Collection<LoanLifecycleStatus> statuses);

    long countByLifecycleStatus(LoanLifecycleStatus status);

    Page<LoanApplication> findByLifecycleStatus(LoanLifecycleStatus status, Pageable pageable);

    Page<LoanApplication> findByUserIdOrderByIdDesc(Long userId, Pageable pageable);
}
