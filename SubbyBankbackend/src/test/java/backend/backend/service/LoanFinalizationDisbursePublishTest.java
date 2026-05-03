package backend.backend.service;

import backend.backend.configuration.SubbyProperties;
import backend.backend.events.DomainEvent;
import backend.backend.events.LoanDisbursed;
import backend.backend.events.LoanFinalized;
import backend.backend.messaging.OutboxEventPublisher;
import backend.backend.model.BankAccount;
import backend.backend.model.LoanApplication;
import backend.backend.model.LoanLifecycleStatus;
import backend.backend.model.User;
import backend.backend.repository.BankAccountRepository;
import backend.backend.repository.LoanApplicationRepository;
import backend.backend.repository.LoanRepaymentRepository;
import backend.backend.repository.UserRepository;
import backend.backend.service.loan.LoanFinalizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoanFinalizationDisbursePublishTest {

    @Mock LoanApplicationRepository loanRepo;
    @Mock UserRepository userRepo;
    @Mock BankAccountRepository bankRepo;
    @Mock LoanRepaymentRepository repaymentRepo;
    @Mock BankPoolService bankPoolService;
    @Mock BankService bankService;
    @Mock TransactionService transactionService;
    @Mock BuisnessLoggingService buisnessLoggingService;
    @Mock OutboxEventPublisher outbox;
    @Mock SubbyProperties properties;
    @Mock SubbyProperties.Topics topics;

    @InjectMocks LoanFinalizationService service;

    @BeforeEach
    void wireTopics() {
        when(properties.topics()).thenReturn(topics);
        when(topics.notifications()).thenReturn("subby-notifications");
    }

    @Test
    void approve_publishesLoanDisbursedExactlyOnceWithCorrectFields() {
        User user = new User();
        user.setId(99L);
        user.setUsername("subham");
        user.setFirstname("Subham");
        user.setEmail("subham@example.com");

        BankAccount account = new BankAccount();
        account.setUser(user);
        account.setAccountNumber("ABCDEFGH1234");
        account.setBalance(0);

        LoanApplication loan = new LoanApplication();
        loan.setId(500L);
        loan.setExternalId("loan-app-ext-500");
        loan.setUserId(99L);
        loan.setAmount(50000);
        loan.setLifecycleStatus(LoanLifecycleStatus.RISK_EVALUATED);

        when(userRepo.findById(99L)).thenReturn(Optional.of(user));
        when(bankRepo.findByUserUsername("subham")).thenReturn(Optional.of(account));

        service.finalize(loan, "APPROVED", "auto-approved",
                new BigDecimal("12.0"), "system:auto");

        ArgumentCaptor<DomainEvent> captor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(outbox, times(2)).publish(eq("subby-notifications"), captor.capture());

        long disbursedCount = captor.getAllValues().stream()
                .filter(LoanDisbursed.class::isInstance).count();
        assertThat(disbursedCount).isEqualTo(1);

        LoanDisbursed event = captor.getAllValues().stream()
                .filter(LoanDisbursed.class::isInstance)
                .map(LoanDisbursed.class::cast)
                .findFirst().orElseThrow();
        assertThat(event.getUserId()).isEqualTo("99");
        assertThat(event.getEmail()).isEqualTo("subham@example.com");
        assertThat(event.getLoanAppId()).isEqualTo("loan-app-ext-500");
        assertThat(event.getAmount()).isEqualByComparingTo(new BigDecimal("50000.0"));
        assertThat(event.getAccountNumberMasked()).isEqualTo("XXXX1234");
        assertThat(event.eventType()).isEqualTo("LoanDisbursed");

        long finalizedCount = captor.getAllValues().stream()
                .filter(LoanFinalized.class::isInstance).count();
        assertThat(finalizedCount).isEqualTo(1);
    }
}
