package backend.backend.messaging.consumer.loan;

import backend.backend.events.*;
import backend.backend.messaging.IdempotencyGuard;
import backend.backend.messaging.OutboxEvent;
import backend.backend.messaging.OutboxEventRepository;
import backend.backend.model.*;
import backend.backend.repository.*;
import backend.backend.service.TransactionService;
import backend.backend.service.findoc.FindocSubmitResponse;
import backend.backend.service.findoc.FindocVerifyClient;
import backend.backend.service.findoc.LoanSubmitRequest;
import backend.backend.storage.S3DocumentStorage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * End-to-end pipeline integration test. Runs the real Spring-wired consumers
 * + {@link backend.backend.service.loan.LoanFinalizationService} against an
 * in-memory H2 database (via {@code application-test.yml}), driving each
 * consumer's {@code handle(rawJson)} method with events the way SQS would
 * deliver them.
 *
 * <p>This test proves three scenarios that together cover the most important
 * behaviors of Prompt 5:
 * <ol>
 *   <li><b>Happy path</b>: LoanApplicationSubmitted → findoc report "verified"
 *       → LoanRiskResult band-B approve → LoanDecisionMade APPROVED.
 *       Asserts lifecycle transitions, EMI math, bank account credit,
 *       LoanRepayment seed, legacy {@code status} column sync,
 *       {@link LoanFinalized} outbox row.</li>
 *   <li><b>findoc-reject short-circuit</b>: findoc report "rejected" with
 *       failed compliance checks. Asserts DOCS_REJECTED, SubbyPythonLoan
 *       never invoked (no {@link LoanRiskRequested} on the outbox).</li>
 *   <li><b>Layer-3 identity-mismatch short-circuit</b>: findoc report
 *       "verified" but the loan documents contain a different PAN than the
 *       user's KYC record. {@link backend.backend.service.loan.KycIdentityGuard}
 *       forces DOCS_REJECTED even though findoc approved. Same assertion —
 *       SubbyPythonLoan never called. This is the fraud vector the user
 *       specifically asked us to defend against.</li>
 * </ol>
 *
 * <p>{@link FindocVerifyClient}, {@link S3DocumentStorage}, and the fraud
 * ML are mocked so the test is network-free; everything else runs through
 * real Spring beans against H2.
 */
