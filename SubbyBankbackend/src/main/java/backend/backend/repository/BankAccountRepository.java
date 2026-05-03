package backend.backend.repository;

import backend.backend.model.BankAccount;
import backend.backend.model.Transaction;
import backend.backend.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BankAccountRepository extends JpaRepository<BankAccount, Long> {
    Optional<BankAccount> findByUser(User user);
    Optional<BankAccount> findByAccountNumber(String accountNumber);
    Page<BankAccount> findAll(Pageable pageable);
    Optional<BankAccount> findByUserUsername(String username);

   Optional<BankAccount> findById(Long id);
    Optional<BankAccount> findByUserId(Long id);

}
