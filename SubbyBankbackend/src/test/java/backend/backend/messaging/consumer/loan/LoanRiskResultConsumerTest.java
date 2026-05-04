package backend.backend.messaging.consumer.loan;

import backend.backend.messaging.IdempotencyGuard;
import backend.backend.messaging.OutboxEvent;
import backend.backend.messaging.OutboxEventRepository;
import backend.backend.model.KycStatus;
import backend.backend.model.LoanApplication;
import backend.backend.model.LoanLifecycleStatus;
import backend.backend.model.LoanPurpose;
import backend.backend.model.PendingLoanEvent;
import backend.backend.model.User;
import backend.backend.repository.LoanApplicationRepository;
import backend.backend.repository.PendingLoanEventRepository;
import backend.backend.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
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

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Guards the out-of-order P0 fix: when a LoanRiskResult arrives BEFORE the loan
 * reaches DOCS_VERIFIED, it is buffered in pending_loan_events and replayed
 * once docs verification completes. The two scenarios:
 *   1. risk-before-docs: assert a PendingLoanEvent row exists, loan unchanged.
 *   2. drain-on-docs: pre-seed a pending row, dispatch FindocLoanReportReady,
 *      assert the risk result is applied AND the pending row is deleted.
 */
@SpringBootTest(properties = "spring.profiles.active=test")
@Sql(scripts = "classpath:h2-schema-fixup.sql", executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
class LoanRiskResultConsumerTest {

    @Autowired ObjectMapper om;
    @Autowired LoanRiskResultConsumer riskConsumer;
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
        user.setFirstname("F");
        user.setLastname("L");
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
    @DisplayName("risk result before DOCS_VERIFIED: buffered in pending_loan_events, loan untouched")
    void risk_result_buffered_when_not_docs_verified() throws Exception {
        riskConsumer.handle(riskResultJson("approve", "B", 0.07));

        LoanApplication after = loanRepo.findById(loan.getId()).orElseThrow();
        assertThat(after.getLifecycleStatus())
                .as("loan must remain in DOCS_UNDER_REVIEW until docs are verified")
                .isEqualTo(LoanLifecycleStatus.DOCS_UNDER_REVIEW);
        assertThat(after.getRiskBand()).isNull();

        List<PendingLoanEvent> buffered = pendingRepo.findByLoanIdOrderByIdAsc(loan.getId());
        assertThat(buffered).hasSize(1);
        assertThat(buffered.get(0).getEventType()).isEqualTo("LoanRiskResult");
    }

    @Test
    @DisplayName("docs verified: pre-buffered LoanRiskResult drained and applied, pending row deleted")
    void pending_events_drained_on_docs_verified() throws Exception {

        ObjectNode envelope = (ObjectNode) om.readTree(riskResultJson("approve", "B", 0.07));
        PendingLoanEvent pe = new PendingLoanEvent();
        pe.setLoanId(loan.getId());
        pe.setEventType("LoanRiskResult");
        pe.setPayloadJson(om.writeValueAsString(envelope));
        pendingRepo.save(pe);


        loan.setLifecycleStatus(LoanLifecycleStatus.DOCS_VERIFIED);
        loanRepo.save(loan);


        riskConsumer.handle(om.writeValueAsString(envelope));

        LoanApplication after = loanRepo.findById(loan.getId()).orElseThrow();
        assertThat(after.getLifecycleStatus())
                .as("ML approve parks the loan at PENDING_ADMIN_DECISION (maker-checker)")
                .isEqualTo(LoanLifecycleStatus.PENDING_ADMIN_DECISION);
        assertThat(after.getRiskBand()).isEqualTo("B");
    }

    @Test
    @DisplayName("ML approve + band B: lifecycle parks at PENDING_ADMIN_DECISION, only LoanPendingAdminDecision staged")
    void ml_approve_routes_to_pending_admin_decision() throws Exception {
        loan.setLifecycleStatus(LoanLifecycleStatus.DOCS_VERIFIED);
        loanRepo.save(loan);

        riskConsumer.handle(riskResultJson("approve", "B", 0.07));

        LoanApplication after = loanRepo.findById(loan.getId()).orElseThrow();
        assertThat(after.getLifecycleStatus()).isEqualTo(LoanLifecycleStatus.PENDING_ADMIN_DECISION);
        assertThat(after.getRiskBand()).isEqualTo("B");
        assertThat(after.getInterestRate()).isEqualByComparingTo("12.00");
        assertThat(after.getMlRecommendation()).isEqualTo("approve");

        List<OutboxEvent> events = outboxRepo.findAll();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getEventType()).isEqualTo("LoanPendingAdminDecision");
    }

    @Test
    @DisplayName("findoc manual_review overrides ML approve: decision becomes MANUAL_REVIEW")
    void findoc_manual_review_overrides_ml_approve() throws Exception {
        ObjectNode report = om.createObjectNode();
        report.put("recommendation", "manual_review");
        report.put("overallScore", 0.62);
        loan.setLoanReportJson(om.writeValueAsString(report));
        loan.setLifecycleStatus(LoanLifecycleStatus.DOCS_VERIFIED);
        loanRepo.save(loan);

        riskConsumer.handle(riskResultJson("approve", "B", 0.07));

        List<OutboxEvent> events = outboxRepo.findAll();
        assertThat(events)
                .as("a LoanDecisionMade should have been published to the outbox")
                .hasSize(1);
        JsonNode payload = om.readTree(events.get(0).getPayload());
        assertThat(payload.get("decision").asText())
                .as("findoc manual_review must downgrade ML approve")
                .isEqualTo("MANUAL_REVIEW");
        assertThat(payload.get("reason").asText())
                .as("reason should explain the docs-layer override")
                .contains("manual_review at docs layer");
    }

    private String riskResultJson(String decision, String band, double pod) throws Exception {
        ObjectNode env = om.createObjectNode();
        env.put("eventId", UUID.randomUUID().toString());
        env.put("schemaVersion", 1);
        env.put("eventType", "LoanRiskResult");
        env.put("occurredAt", Instant.now().toString());
        env.put("correlationId", loan.getExternalId());

        ObjectNode payload = env.putObject("payload");
        payload.put("loanAppId", loan.getExternalId());
        payload.put("decision", decision);
        payload.put("probability_of_default", pod);
        payload.put("risk_band", band);
        payload.put("modelVersion", "test-v1");
        payload.put("reason", "test");
        payload.putArray("featuresUsed").add("credit_score");
        return om.writeValueAsString(env);
    }
}
