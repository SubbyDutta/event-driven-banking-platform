package backend.backend.repository;

import backend.backend.model.LoanRepayment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface LoanRepaymentRepository extends JpaRepository<LoanRepayment, Long> {
    List<LoanRepayment> findByUsernameOrderByPaymentDateDesc(String username);
    List<LoanRepayment> findByLoanIdOrderByPaymentDateDesc(Long loanId);
    Page<LoanRepayment> findAllByOrderByPaymentDateDesc(Pageable pageable);
    @Modifying
    @Transactional
    @Query("DELETE FROM LoanRepayment l WHERE l.username = :username")
    int deleteAllByUsername(@Param("username") String username);

}