package backend.backend.repository;

import backend.backend.model.PendingLoanEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PendingLoanEventRepository extends JpaRepository<PendingLoanEvent, Long> {

    List<PendingLoanEvent> findByLoanIdOrderByIdAsc(Long loanId);
}
