package backend.backend.repository;

import backend.backend.model.BankPool;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BankPoolRepository extends JpaRepository<BankPool, Long> {
    List<BankPool> findAll();
}
