package backend.backend.repository;

import backend.backend.model.KycStatus;
import backend.backend.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);

    boolean existsByUsername(String admin);
    Optional<User> findByEmail(String email);
    Optional<User> findByMobile(String mobile);
    List<User> findByRole(String role);
    Page<User> findAll(Pageable pageable);

    /**
     * Duplicate-document guard. {@link backend.backend.configuration.PiiConverter}
     * is deterministic (same plaintext → same ciphertext), so these derived
     * queries go through the converter on the way in and compare ciphertext
     * on the way out — i.e. a plaintext Aadhaar on one user matches the
     * encrypted column on another. The {@code IdNot} keeps the current user
     * from matching themselves on KYC retry. Backed by V3 UNIQUE constraints
     * as belt-and-suspenders if the Java-side check races.
     */
    boolean existsByAadhaarNumberAndIdNot(String aadhaarNumber, Long id);
    boolean existsByPanNumberAndIdNot(String panNumber, Long id);

    /**
     * Admin KYC listing. Filter by status and optional free-text {@code q}
     * (matched case-insensitively against username, email, mobile, first and last
     * name). A null {@code kycStatus} means "any status". A blank/null {@code q}
     * short-circuits the LIKE branch.
     */
    @Query("""
            SELECT u FROM User u
             WHERE (:kycStatus IS NULL OR u.kycStatus = :kycStatus)
               AND (
                    :q IS NULL OR :q = ''
                    OR LOWER(u.username)  LIKE LOWER(CONCAT('%', :q, '%'))
                    OR LOWER(u.email)     LIKE LOWER(CONCAT('%', :q, '%'))
                    OR LOWER(u.mobile)    LIKE LOWER(CONCAT('%', :q, '%'))
                    OR LOWER(u.firstname) LIKE LOWER(CONCAT('%', :q, '%'))
                    OR LOWER(u.lastname)  LIKE LOWER(CONCAT('%', :q, '%'))
                   )
            """)
    Page<User> searchForKycAdmin(@Param("kycStatus") KycStatus kycStatus,
                                 @Param("q") String q,
                                 Pageable pageable);
}
