package backend.backend.controller;

import backend.backend.messaging.OutboxEvent;
import backend.backend.messaging.OutboxEventRepository;
import backend.backend.model.BankAccount;
import backend.backend.model.KycStatus;
import backend.backend.model.LoanApplication;
import backend.backend.model.LoanLifecycleStatus;
import backend.backend.model.LoanPurpose;
import backend.backend.model.Transaction;
import backend.backend.model.User;
import backend.backend.repository.BankAccountRepository;
import backend.backend.repository.LoanApplicationRepository;
import backend.backend.repository.LoanDecisionOverrideRepository;
import backend.backend.repository.UserRepository;
import backend.backend.service.TransactionService;
import backend.backend.service.loan.AdminLoanOverrideService;
import backend.backend.service.loan.LoanFinalizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Contract test for /api/admin/loans/{id}/override: APPROVE requires interestRate (in 0..50), REJECT ignores rate, both publish LoanFinalized.
 */
@SpringBootTest(properties = "spring.profiles.active=test")
@Sql(scripts = "classpath:h2-schema-fixup.sql", executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
class AdminLoanControllerOverrideTest {

    @Autowired AdminLoanOverrideService overrideService;
    @Autowired LoanFinalizationService finalizer;
    @Autowired UserRepository userRepo;
    @Autowired BankAccountRepository bankRepo;
    @Autowired LoanApplicationRepository loanRepo;
    @Autowired LoanDecisionOverrideRepository overrideRepo;
    @Autowired OutboxEventRepository outboxRepo;

    @MockBean TransactionService transactionService;

    private User user;
    private LoanApplication loan;

    @BeforeEach
    void seed() {
        outboxRepo.deleteAll();
        overrideRepo.deleteAll();
        loanRepo.deleteAll();
        bankRepo.deleteAll();
        userRepo.deleteAll();

        String uniq = UUID.randomUUID().toString().substring(0, 8);
        user = new User();
        user.setUsername("u_" + uniq);
        user.setFirstname("Test");
        user.setLastname("User");
        user.setEmail("u_" + uniq + "@example.com");
        user.setMobile("9" + Long.toString(System.nanoTime()).substring(0, 9));
        user.setPassword("x");
        user.setRole("USER");
        user.setKycStatus(KycStatus.KYC_APPROVED);
        user.setAccountActive(true);
        user.setPanNumber("ABCDE1234F");
        user.setAadhaarNumber("123412341234");
        user.setDob(LocalDate.of(1998, 3, 15));
        user = userRepo.save(user);

        BankAccount account = new BankAccount();
        account.setUser(user);
        account.setAccountNumber("ACC-" + uniq);
        account.setBalance(1_000.0);
        account.setType("SAVINGS");
        account.setVerified(true);
        bankRepo.save(account);

        loan = new LoanApplication();
        loan.setExternalId(UUID.randomUUID().toString());
        loan.setUserId(user.getId());
        loan.setUsername(user.getUsername());
        loan.setAmount(500_000);
        loan.setPurpose(LoanPurpose.MEDICAL);
        loan.setMonthsRemaining(6);
        loan.setLifecycleStatus(LoanLifecycleStatus.PENDING_ADMIN_DECISION);
        loan.setStatus("PENDING");
        loan.setSubmittedAt(Instant.now());
        loan = loanRepo.save(loan);

        when(transactionService.checkFraud(any(Transaction.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(transactionService.saveTransaction(any(Transaction.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("APPROVE without interestRate → validation error (400)")
    void approve_without_interest_rate_400() {
        AdminLoanOverrideService.Result r = overrideService.override(
                loan.getExternalId(), "APPROVE", "ok to approve", null, "admin1");

        assertThat(r.notFound).isFalse();
        assertThat(r.validationError).isNotNull().contains("interestRate is required");

        LoanApplication after = loanRepo.findById(loan.getId()).orElseThrow();
        assertThat(after.getLifecycleStatus()).isEqualTo(LoanLifecycleStatus.PENDING_ADMIN_DECISION);
        assertThat(loanFinalizedCount()).isZero();
    }

    @Test
    @DisplayName("APPROVE with valid interestRate → PENDING_USER_ACCEPTANCE; user accept disburses + LoanFinalized published")
    void approve_with_rate_parks_then_user_accept_finalizes() {
        AdminLoanOverrideService.Result r = overrideService.override(
                loan.getExternalId(), "APPROVE", "looks good",
                new BigDecimal("12.50"), "admin1");

        assertThat(r.validationError).isNull();
        assertThat(r.newDecision).isEqualTo("APPROVED");

        LoanApplication parked = loanRepo.findById(loan.getId()).orElseThrow();
        assertThat(parked.getLifecycleStatus()).isEqualTo(LoanLifecycleStatus.PENDING_USER_ACCEPTANCE);
        assertThat(parked.getInterestRate()).isEqualByComparingTo("12.50");
        assertThat(parked.getMonthlyEmi()).isGreaterThan(0.0);
        assertThat(loanFinalizedCount()).as("park does not finalize; LoanFinalized fires after user accepts").isZero();

        finalizer.finalize(parked, "APPROVED", "user accepted",
                parked.getInterestRate(), "user:" + user.getUsername());

        LoanApplication after = loanRepo.findById(loan.getId()).orElseThrow();
        assertThat(after.getLifecycleStatus()).isEqualTo(LoanLifecycleStatus.APPROVED);
        assertThat(loanFinalizedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("REJECT ignores interestRate → REJECTED + LoanFinalized published")
    void reject_ignores_rate_and_publishes() {
        AdminLoanOverrideService.Result r = overrideService.override(
                loan.getExternalId(), "REJECT", "fraud signal",
                new BigDecimal("12.00"), "admin1");

        assertThat(r.validationError).isNull();
        assertThat(r.newDecision).isEqualTo("REJECTED");

        LoanApplication after = loanRepo.findById(loan.getId()).orElseThrow();
        assertThat(after.getLifecycleStatus()).isEqualTo(LoanLifecycleStatus.REJECTED);
        assertThat(loanFinalizedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("APPROVE on loan with doc_reeval_result=REJECT (path F) → parked at PENDING_USER_ACCEPTANCE then APPROVED on user accept, override wins")
    void approve_on_doc_reeval_reject_wins() {
        loan.setLifecycleStatus(LoanLifecycleStatus.PENDING_ADMIN_DECISION);
        loan.setInterestRate(new BigDecimal("12.00"));
        loan.setDocReevalResult("REJECT");
        loan.setDocReevalReason("fraud signal in payslip");
        loan.setDocReevalRunNumber(2);
        loan.setDocReevalAt(Instant.now());
        loanRepo.save(loan);

        AdminLoanOverrideService.Result r = overrideService.override(
                loan.getExternalId(), "APPROVE", "override doc reeval — independent verification done",
                new BigDecimal("15.00"), "admin1");

        assertThat(r.validationError).isNull();

        LoanApplication parked = loanRepo.findById(loan.getId()).orElseThrow();
        assertThat(parked.getLifecycleStatus()).isEqualTo(LoanLifecycleStatus.PENDING_USER_ACCEPTANCE);
        assertThat(parked.getInterestRate()).isEqualByComparingTo("15.00");

        finalizer.finalize(parked, "APPROVED", "user accepted",
                parked.getInterestRate(), "user:" + user.getUsername());

        LoanApplication after = loanRepo.findById(loan.getId()).orElseThrow();
        assertThat(after.getLifecycleStatus()).isEqualTo(LoanLifecycleStatus.APPROVED);
        assertThat(loanFinalizedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("APPROVE with rate > 50 → validation error")
    void approve_rate_above_max_rejected() {
        AdminLoanOverrideService.Result r = overrideService.override(
                loan.getExternalId(), "APPROVE", "ok",
                new BigDecimal("99.00"), "admin1");
        assertThat(r.validationError).contains("less than or equal to 50");
    }

    @Test
    @DisplayName("APPROVE with rate <= 0 → validation error")
    void approve_rate_zero_rejected() {
        AdminLoanOverrideService.Result r = overrideService.override(
                loan.getExternalId(), "APPROVE", "ok",
                BigDecimal.ZERO, "admin1");
        assertThat(r.validationError).contains("greater than 0");
    }

    private long loanFinalizedCount() {
        return outboxRepo.findAll().stream()
                .map(OutboxEvent::getEventType)
                .filter("LoanFinalized"::equals)
                .count();
    }

    private long loanRiskRequestedCount() {
        return outboxRepo.findAll().stream()
                .map(OutboxEvent::getEventType)
                .filter("LoanRiskRequested"::equals)
                .count();
    }
}
