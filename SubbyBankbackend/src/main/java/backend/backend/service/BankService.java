package backend.backend.service;

import backend.backend.Exception.ForbiddenException;
import backend.backend.Exception.ResourceNotFoundException;
import backend.backend.Exception.UnauthorizedException;
import backend.backend.configuration.CryptoUtils;
import backend.backend.configuration.PiiConverter;
import backend.backend.model.BankAccount;
import backend.backend.model.IdempotencyKey;
import backend.backend.model.KycStatus;
import backend.backend.model.Transaction;
import backend.backend.model.User;
import backend.backend.repository.BankAccountRepository;
import backend.backend.repository.IdempotencyRepository;
import backend.backend.repository.TransactionRepository;
import backend.backend.Dtos.BankAccountResponseDto;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BankService {

    private final BankAccountRepository bankRepo;
    private final TransactionRepository txRepo;
    private final PasswordEncoder passwordEncoder;
    private final IdempotencyRepository idempotencyRepo;
    private final TransactionService transactionService;
    private final BuisnessLoggingService buisnessLoggingService;

    @CacheEvict(value = "banking:accounts:list", allEntries = true)
    public BankAccount createAccount(User user, String type) {

        if (user.getKycStatus() != KycStatus.KYC_APPROVED || !user.isAccountActive()) {
            throw new ForbiddenException(
                    "KYC must be approved before creating a bank account (current: "
                            + user.getKycStatus() + ", accountActive=" + user.isAccountActive() + ")");
        }
        if (user.getAadhaarNumber() == null || user.getPanNumber() == null) {

            throw new IllegalStateException(
                    "User is KYC_APPROVED but missing encrypted Aadhaar/PAN — this is a data inconsistency");
        }

        if (bankRepo.findByUser(user).isPresent())
            throw new RuntimeException("Account already exists for this user");

        BankAccount acc = new BankAccount();
        acc.setUser(user);
        acc.setAccountNumber(UUID.randomUUID().toString().substring(0, 12));
        acc.setType(type);
        acc.setBalance(0);
        acc.setVerified(true);

        buisnessLoggingService.log("BANK ACCOUNT CREATED", acc.getAccountNumber(),
                "FOR USER " + user.getUsername() + " (KYC_APPROVED)");
        return bankRepo.save(acc);
    }

    @Caching(evict = {
            @CacheEvict(value = "banking:balance", allEntries = true),
            @CacheEvict(value = "banking:account:dto", allEntries = true),
            @CacheEvict(value = "banking:transactions:list", allEntries = true),
            @CacheEvict(value = "banking:transactions:user", allEntries = true)
    })
    @Transactional(isolation = Isolation.SERIALIZABLE,
    timeout = 10
            )

    public Transaction transfer(String key, String username, Long userId, String senderAcc,
                                String receiverAcc, double amount, String password) {

        Optional<IdempotencyKey> existing = idempotencyRepo.findById(key);
        if (existing.isPresent()) {
            Long priorTxId = existing.get().getTransactionId();
            if (priorTxId != null) {
                return txRepo.findById(priorTxId)
                        .orElseThrow(() -> new ResourceNotFoundException("Prior transaction missing for idempotency key"));
            }
            throw new UnauthorizedException("Duplicate transfer request");
        }

        BankAccount sender = bankRepo.findByAccountNumber(senderAcc)
                .orElseThrow(() -> new ResourceNotFoundException("Sender account not found"));

        User user = sender.getUser();

        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new UnauthorizedException("Incorrect password");
        }

        if (!user.getUsername().equals(username)) {
            throw new UnauthorizedException("Unauthorized: sender account does not belong to you");
        }

        if (sender.getBalance() < amount) {
            throw new UnauthorizedException("Insufficient balance");
        }
        if (sender.isBlocked()) {
            throw new ForbiddenException("Account is blocked");
        }
        if (amount <= 0) {
            throw new ForbiddenException("Amount must be greater than zero");
        }

        sender.setBalance(sender.getBalance() - amount);
        bankRepo.save(sender);

        Transaction tx = new Transaction();
        tx.setUserId(userId.intValue());
        tx.setSenderAccount(senderAcc);
        tx.setAmount(amount);
        tx.setBalance(sender.getBalance());

        Optional<BankAccount> receiverOpt = bankRepo.findByAccountNumber(receiverAcc);
        if (receiverOpt.isPresent()) {
            BankAccount receiver = receiverOpt.get();
            receiver.setBalance(receiver.getBalance() + amount);
            updateUserBalance(receiver.getUser().getUsername(), receiver.getBalance());

            tx.setReceiverAccount(receiverAcc);
            tx.setIsForeign(0);
        } else {
            tx.setReceiverAccount(receiverAcc);
            tx.setIsForeign(1);
        }

        int risk = 0;
        boolean isSavings = sender.getType().equalsIgnoreCase("SAVINGS") ||
                (receiverOpt.isPresent() && receiverOpt.get().getType().equalsIgnoreCase("SAVINGS"));
        boolean isCurrent = sender.getType().equalsIgnoreCase("CURRENT") ||
                (receiverOpt.isPresent() && receiverOpt.get().getType().equalsIgnoreCase("CURRENT"));

        if ((isSavings && amount > 500_000) || (isCurrent && amount > 200_000)) {
            risk = 1;
        }

        if (receiverOpt.isPresent() && receiverOpt.get().getType().equalsIgnoreCase("SALARY")) {
            risk = 0;
            tx.setIs_fraud(0);
            tx.setFraud_probability(0);
        }

        List<Transaction> pastTx = txRepo.findByUserId(userId.intValue());
        double total = pastTx.stream().mapToDouble(Transaction::getAmount).sum();
        double avg = pastTx.isEmpty() ? amount : (total + amount) / (pastTx.size() + 1);
        tx.setAvg_amount(avg);

        tx.setIsHighRisk(risk);

        Transaction result = transactionService.checkFraud(tx);

        IdempotencyKey idempotencyKey = new IdempotencyKey();
        idempotencyKey.setKey(key);
        idempotencyKey.setTransactionId(result.getId());
        idempotencyKey.setCreatedAt(LocalDateTime.now());
        idempotencyRepo.save(idempotencyKey);
        return result;
    }

    @Caching(evict = {
            @CacheEvict(value = "banking:balance", allEntries = true),
            @CacheEvict(value = "banking:account:dto", allEntries = true),
            @CacheEvict(value = "banking:accounts:list", allEntries = true),
            @CacheEvict(value = "banking:account:byNumber", allEntries = true)

    })
    public RuntimeException deleteAccount(Long id) {
        if (!bankRepo.existsById(id)) {
            return new ResourceNotFoundException("Bank account not found");
        }

        buisnessLoggingService.log("DELETED BANK ACC ", getAccountByid(id).accountNumber(),
                "DELETED BY ADMIN");
        bankRepo.deleteById(id);
        return null;
    }

    @Cacheable(value = "banking:account:dto", key = "#id", sync = true)
    public BankAccountResponseDto getAccountByid(Long id) {
        System.out.println("DB HIT GETACCOUNTBYID: id=" + id);

        BankAccount bankAccount = bankRepo.findByUserId(id)
                .orElseThrow(() -> new ResourceNotFoundException("bank account not found"));
        return toDto(bankAccount);
    }

    @Caching(evict = {
            @CacheEvict(value = "banking:balance", allEntries = true),
            @CacheEvict(value = "banking:account:dto", allEntries = true),
            @CacheEvict(value = "banking:accounts:list", allEntries = true)
    })
    public void updateUserBalance(String username, double amount) {
        BankAccount account = bankRepo.findByUserUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("Bank account not found"));

        account.setBalance(amount);
        buisnessLoggingService.log("UPDATED BALANCE", account.getAccountNumber(),
                "UPDATED BALANCE TO " + amount);
        bankRepo.save(account);
    }

    @Caching(evict = {
            @CacheEvict(value = "banking:account:dto", key = "#id"),
            @CacheEvict(value = "banking:accounts:list", allEntries = true)
    })
    public boolean ToggleBlock(Long id) {
        BankAccount acc = bankRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Bank account not found"));
        acc.setBlocked(!acc.isBlocked());
        bankRepo.save(acc);
        buisnessLoggingService.log("ACCOUNT BLOCKED", acc.getAccountNumber(),
                "BLOCKED BY ADMIN");
        return acc.isBlocked();
    }

    /**
     * Idempotency check used by the wallet-credit paths (user-redirect verify,
     * Razorpay webhook).
     *
     * <p><b>NOT cached.</b> The previous {@code @Cacheable} on this method
     * cached the negative answer ("no row exists") for the cache TTL — which
     * defeats idempotency entirely: a replay within the TTL gets the cached
     * "not yet processed" and credits a second time. Hitting the DB on every
     * call is correct for a write-protection check; the read is a single
     * primary-key lookup against an indexed table. Don't re-introduce caching
     * here without {@code unless="!#result"} (cache only positives).
     */
    public boolean isIdempotent(String key) {
        return idempotencyRepo.existsById(key);
    }

    private BankAccountResponseDto toDto(BankAccount b) {
        return new BankAccountResponseDto(
                b.getId(),
                b.getAccountNumber(),
                b.getType(),
                b.getBalance(),
                b.getUser().getUsername(),
                b.isBlocked(),
                b.isVerified()
        );
    }
}