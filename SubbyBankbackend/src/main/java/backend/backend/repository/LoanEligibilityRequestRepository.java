package backend.backend.repository;

import backend.backend.model.LoanEligibilityRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LoanEligibilityRequestRepository extends JpaRepository<LoanEligibilityRequest, Long> {}