@SpringBootTest(properties = "spring.profiles.active=test")
@Sql(scripts = "classpath:h2-schema-fixup.sql", executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
class LoanOriginationPipelineIT {

    @Autowired ObjectMapper om;
    @Autowired LoanSubmittedConsumer loanSubmittedConsumer;
    @Autowired LoanFindocResultConsumer loanFindocResultConsumer;
    @Autowired LoanRiskResultConsumer loanRiskResultConsumer;
    @Autowired LoanDecisionConsumer loanDecisionConsumer;
    @Autowired backend.backend.service.loan.LoanFinalizationService loanFinalizer;

    @Autowired UserRepository userRepo;
    @Autowired BankAccountRepository bankRepo;
    @Autowired LoanApplicationRepository loanRepo;
    @Autowired LoanRepaymentRepository repaymentRepo;
    @Autowired OutboxEventRepository outboxRepo;

    @MockBean FindocVerifyClient findoc;
    @MockBean S3DocumentStorage storage;


    @MockBean TransactionService transactionService;


    @MockBean IdempotencyGuard idempotencyGuard;

    private Long userId;
    private String username;
    private String loanAppId;

    @BeforeEach
    void seed() {
        outboxRepo.deleteAll();
        repaymentRepo.deleteAll();
        loanRepo.deleteAll();
        bankRepo.deleteAll();
        userRepo.deleteAll();


        String uniq = UUID.randomUUID().toString().substring(0, 8);
        User u = new User();
        u.setUsername("subham_" + uniq);
        u.setFirstname("Subham");
        u.setLastname("Dutta");
        u.setEmail("subham_" + uniq + "@example.com");
        u.setMobile("9" + Long.toString(System.nanoTime()).substring(0, 9));
        u.setPassword("x");
        u.setRole("USER");
        u.setKycStatus(KycStatus.KYC_APPROVED);
        u.setAccountActive(true);
        u.setPanNumber("ABCDE1234F");
        u.setAadhaarNumber("123412341234");
        u.setDob(LocalDate.of(1998, 3, 15));
        u.setCreditScore(780);
        u = userRepo.save(u);
        this.userId = u.getId();
        this.username = u.getUsername();

        BankAccount acct = new BankAccount();
        acct.setUser(u);
        acct.setAccountNumber("ACC-" + uniq);
        acct.setBalance(1_000.0);
        bankRepo.save(acct);


        this.loanAppId = UUID.randomUUID().toString();
        LoanApplication loan = new LoanApplication();
        loan.setExternalId(loanAppId);
        loan.setUserId(u.getId());
        loan.setUsername(u.getUsername());
        loan.setAmount(500_000);
        loan.setPurpose(LoanPurpose.MEDICAL);
        loan.setMonthsRemaining(6);
        loan.setLifecycleStatus(LoanLifecycleStatus.DRAFT);
        loan.setStatus("PENDING");
        loan.setApproved(false);
        loan.setDue_amount(0);
        loan.setSubmittedAt(Instant.now());
        loanRepo.save(loan);


        when(storage.downloadBytes(ArgumentMatchers.anyString())).thenReturn(new byte[]{0x25});


        when(transactionService.checkFraud(any(Transaction.class)))
                .thenAnswer(inv -> inv.getArgument(0));


        when(idempotencyGuard.claim(any(UUID.class), ArgumentMatchers.anyString())).thenReturn(true);
        when(idempotencyGuard.claim(ArgumentMatchers.anyString(), ArgumentMatchers.anyString())).thenReturn(true);
    }


    @Test
    @DisplayName("happy path: docs verified → risk band B → APPROVED with disbursement")
    void happy_path() throws Exception {

        FindocSubmitResponse submitResp = new FindocSubmitResponse();
        submitResp.setApplicationId("findoc-" + UUID.randomUUID());
        submitResp.setExternalId(loanAppId);
        submitResp.setUseCase("loan");
        submitResp.setStatus("processing");
        submitResp.setDocumentsAccepted(9);
        when(findoc.submitLoan(any(LoanSubmitRequest.class))).thenReturn(submitResp);


        loanSubmittedConsumer.handle(submittedEventJson());
        LoanApplication after = loanRepo.findByExternalId(loanAppId).orElseThrow();
        assertThat(after.getLifecycleStatus()).isEqualTo(LoanLifecycleStatus.DOCS_UNDER_REVIEW);
        assertThat(after.getFindocLoanApplicationId()).isEqualTo(submitResp.getApplicationId());


        loanFindocResultConsumer.handle(findocReportJson("verified",  true,  false));
        after = loanRepo.findByExternalId(loanAppId).orElseThrow();
        assertThat(after.getLifecycleStatus()).isEqualTo(LoanLifecycleStatus.DOCS_VERIFIED);
        assertThat(after.getLoanReportJson()).isNotNull();


        List<OutboxEvent> riskReqs = outboxRepo.findAll().stream()
                .filter(e -> "LoanRiskRequested".equals(e.getEventType()))
                .toList();
        assertThat(riskReqs).hasSize(1);
        JsonNode riskPayload = om.readTree(riskReqs.get(0).getPayload());
        assertThat(riskPayload.get("amountRequested").asDouble()).isEqualTo(500_000.0);
        assertThat(riskPayload.get("tenureMonths").asInt()).isEqualTo(6);
        assertThat(riskPayload.get("features").has("credit_score")).isTrue();


        loanRiskResultConsumer.handle(riskResultJson("approve", "B", 0.07));
        after = loanRepo.findByExternalId(loanAppId).orElseThrow();
        assertThat(after.getLifecycleStatus()).isEqualTo(LoanLifecycleStatus.PENDING_ADMIN_DECISION);
        assertThat(after.getRiskBand()).isEqualTo("B");
        assertThat(after.getInterestRate()).isEqualByComparingTo(new BigDecimal("12.00"));


        assertThat(outboxRepo.findAll().stream()
                .noneMatch(e -> "LoanDecisionMade".equals(e.getEventType())))
                .as("ML approve must wait for an explicit admin click")
                .isTrue();


        loanFinalizer.finalize(after, "APPROVED", "admin approve from pending",
                after.getInterestRate(), "admin:test");
        after = loanRepo.findByExternalId(loanAppId).orElseThrow();
        assertThat(after.getLifecycleStatus()).isEqualTo(LoanLifecycleStatus.APPROVED);
        assertThat(after.getStatus()).isEqualTo("APPROVED");
        assertThat(after.isApproved()).isTrue();
        assertThat(after.getMonthlyEmi()).isGreaterThan(86_000).isLessThan(87_000);
        assertThat(after.getDue_amount()).isEqualTo(Math.round(after.getMonthlyEmi() * 6 * 100) / 100.0);
        assertThat(after.getNextDueDate()).isNotNull();


        BankAccount acct = bankRepo.findByUserUsername(username).orElseThrow();
        assertThat(acct.getBalance()).isEqualTo(1_000.0 + 500_000.0);


        User u = userRepo.findById(userId).orElseThrow();
        assertThat(u.isHasLoan()).isTrue();
        assertThat(u.getLoanamount()).isEqualTo(500_000.0);


        assertThat(repaymentRepo.count()).isEqualTo(1);


        assertThat(outboxRepo.findAll().stream()
                .anyMatch(e -> "LoanFinalized".equals(e.getEventType()))).isTrue();
    }


    @Test
    @DisplayName("findoc-reject: recommendation=rejected short-circuits BEFORE SubbyPythonLoan")
    void findoc_reject_short_circuits_ml() throws Exception {

        FindocSubmitResponse r = new FindocSubmitResponse();
        r.setApplicationId("findoc-reject-" + UUID.randomUUID());
        r.setExternalId(loanAppId);
        r.setUseCase("loan");
        r.setStatus("processing");
        r.setDocumentsAccepted(9);
        when(findoc.submitLoan(any(LoanSubmitRequest.class))).thenReturn(r);

        loanSubmittedConsumer.handle(submittedEventJson());


        loanFindocResultConsumer.handle(findocReportJson("rejected",  true,  true));

        LoanApplication after = loanRepo.findByExternalId(loanAppId).orElseThrow();
        assertThat(after.getLifecycleStatus()).isEqualTo(LoanLifecycleStatus.DOCS_REJECTED);
        assertThat(after.getDecisionReason()).containsIgnoringCase("verification failed");
        assertThat(after.getDecidedAt()).isNotNull();


        assertThat(outboxRepo.findAll().stream()
                .noneMatch(e -> "LoanRiskRequested".equals(e.getEventType())))
                .as("findoc hard-reject must NOT trigger ML risk scoring")
                .isTrue();


        OutboxEvent decEvt = outboxRepo.findAll().stream()
                .filter(e -> "LoanDecisionMade".equals(e.getEventType()))
                .findFirst().orElseThrow();
        JsonNode payload = om.readTree(decEvt.getPayload());
        assertThat(payload.get("decision").asText()).isEqualTo("REJECTED");
        assertThat(payload.get("source").asText()).isEqualTo("findoc-reject");
    }


    @Test
    @DisplayName("identity mismatch (layer 3): KYC PAN differs from doc PAN → hard reject, ML skipped")
    void identity_mismatch_short_circuits_ml() throws Exception {
        FindocSubmitResponse r = new FindocSubmitResponse();
        r.setApplicationId("findoc-mismatch-" + UUID.randomUUID());
        r.setExternalId(loanAppId);
        r.setUseCase("loan");
        r.setStatus("processing");
        r.setDocumentsAccepted(9);
        when(findoc.submitLoan(any(LoanSubmitRequest.class))).thenReturn(r);

        loanSubmittedConsumer.handle(submittedEventJson());


        loanFindocResultConsumer.handle(findocReportJson("verified",  false,  false));

        LoanApplication after = loanRepo.findByExternalId(loanAppId).orElseThrow();
        assertThat(after.getLifecycleStatus())
                .as("Layer-3 must override findoc's 'verified' recommendation")
                .isEqualTo(LoanLifecycleStatus.DOCS_REJECTED);
        assertThat(after.getDecisionReason()).containsIgnoringCase("identity mismatch");


        JsonNode stored = om.readTree(after.getLoanReportJson());
        assertThat(stored.has("identityChecks")).isTrue();
        assertThat(stored.get("identityChecks").isArray()).isTrue();
        assertThat(stored.get("identityChecks").size()).isGreaterThanOrEqualTo(1);
        assertThat(stored.get("identityChecks").get(0).get("check").asText())
                .isEqualTo("pan_mismatch");
        assertThat(stored.get("identityChecks").get(0).get("expected").asText())
                .isEqualTo("ABCDE1234F");


        assertThat(outboxRepo.findAll().stream()
                .noneMatch(e -> "LoanRiskRequested".equals(e.getEventType())))
                .as("identity mismatch must short-circuit before ML")
                .isTrue();


        verify(findoc, times(1)).submitLoan(any(LoanSubmitRequest.class));
        verifyNoMoreInteractions(findoc);
    }


    private String submittedEventJson() throws Exception {
        Map<String, String> s3Keys = Map.of(
                "bank_statement_1", "loans/" + loanAppId + "/bank_statement_1/x_bs1.pdf",
                "bank_statement_2", "loans/" + loanAppId + "/bank_statement_2/x_bs2.pdf",
                "bank_statement_3", "loans/" + loanAppId + "/bank_statement_3/x_bs3.pdf",
                "payslip_1",        "loans/" + loanAppId + "/payslip_1/x_ps1.pdf",
                "payslip_2",        "loans/" + loanAppId + "/payslip_2/x_ps2.pdf",
                "payslip_3",        "loans/" + loanAppId + "/payslip_3/x_ps3.pdf",
                "employment_letter","loans/" + loanAppId + "/employment_letter/x_el.pdf",
                "itr",              "loans/" + loanAppId + "/itr/x_itr.pdf",
                "credit_report",    "loans/" + loanAppId + "/credit_report/x_cr.pdf");
        LoanApplicationSubmitted e = LoanApplicationSubmitted.of(
                loanAppId, String.valueOf(userId), username,
                500_000, "MEDICAL", 6, s3Keys,
                "Subham Dutta", "subham@example.com", "9998887776", "1998-03-15");
        return om.writeValueAsString(e);
    }

    private String findocReportJson(String recommendation, boolean cleanIdentity, boolean withFail) throws Exception {
        ObjectNode env = om.createObjectNode();
        env.put("eventId", UUID.randomUUID().toString());
        env.put("schemaVersion", 1);
        env.put("eventType", "application.loan_report_ready");
        env.put("occurredAt", Instant.now().toString());

        ObjectNode payload = env.putObject("payload");
        payload.put("applicationId", UUID.randomUUID().toString());
        payload.put("correlationId", loanAppId);
        payload.put("useCase", "loan");
        payload.put("status", "completed");
        payload.put("recommendation", recommendation);
        payload.put("overallScore", 0.72);

        ArrayNode compliance = payload.putArray("complianceChecks");
        ObjectNode c1 = compliance.addObject();
        c1.put("name", "pan_format");
        c1.put("status", "pass");
        c1.putObject("details");
        if (withFail) {
            ObjectNode c2 = compliance.addObject();
            c2.put("name", "bank_balance_consistency");
            c2.put("status", "fail");
            c2.putObject("details").put("reason", "Declared income does not match bank deposits");
        }

        payload.putArray("crossDocValidations");

        ArrayNode fraud = payload.putArray("fraudSignals");
        ObjectNode fs = fraud.addObject();
        fs.put("signalName", "velocity");
        fs.put("severity", "low");
        fs.put("score", 0.12);
        fs.putObject("details");


        ObjectNode report = payload.putObject("report");
        ArrayNode docs = report.putArray("documents");
        String panInDocs = cleanIdentity ? "ABCDE1234F" : "ZZZZZ9999Z";
        String nameInDocs = cleanIdentity ? "SUBHAM DUTTA" : "RAJESH KUMAR";
        for (String fld : new String[]{"payslip", "bank_statement", "itr"}) {
            ObjectNode doc = docs.addObject();
            doc.put("type", fld);
            ObjectNode extracted = doc.putObject("extracted");
            extracted.put("name", nameInDocs);
            extracted.put("pan", panInDocs);
            extracted.put("dob", "1998-03-15");
        }

        report.put("monthly_income", 65_000);
        report.put("credit_score", 780);
        report.put("annual_income", 780_000);

        return om.writeValueAsString(env);
    }

    private String riskResultJson(String decision, String band, double pod) throws Exception {
        ObjectNode env = om.createObjectNode();
        env.put("eventId", UUID.randomUUID().toString());
        env.put("schemaVersion", 1);
        env.put("eventType", "LoanRiskResult");
        env.put("occurredAt", Instant.now().toString());
        env.put("correlationId", loanAppId);

        ObjectNode payload = env.putObject("payload");
        payload.put("loanAppId", loanAppId);
        payload.put("decision", decision);
        payload.put("probability_of_default", pod);
        payload.put("risk_band", band);
        payload.put("modelVersion", "test-v1");
        payload.put("reason", "test");
        payload.putArray("featuresUsed").add("credit_score").add("monthly_income");
        return om.writeValueAsString(env);
    }
}
