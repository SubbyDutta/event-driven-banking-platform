package backend.backend.messaging.consumer.loan;

import backend.backend.messaging.IdempotencyGuard;
import backend.backend.messaging.OutboxEvent;
import backend.backend.messaging.OutboxEventRepository;
import backend.backend.model.KycStatus;
import backend.backend.model.LoanApplication;
import backend.backend.model.LoanLifecycleStatus;
import backend.backend.model.LoanPurpose;
import backend.backend.model.User;
import backend.backend.repository.LoanApplicationRepository;
import backend.backend.repository.PendingLoanEventRepository;
import backend.backend.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Scenarios A–I from the admin loan-inspect refactor: first-pass, replay, and reverse-replay paths.
 * Each test asserts final lifecycleStatus, outbox event counts, and doc_reeval_* persistence.
 */
@SpringBootTest(properties = "spring.profiles.active=test")
@Sql(scripts = "classpath:h2-schema-fixup.sql", executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
class LoanFindocResultConsumerReplayTest {

    @Autowired ObjectMapper om;
    @Autowired LoanFindocResultConsumer findocConsumer;
    @Autowired UserRepository userRepo;
    @Autowired LoanApplicationRepository loanRepo;
    @Autowired PendingLoanEventRepository pendingRepo;
    @Autowired OutboxEventRepository outboxRepo;

    @MockBean IdempotencyGuard idempotencyGuard;

    private User user;
    private LoanApplication loan;

    @BeforeEach
    void seed() {
        outboxRepo.deleteAll();
        pendingRepo.deleteAll();
        loanRepo.deleteAll();
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
        user.setCreditScore(780);
        user = userRepo.save(user);

        loan = new LoanApplication();
        loan.setExternalId(UUID.randomUUID().toString());
        loan.setUserId(user.getId());
        loan.setUsername(user.getUsername());
        loan.setAmount(500_000);
        loan.setPurpose(LoanPurpose.MEDICAL);
        loan.setMonthsRemaining(6);
        loan.setLifecycleStatus(LoanLifecycleStatus.DOCS_UNDER_REVIEW);
        loan.setStatus("PENDING");
        loan.setSubmittedAt(Instant.now());
        loan = loanRepo.save(loan);

        when(idempotencyGuard.claim(ArgumentMatchers.any(UUID.class), ArgumentMatchers.anyString()))
                .thenReturn(true);
        when(idempotencyGuard.claim(ArgumentMatchers.anyString(), ArgumentMatchers.anyString()))
                .thenReturn(true);
    }

    @Test
    @DisplayName("A. first-pass verified: DOCS_UNDER_REVIEW → DOCS_VERIFIED + LoanRiskRequested once")
    void scenarioA_first_pass_verified() throws Exception {
        findocConsumer.handle(findocEvent("verified", null, null, null));

        LoanApplication after = loanRepo.findById(loan.getId()).orElseThrow();
        assertThat(after.getLifecycleStatus()).isEqualTo(LoanLifecycleStatus.DOCS_VERIFIED);
        assertReevalNull(after);
        assertOutboxCounts(1, 0, 0);
    }

    @Test
    @DisplayName("B. first-pass rejected: DOCS_UNDER_REVIEW → DOCS_REJECTED, no outbox events")
    void scenarioB_first_pass_rejected_no_replay() throws Exception {
        findocConsumer.handle(findocEvent("rejected", null, null, null));

        LoanApplication after = loanRepo.findById(loan.getId()).orElseThrow();
        assertThat(after.getLifecycleStatus()).isEqualTo(LoanLifecycleStatus.DOCS_REJECTED);
        assertReevalNull(after);
        assertOutboxCounts(0, 0, 0);
    }

    @Test
    @DisplayName("C. replay flips reject → approve: DOCS_REJECTED → DOCS_VERIFIED + LoanRiskRequested, doc_reeval=APPROVE")
    void scenarioC_replay_approves_triggers_ml() throws Exception {
        loan.setLifecycleStatus(LoanLifecycleStatus.DOCS_REJECTED);
        loanRepo.save(loan);

        findocConsumer.handle(findocEvent("approve", true, null, 2));

        LoanApplication after = loanRepo.findById(loan.getId()).orElseThrow();
        assertThat(after.getLifecycleStatus()).isEqualTo(LoanLifecycleStatus.DOCS_VERIFIED);
        assertThat(after.getDocReevalResult()).isEqualTo("APPROVE");
        assertThat(after.getDocReevalRunNumber()).isEqualTo(2);
        assertThat(after.getDocReevalAt()).isNotNull();
        assertOutboxCounts(1, 0, 0);
    }

    @Test
    @DisplayName("D. replay re-rejected: stays DOCS_REJECTED, no outbox events, doc_reeval=REJECT")
    void scenarioD_replay_stays_rejected() throws Exception {
        loan.setLifecycleStatus(LoanLifecycleStatus.DOCS_REJECTED);
        loanRepo.save(loan);

        findocConsumer.handle(findocEvent("rejected", true, null, 2));

        LoanApplication after = loanRepo.findById(loan.getId()).orElseThrow();
        assertThat(after.getLifecycleStatus()).isEqualTo(LoanLifecycleStatus.DOCS_REJECTED);
        assertThat(after.getDocReevalResult()).isEqualTo("REJECT");
        assertThat(after.getDocReevalRunNumber()).isEqualTo(2);
        assertOutboxCounts(0, 0, 0);
    }

    @Test
    @DisplayName("E. replay → manual_review from DOCS_REJECTED: lifecycle MANUAL_REVIEW + LoanPendingAdminDecision")
    void scenarioE_replay_to_manual_review() throws Exception {
        loan.setLifecycleStatus(LoanLifecycleStatus.DOCS_REJECTED);
        loanRepo.save(loan);

        findocConsumer.handle(findocEvent("manual_review", true, null, 2));

        LoanApplication after = loanRepo.findById(loan.getId()).orElseThrow();
        assertThat(after.getLifecycleStatus()).isEqualTo(LoanLifecycleStatus.MANUAL_REVIEW);
        assertThat(after.getDocReevalResult()).isEqualTo("MANUAL_REVIEW");
        assertOutboxCounts(0, 1, 0);
    }

    @Test
    @DisplayName("F. reverse: PENDING_ADMIN_DECISION + replay rejected → stays PENDING_ADMIN_DECISION + LoanPendingAdminDecision, doc_reeval=REJECT")
    void scenarioF_reverse_pending_admin_re_rejected() throws Exception {
        loan.setLifecycleStatus(LoanLifecycleStatus.PENDING_ADMIN_DECISION);
        loan.setInterestRate(new BigDecimal("12.00"));
        loanRepo.save(loan);

        findocConsumer.handle(findocEvent("rejected", true, null, 2));

        LoanApplication after = loanRepo.findById(loan.getId()).orElseThrow();
        assertThat(after.getLifecycleStatus()).isEqualTo(LoanLifecycleStatus.PENDING_ADMIN_DECISION);
        assertThat(after.getDocReevalResult()).isEqualTo("REJECT");
        assertThat(after.getDocReevalRunNumber()).isEqualTo(2);
        assertOutboxCounts(0, 1, 0);
    }

    @Test
    @DisplayName("G. reverse: PENDING_ADMIN_DECISION + replay approved → noop lifecycle, no outbox, doc_reeval=APPROVE")
    void scenarioG_reverse_re_approve_noop() throws Exception {
        loan.setLifecycleStatus(LoanLifecycleStatus.PENDING_ADMIN_DECISION);
        loan.setInterestRate(new BigDecimal("12.00"));
        loanRepo.save(loan);

        findocConsumer.handle(findocEvent("approve", true, null, 2));

        LoanApplication after = loanRepo.findById(loan.getId()).orElseThrow();
        assertThat(after.getLifecycleStatus()).isEqualTo(LoanLifecycleStatus.PENDING_ADMIN_DECISION);
        assertThat(after.getDocReevalResult()).isEqualTo("APPROVE");
        assertOutboxCounts(0, 0, 0);
    }

    @Test
    @DisplayName("H. reverse: PENDING_ADMIN_DECISION + replay manual_review → stays PENDING_ADMIN_DECISION + LoanPendingAdminDecision")
    void scenarioH_reverse_re_manual_review() throws Exception {
        loan.setLifecycleStatus(LoanLifecycleStatus.PENDING_ADMIN_DECISION);
        loan.setInterestRate(new BigDecimal("12.00"));
        loanRepo.save(loan);

        findocConsumer.handle(findocEvent("manual_review", true, null, 2));

        LoanApplication after = loanRepo.findById(loan.getId()).orElseThrow();
        assertThat(after.getLifecycleStatus()).isEqualTo(LoanLifecycleStatus.PENDING_ADMIN_DECISION);
        assertThat(after.getDocReevalResult()).isEqualTo("MANUAL_REVIEW");
        assertOutboxCounts(0, 1, 0);
    }

    @Test
    @DisplayName("I. re-eval after terminal APPROVED: ignored, no DB writes, no outbox")
    void scenarioI_re_eval_after_terminal_ignored() throws Exception {
        loan.setLifecycleStatus(LoanLifecycleStatus.APPROVED);
        loan.setInterestRate(new BigDecimal("12.00"));
        loanRepo.save(loan);

        findocConsumer.handle(findocEvent("rejected", true, null, 2));

        LoanApplication after = loanRepo.findById(loan.getId()).orElseThrow();
        assertThat(after.getLifecycleStatus()).isEqualTo(LoanLifecycleStatus.APPROVED);
        assertReevalNull(after);
        assertOutboxCounts(0, 0, 0);
    }

    private void assertReevalNull(LoanApplication l) {
        assertThat(l.getDocReevalResult()).isNull();
        assertThat(l.getDocReevalReason()).isNull();
        assertThat(l.getDocReevalRunNumber()).isNull();
        assertThat(l.getDocReevalAt()).isNull();
    }

    private void assertOutboxCounts(int riskRequested, int pendingAdmin, int finalized) {
        List<OutboxEvent> events = outboxRepo.findAll();
        long actualRisk = events.stream().filter(e -> "LoanRiskRequested".equals(e.getEventType())).count();
        long actualPending = events.stream().filter(e -> "LoanPendingAdminDecision".equals(e.getEventType())).count();
        long actualFinal = events.stream().filter(e -> "LoanFinalized".equals(e.getEventType())).count();
        assertThat(actualRisk).as("LoanRiskRequested count").isEqualTo(riskRequested);
        assertThat(actualPending).as("LoanPendingAdminDecision count").isEqualTo(pendingAdmin);
        assertThat(actualFinal).as("LoanFinalized count").isEqualTo(finalized);
    }

    private String findocEvent(String recommendation, Boolean replayed, Boolean overridden, Integer runNumber)
            throws Exception {
        ObjectNode env = om.createObjectNode();
        env.put("eventId", UUID.randomUUID().toString());
        env.put("schemaVersion", 1);
        env.put("eventType", "FindocLoanReportReady");
        env.put("occurredAt", Instant.now().toString());
        env.put("correlationId", loan.getExternalId());

        ObjectNode payload = env.putObject("payload");
        payload.put("applicationId", "fa-" + loan.getId());
        payload.put("correlationId", loan.getExternalId());
        payload.put("useCase", "loan");
        payload.put("status", "completed");
        payload.put("recommendation", recommendation);
        payload.put("overallScore", 0.75);
        payload.putArray("complianceChecks");
        payload.putArray("crossDocValidations");
        payload.putArray("fraudSignals");
        ObjectNode reportRoot = payload.putObject("report");
        reportRoot.put("recommendation", recommendation);
        reportRoot.put("creditScore", 780);
        if (replayed != null) payload.put("replayed", replayed);
        if (overridden != null) payload.put("overridden", overridden);
        if (runNumber != null) payload.put("runNumber", runNumber);
        return om.writeValueAsString(env);
    }
}
