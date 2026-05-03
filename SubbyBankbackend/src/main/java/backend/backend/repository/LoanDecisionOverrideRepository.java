package backend.backend.repository;

import backend.backend.model.LoanDecisionOverride;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LoanDecisionOverrideRepository extends JpaRepository<LoanDecisionOverride, Long> {

    List<LoanDecisionOverride> findByLoanApplicationIdOrderByIdDesc(Long loanApplicationId);

    Optional<LoanDecisionOverride> findFirstByLoanApplicationIdAndOverriddenByAndNewDecisionOrderByIdDesc(
            Long loanApplicationId, String overriddenBy, String newDecision);
}
