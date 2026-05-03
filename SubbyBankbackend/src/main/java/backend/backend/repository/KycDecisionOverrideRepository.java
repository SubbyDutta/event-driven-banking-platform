package backend.backend.repository;

import backend.backend.model.KycDecisionOverride;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KycDecisionOverrideRepository extends JpaRepository<KycDecisionOverride, Long> {

    List<KycDecisionOverride> findByUserIdOrderByCreatedAtDesc(Long userId);
}
