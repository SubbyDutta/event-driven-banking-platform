package backend.backend.service;

import backend.backend.configuration.SubbyProperties;
import backend.backend.events.TransactionPosted;
import backend.backend.messaging.OutboxEventPublisher;
import backend.backend.model.BankAccount;
import backend.backend.model.Transaction;
import backend.backend.model.User;
import backend.backend.repository.BankAccountRepository;
import backend.backend.repository.TransactionRepository;
import backend.backend.service.fraud.FraudCheckResult;
import backend.backend.service.fraud.FraudClient;
import backend.backend.service.fraud.FraudResultCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionServicePublishTest {

    @Mock UserService userService;
    @Mock TransactionRepository transactionRepository;
    @Mock FraudClient fraudClient;
    @Mock FraudResultCache fraudResultCache;
    @Mock BuisnessLoggingService buisnessLoggingService;
    @Mock CachedLists cachedLists;
    @Mock BankAccountRepository bankAccountRepository;
    @Mock OutboxEventPublisher outboxPublisher;
    @Mock SubbyProperties subbyProperties;
    @Mock SubbyProperties.Topics topics;

    @InjectMocks TransactionService transactionService;

    private void stubTopics() {
        when(subbyProperties.topics()).thenReturn(topics);
        when(topics.notifications()).thenReturn("subby-notifications");
    }

    @Test
    void checkFraud_transferBetweenUsers_publishesCreditForReceiverAndDebitForSender() {
        stubTopics();
        User sender = newUser(1L, "Subham", "subham@example.com");
        User receiver = newUser(2L, "Rajat", "rajat@example.com");
        BankAccount senderAcc = newAccount(sender, "ACC-SEND-001", 9500);
        BankAccount receiverAcc = newAccount(receiver, "ACC-RECV-002", 12500);

        when(bankAccountRepository.findByAccountNumber("ACC-SEND-001"))
                .thenReturn(Optional.of(senderAcc));
        when(bankAccountRepository.findByAccountNumber("ACC-RECV-002"))
                .thenReturn(Optional.of(receiverAcc));

        Transaction tx = new Transaction();
        tx.setId(1001L);
        tx.setSenderAccount("ACC-SEND-001");
        tx.setReceiverAccount("ACC-RECV-002");
        tx.setAmount(500);
        tx.setBalance(9500);
        tx.setUserId(1);

        when(fraudResultCache.getOrLoad(eq(tx), any(Supplier.class)))
                .thenReturn(FraudCheckResult.checked(0.05, 0));
        when(transactionRepository.save(tx)).thenReturn(tx);

        transactionService.checkFraud(tx);

        ArgumentCaptor<TransactionPosted> captor = ArgumentCaptor.forClass(TransactionPosted.class);
        verify(outboxPublisher, org.mockito.Mockito.times(2))
                .publish(eq("subby-notifications"), captor.capture());

        List<TransactionPosted> events = captor.getAllValues();
        assertThat(events).hasSize(2);

        TransactionPosted debit = events.stream()
                .filter(e -> e.getDirection() == TransactionPosted.Direction.DEBIT)
                .findFirst().orElseThrow();
        TransactionPosted credit = events.stream()
                .filter(e -> e.getDirection() == TransactionPosted.Direction.CREDIT)
                .findFirst().orElseThrow();

        assertThat(debit.getEmail()).isEqualTo("subham@example.com");
        assertThat(debit.getCounterparty()).isEqualTo("Rajat");
        assertThat(debit.getCategory()).isEqualTo(TransactionPosted.Category.TRANSFER);

        assertThat(credit.getEmail()).isEqualTo("rajat@example.com");
        assertThat(credit.getCounterparty()).isEqualTo("Subham");
        assertThat(credit.getCategory()).isEqualTo(TransactionPosted.Category.TRANSFER);
    }

    @Test
    void checkFraud_disbursementFromBank_doesNotPublishTransactionPosted() {
        Transaction tx = new Transaction();
        tx.setId(2001L);
        tx.setSenderAccount("BANK");
        tx.setReceiverAccount("ACC-USER-001");
        tx.setAmount(50000);
        tx.setUserId(1);

        when(transactionRepository.save(tx)).thenReturn(tx);

        transactionService.checkFraud(tx);

        verify(transactionRepository).save(tx);
        verifyNoInteractions(outboxPublisher);
    }

    @Test
    void checkFraud_topupFromRazorpaySystem_doesNotPublishTransactionPosted() {
        Transaction tx = new Transaction();
        tx.setId(3001L);
        tx.setSenderAccount("RAZORPAY_TOPUP");
        tx.setReceiverAccount("ACC-USER-001");
        tx.setAmount(2000);
        tx.setUserId(1);

        when(transactionRepository.save(tx)).thenReturn(tx);

        transactionService.checkFraud(tx);

        verify(transactionRepository).save(tx);
        verify(outboxPublisher, never()).publish(any(), any());
    }

    private static User newUser(long id, String firstname, String email) {
        User u = new User();
        u.setId(id);
        u.setFirstname(firstname);
        u.setEmail(email);
        u.setUsername("user" + id);
        return u;
    }

    private static BankAccount newAccount(User user, String accountNumber, double balance) {
        BankAccount a = new BankAccount();
        a.setUser(user);
        a.setAccountNumber(accountNumber);
        a.setBalance(balance);
        return a;
    }
}
