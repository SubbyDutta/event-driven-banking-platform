package backend.backend.service;

import backend.backend.Exception.ResourceNotFoundException;
import backend.backend.model.BankAccount;
import backend.backend.model.Transaction;
import backend.backend.model.User;
import backend.backend.repository.BankAccountRepository;
import backend.backend.repository.TransactionRepository;
import backend.backend.Dtos.BankAccountResponseDto;
import backend.backend.requests_response.PagedResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Service
public class AccountService {

    private final BankAccountRepository bankRepo;
    private final CachedLists cachedLists;
    private final TransactionRepository txRepo;

    @Cacheable(
            value = "banking:account:byNumber",
            key = "#username",
            unless = "#result == null"
    )
    public String getAccount(String username) {
        BankAccount bankAccount = bankRepo.findByUserUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));
        System.out.println("DB HIT GETACCOUNT: username=" + username);

        return bankAccount.getAccountNumber();
    }

    public Optional<BankAccount> accountDetails(String username)
    {
        Optional<BankAccount> bankAccount = bankRepo.findByUserUsername(username);
        return bankAccount;
    }

    public PagedResponse<BankAccountResponseDto> getAllAccountsPaged(int page, int size) {
        if (page < 0) page = 0;
        if (size <= 0) size = 20;
        if (size > 100) size = 100;

        Pageable pageable = PageRequest.of(page, size, Sort.by("id").descending());
        List<BankAccountResponseDto> content = cachedLists.getAllAccounts(page, size);

        long totalElements = bankRepo.count();
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

    @Cacheable(value = "banking:balance", key = "#userId")
    public double getBalance(Long userId) {
        System.out.println("DB HIT GETBALANCE: userId=" + userId);
        return bankRepo.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"))
                .getBalance();
    }

    public Transaction getLastTransaction(int userId) {
        return txRepo.findTopByUserIdOrderByTimestampDesc(userId)
                .orElseThrow(() -> new ResourceNotFoundException("No transactions found"));
    }

    public List<Transaction> getTransactionById(int userId) {
        BankAccount account = bankRepo.findByUserId((long) userId)
                .orElseThrow(() -> new ResourceNotFoundException("Bank account not found"));
        String accountNumber = account.getAccountNumber();
        return txRepo.findBySenderAccountOrReceiverAccountOrderByTimestampDesc(accountNumber, accountNumber);
    }

    public List<Transaction> getLastNTransactions(int userId, int n) {
        return txRepo.findRecentTransactions(userId, PageRequest.of(0, n));
    }

    public String getLastSentTo(int userId) {
        Transaction tx = txRepo.findTopByUserIdOrderByTimestampDesc(userId)
                .orElseThrow(() -> new ResourceNotFoundException("No transactions found"));
        return "You last sent ₹" + tx.getAmount() + " to account " + tx.getReceiverAccount();
    }

    public String getLastReceivedFrom(int userId, String myAccountNumber) {
        Transaction tx = txRepo.findTopByUserIdOrderByTimestampDesc(userId)
                .orElseThrow(() -> new ResourceNotFoundException("No transactions found"));
        if (myAccountNumber.equals(tx.getReceiverAccount())) {
            return "You last received ₹" + tx.getAmount() + " from account " + tx.getSenderAccount();
        } else {
            return "No incoming transactions found recently.";
        }
    }

    public String getAccountNumber(Long userId) {
        return bankRepo.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"))
                .getAccountNumber();
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