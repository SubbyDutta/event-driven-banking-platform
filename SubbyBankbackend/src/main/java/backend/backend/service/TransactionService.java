package backend.backend.service;

import backend.backend.Exception.FraudServiceUnavailableException;
import backend.backend.configuration.SubbyProperties;
import backend.backend.events.TransactionPosted;
import backend.backend.messaging.OutboxEventPublisher;
import backend.backend.model.BankAccount;
import backend.backend.model.Transaction;
import backend.backend.model.User;
import backend.backend.repository.BankAccountRepository;
import backend.backend.repository.TransactionRepository;
import backend.backend.requests_response.PagedResponse;
import backend.backend.service.fraud.FraudCheckResult;
import backend.backend.service.fraud.FraudClient;
import backend.backend.service.fraud.FraudResultCache;
import backend.backend.Dtos.TransactionDto;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

@RequiredArgsConstructor
@Service
public class TransactionService {

    private final UserService userService;
    private final TransactionRepository transactionRepository;
    private final FraudClient fraudClient;
    private final FraudResultCache fraudResultCache;
    private final BuisnessLoggingService buisnessLoggingService;
    private final CachedLists cachedLists;
    private final BankAccountRepository bankAccountRepository;
    private final OutboxEventPublisher outboxPublisher;
    private final SubbyProperties subbyProperties;

    @Caching(evict = {
            @CacheEvict(value = "banking:transactions:list", allEntries = true),
            @CacheEvict(value = "banking:transactions:user", allEntries = true)
    })
    public Transaction saveTransaction(Transaction transaction) {
        if(transaction.getIs_fraud()==1)
        {
             Optional<BankAccount> senderUser=bankAccountRepository.findByAccountNumber(transaction.getSenderAccount());
             if(senderUser.isPresent()){
                 senderUser.get().setBlocked(true);

             }
            Optional<BankAccount> receiverUser=bankAccountRepository.findByAccountNumber(transaction.getReceiverAccount());
            if(receiverUser.isPresent())
            {
                receiverUser.get().setBlocked(true);

            }
            buisnessLoggingService.log("BLOCKED", transaction.getSenderAccount(),
                    " SUSPECTED FRAUD TRANSFER FROM " + transaction.getSenderAccount() + " TO " + transaction.getReceiverAccount() +
                            " OF AMOUNT " + transaction.getAmount());
        }
        return transactionRepository.save(transaction);
    }

    @Transactional(timeout = 10)
    public Transaction checkFraud(Transaction transaction) {

        boolean systemSender = "BANK".equals(transaction.getSenderAccount())
                || "RAZORPAY_TOPUP".equals(transaction.getSenderAccount());
        if (systemSender) {
            applyFraudResult(transaction, FraudCheckResult.skippedSystem());
            buisnessLoggingService.log("TRANSFER", transaction.getSenderAccount(),
                    "FROM " + transaction.getSenderAccount() + " TO " + transaction.getReceiverAccount() +
                            " OF AMOUNT " + transaction.getAmount());
            saveTransaction(transaction);
            publishTransactionPosted(transaction);
            return transaction;
        }

        FraudCheckResult result = fraudResultCache.getOrLoad(transaction, () -> fraudClient.score(transaction));
        applyFraudResult(transaction, result);

        if (result.status() == FraudCheckResult.Status.UNAVAILABLE) {
            throw new FraudServiceUnavailableException(
                    "Fraud detection offline. Transfer aborted to protect funds.",
                    result.cause());
        }

        buisnessLoggingService.log("TRANSFER", transaction.getSenderAccount(),
                "FROM " + transaction.getSenderAccount() + " TO " + transaction.getReceiverAccount() +
                        " OF AMOUNT " + transaction.getAmount() +
                        " [fraud=" + result.status() + " p=" + result.probability() + "]");
        saveTransaction(transaction);
        publishTransactionPosted(transaction);
        return transaction;
    }

