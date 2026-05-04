package backend.backend.service;

import backend.backend.model.BankAccount;
import backend.backend.model.KycStatus;
import backend.backend.model.Transaction;
import backend.backend.model.User;
import backend.backend.repository.BankAccountRepository;
import backend.backend.repository.IdempotencyRepository;
import backend.backend.repository.TransactionRepository;
import backend.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Guards two P0 fixes that live in {@link BankService#transfer}:
 *   - idempotency: a redelivered transfer with the same key returns the prior
 *     transaction instead of debiting the sender twice.
 *   - atomicity: when receiver-side work fails, the sender debit rolls back so
 *     funds are never left in limbo.
 */
@SpringBootTest(properties = "spring.profiles.active=test")
@Sql(scripts = "classpath:h2-schema-fixup.sql", executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
class BankServiceTest {

    @Autowired BankService bankService;
    @Autowired UserRepository userRepo;
    @Autowired BankAccountRepository bankRepo;
    @Autowired TransactionRepository txRepo;
    @Autowired IdempotencyRepository idempotencyRepo;
    @Autowired PasswordEncoder passwordEncoder;

    @MockBean TransactionService transactionService;

    private User sender;
    private BankAccount senderAcct;
    private BankAccount receiverAcct;
    private static final String PASSWORD = "secret";

    @BeforeEach
    void seed() {
        idempotencyRepo.deleteAll();
        txRepo.deleteAll();
        bankRepo.deleteAll();
        userRepo.deleteAll();

        String uniq = UUID.randomUUID().toString().substring(0, 8);
        sender = newUser("sender_" + uniq, PASSWORD);
        sender = userRepo.save(sender);
        User receiver = newUser("receiver_" + uniq, PASSWORD);
        receiver = userRepo.save(receiver);

        senderAcct = newAcct(sender, "ACC-S-" + uniq, 10_000.0);
        receiverAcct = newAcct(receiver, "ACC-R-" + uniq, 0.0);
        senderAcct = bankRepo.save(senderAcct);
        receiverAcct = bankRepo.save(receiverAcct);


        when(transactionService.checkFraud(any(Transaction.class)))
                .thenAnswer(inv -> {
                    Transaction t = inv.getArgument(0);
                    t.setIs_fraud(0);
                    t.setFraud_probability(0.0);
                    return txRepo.save(t);
                });
    }

    @Test
    @DisplayName("idempotent transfer: same key returns same Transaction.id, sender debited only once")
    void idempotent_transfer_returns_same_transaction() {
        String key = "idem-" + UUID.randomUUID();

        Transaction first = bankService.transfer(key, sender.getUsername(), sender.getId(),
                senderAcct.getAccountNumber(), receiverAcct.getAccountNumber(), 1_000.0, PASSWORD);
        Transaction second = bankService.transfer(key, sender.getUsername(), sender.getId(),
                senderAcct.getAccountNumber(), receiverAcct.getAccountNumber(), 1_000.0, PASSWORD);

        assertThat(second.getId()).isEqualTo(first.getId());
        BankAccount after = bankRepo.findByAccountNumber(senderAcct.getAccountNumber()).orElseThrow();
        assertThat(after.getBalance()).isEqualTo(9_000.0);
    }

    @Test
    @DisplayName("transfer atomicity: when fraud check throws, sender balance is rolled back")
    void transfer_atomicity_on_receiver_failure() {
        when(transactionService.checkFraud(any(Transaction.class)))
                .thenThrow(new RuntimeException("simulated downstream failure"));

        String key = "fail-" + UUID.randomUUID();

        assertThatThrownBy(() -> bankService.transfer(key, sender.getUsername(), sender.getId(),
                senderAcct.getAccountNumber(), receiverAcct.getAccountNumber(), 1_000.0, PASSWORD))
                .isInstanceOf(RuntimeException.class);

        BankAccount after = bankRepo.findByAccountNumber(senderAcct.getAccountNumber()).orElseThrow();
        assertThat(after.getBalance())
                .as("sender balance must remain at the pre-transfer amount after rollback")
                .isEqualTo(10_000.0);
        assertThat(idempotencyRepo.findById(key))
                .as("idempotency key must NOT be persisted on rollback")
                .isEmpty();
    }

    private User newUser(String username, String password) {
        User u = new User();
        u.setUsername(username);
        u.setFirstname("F");
        u.setLastname("L");
        u.setEmail(username + "@example.com");
        u.setMobile("9" + Long.toString(System.nanoTime()).substring(0, 9));
        u.setPassword(passwordEncoder.encode(password));
        u.setRole("USER");
        u.setKycStatus(KycStatus.KYC_APPROVED);
        u.setAccountActive(true);
        u.setPanNumber("ABCDE1234F");
        u.setAadhaarNumber("123412341234");
        u.setDob(LocalDate.of(1998, 3, 15));
        return u;
    }

    private BankAccount newAcct(User u, String accountNumber, double balance) {
        BankAccount a = new BankAccount();
        a.setUser(u);
        a.setAccountNumber(accountNumber);
        a.setBalance(balance);
        a.setType("SAVINGS");
        a.setVerified(true);
        return a;
    }
}