    private void publishTransactionPosted(Transaction transaction) {
        String sender = transaction.getSenderAccount();
        String receiver = transaction.getReceiverAccount();
        TransactionPosted.Category category = classifyCategory(sender, receiver);

        if (category == TransactionPosted.Category.DISBURSEMENT
                || category == TransactionPosted.Category.TOPUP) {
            return;
        }

        if (transaction.getIs_fraud() == 1) {
            return;
        }

        String topic = subbyProperties.topics().notifications();
        String txnRef = transaction.getId() == null ? "" : String.valueOf(transaction.getId());
        Instant occurredAt = transaction.getTimestamp() == null
                ? Instant.now()
                : transaction.getTimestamp().atZone(ZoneId.systemDefault()).toInstant();
        BigDecimal amount = BigDecimal.valueOf(transaction.getAmount());

        Optional<BankAccount> senderAcc = bankAccountRepository.findByAccountNumber(sender);
        Optional<BankAccount> receiverAcc = bankAccountRepository.findByAccountNumber(receiver);

        senderAcc.ifPresent(acc -> {
            User user = acc.getUser();
            if (user == null || user.getEmail() == null || user.getEmail().isBlank()) return;
            String counterparty = receiverAcc
                    .map(r -> displayName(r.getUser()))
                    .orElse(receiver);
            outboxPublisher.publish(topic,
                    TransactionPosted.forUser(
                            String.valueOf(user.getId()),
                            user.getEmail(),
                            TransactionPosted.Direction.DEBIT,
                            amount,
                            "INR",
                            txnRef,
                            BigDecimal.valueOf(acc.getBalance()),
                            counterparty,
                            category,
                            occurredAt));
        });

        receiverAcc.ifPresent(acc -> {
            User user = acc.getUser();
            if (user == null || user.getEmail() == null || user.getEmail().isBlank()) return;
            String counterparty = senderAcc
                    .map(s -> displayName(s.getUser()))
                    .orElse(sender);
            outboxPublisher.publish(topic,
                    TransactionPosted.forUser(
                            String.valueOf(user.getId()),
                            user.getEmail(),
                            TransactionPosted.Direction.CREDIT,
                            amount,
                            "INR",
                            txnRef,
                            BigDecimal.valueOf(acc.getBalance()),
                            counterparty,
                            category,
                            occurredAt));
        });
    }

    private static TransactionPosted.Category classifyCategory(String sender, String receiver) {
        if ("BANK".equals(sender)) return TransactionPosted.Category.DISBURSEMENT;
        if ("RAZORPAY_TOPUP".equals(sender)) return TransactionPosted.Category.TOPUP;
        if ("BANK".equals(receiver)) return TransactionPosted.Category.EMI;
        return TransactionPosted.Category.TRANSFER;
    }

    private static String displayName(User user) {
        if (user == null) return "";
        if (user.getFirstname() != null && !user.getFirstname().isBlank()) return user.getFirstname();
        if (user.getUsername() != null && !user.getUsername().isBlank()) return user.getUsername();
        return "";
    }

    private static void applyFraudResult(Transaction transaction, FraudCheckResult result) {
        transaction.setFraud_probability(result.probability());
        transaction.setIs_fraud(result.fraudLabel());
    }

    public PagedResponse<TransactionDto> getAllTransactionsPaged(int page, int size) {
        List<TransactionDto> content = cachedLists.getAllTransactionsCached(page, size);

        long totalElements = transactionRepository.count();
        int totalPages = (int) Math.ceil((double) totalElements / size);

        return new PagedResponse<>(
                content,
                page,
                size,
                totalElements,
                totalPages,
                page + 1 >= totalPages
        );
    }

    public PagedResponse<TransactionDto> getUserTransactionsFiltered(String username,
                                                                     Integer page,
                                                                     Integer size,
                                                                     LocalDate from,
                                                                     LocalDate to,
                                                                     Double minAmount,
                                                                     Double maxAmount) {
        User user = userService.ifUserExists(username);
        List<TransactionDto> content = cachedLists.getUserTransactionsCached(
                user.getId().intValue(), page, size, from, to, minAmount, maxAmount);

        LocalDateTime fromTs = from != null ? from.atStartOfDay() : null;
        LocalDateTime toTs = to != null ? to.plusDays(1).atStartOfDay() : null;

        long totalElements = transactionRepository.countByUserWithFilters(
                user.getId().intValue(),
                fromTs,
                toTs,
                minAmount,
                maxAmount
        );
        int totalPages = (int) Math.ceil((double) totalElements / size);

        return new PagedResponse<>(
                content,
                page,
                size,
                totalElements,
                totalPages,
                page + 1 >= totalPages
        );
    }

    private TransactionDto toDto(Transaction t) {
        return new TransactionDto(
                t.getId(),
                t.getSenderAccount(),
                t.getReceiverAccount(),
                t.getAmount(),
                t.getFraud_probability(),
                t.getIs_fraud(),
                t.getUserId(),
                t.getIsForeign(),
                t.getHour(),
                t.getTimestamp() != null ? t.getTimestamp().toString() : null
        );
    }
}