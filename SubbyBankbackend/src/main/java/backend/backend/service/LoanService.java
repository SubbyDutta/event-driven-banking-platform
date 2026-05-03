package backend.backend.service;

import backend.backend.Dtos.LoanSummaryDTO;
import backend.backend.Exception.ForbiddenException;
import backend.backend.Exception.ResourceNotFoundException;
import backend.backend.Exception.UnauthorizedException;
import backend.backend.configuration.LoanMlProperties;
import backend.backend.model.*;
import backend.backend.repository.*;
import backend.backend.Dtos.LoanApplicationResponseDto;
import backend.backend.Dtos.LoanRepaymentResponseDto;
import backend.backend.requests_response.PagedResponse;
import jakarta.annotation.PostConstruct;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@Service
public class LoanService {
    private static final int TENURE_MONTHS = 6;
    private final LoanEligibilityRequestRepository eligibilityRepo;
    private final BankPoolService bankPoolService;
    private final LoanApplicationRepository applicationRepo;
    private final BankAccountRepository bankRepo;
    private final RestTemplate restTemplate;
    private final UserRepository userRepository;
    private final TransactionRepository txRepo;
    private final TransactionService transactionService;
    private final BankService bankService;
    private final LoanMlProperties loanMlProperties;
    private final UserService userService;
    private String loanCheckUrl;
    private final BuisnessLoggingService buisnessLoggingService;
    private final IdempotencyRepository idempotencyRepository;
    private final CachedLists cachedLists;
    private final LoanRepaymentRepository repaymentRepo;

    @PostConstruct
    public void init() {
        this.loanCheckUrl = loanMlProperties.geturl();
    }

    public LoanApplicationResponseDto toDto(LoanApplication a) {
        return new LoanApplicationResponseDto(
                a.getId(),
                a.getUsername(),
                a.getAmount(),
                a.getDue_amount(),
                a.isApproved(),
                a.getStatus(),
                a.getMonthsRemaining(),
                a.getMonthlyEmi(),
                a.getApprovedAt(),
                a.getNextDueDate()
        );
    }
    public List<LoanApplicationResponseDto> userLoans(String username)
    {
        List<LoanApplication> loans=applicationRepo.findByUsername(username);
        return loans.stream()
                .map(this::toDto)
                .toList();

    }

    public LoanEligibilityRequest checkEligibility(String username, double income, double requestedAmount) {
        LoanEligibilityRequest req = new LoanEligibilityRequest();
        req.setUsername(username);
        req.setIncome(income);

        User userForPii = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        req.setPan(userForPii.getPanNumber());
        req.setAdhar(userForPii.getAadhaarNumber());
        req.setCreditScore(userForPii.getCreditScore());
        req.setRequestedAmount(requestedAmount);

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        BankAccount bank = bankRepo.findByUserUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("Bank account not found"));

        double balance = bank.getBalance();
        double avgAmount = txRepo.findByUserId(user.getId().intValue())
                .stream()
                .mapToDouble(Transaction::getAmount)
                .average()
                .orElse(0.0);

        req.setBalance(balance);
        req.setAvg_transaction(avgAmount);

        Map<String, Object> payload = Map.of(
                "income", income,
                "pan", req.getPan(),
                "adhar", req.getAdhar(),
                "credit_score", req.getCreditScore(),
                "requested_amount", requestedAmount,
                "balance", balance,
                "avg_transaction", avgAmount
        );

        Map<String, Object> response = restTemplate.postForObject(loanCheckUrl, payload, Map.class);
        req.setEligible((Boolean) response.get("eligible"));
        req.setProbability(((Number) response.get("probability")).doubleValue());
        int amount_to_pay = 0;
        int rate = 0;

        if (req.getCreditScore() >= 750) {
            rate = 10;
        } else if (req.getCreditScore() >= 700) {
            rate = 15;
        } else if (req.getCreditScore() >= 650) {
            rate = 20;
        } else if (req.getCreditScore() >= 500) {
            rate = 25;
        }

        amount_to_pay = (int) ((int) (requestedAmount * rate / 100) + requestedAmount);
        req.setAmount_to_pay(amount_to_pay);
        buisnessLoggingService.log("ELIGIBILTY CHECK", "BY " + username,
                "CHECKED ELIGINILTY WITH INCOME OF " + income);
        return eligibilityRepo.save(req);
    }

    @Caching(evict = {
            @CacheEvict(value = "banking:loans:pending", allEntries = true),
            @CacheEvict(value = "banking:loans:list", allEntries = true)
    })
    public LoanApplication applyLoan(Long eligibilityId, String usernameFromToken) {

        LoanEligibilityRequest eligibility = eligibilityRepo.findById(eligibilityId)
                .orElseThrow(() -> new ResourceNotFoundException("Eligibility not found"));

        if (!eligibility.getUsername().equals(usernameFromToken)) {
            throw new UnauthorizedException("Unauthorized to apply for this loan");
        }

        if (!eligibility.isEligible()) {
            throw new UnauthorizedException("User not eligible for this loan");
        }

        if (eligibility.getRequestedAmount() > eligibility.getMaxamoount()) {
            throw new ForbiddenException("Requested amount exceeds maximum allowed based on eligibility");
        }

        boolean hasActiveLoan = applicationRepo.findByUsername(eligibility.getUsername())
                .stream()
                .anyMatch(loan -> {
                    String status = loan.getStatus().toUpperCase();
                    return status.equals("PENDING") || status.equals("APPROVED");
                });

        if (hasActiveLoan) {
            throw new ForbiddenException("You already have an active loan. Repay it before applying again.");
        }

        LoanApplication loan = new LoanApplication();
        loan.setUsername(eligibility.getUsername());
        loan.setAmount(eligibility.getRequestedAmount());
        loan.setDue_amount(eligibility.getAmount_to_pay());
        loan.setStatus("PENDING");
        loan.setApproved(false);
        buisnessLoggingService.log("APPLIED FOR LOAN", usernameFromToken,
                " APPLIED FOR A LOAN OF " + loan.getAmount());
        return applicationRepo.save(loan);
    }

    @Caching(evict = {
            @CacheEvict(value = "banking:loans:pending", allEntries = true),
            @CacheEvict(value = "banking:loans:list", allEntries = true),
            @CacheEvict(value = "banking:balance", allEntries = true),
            @CacheEvict(value = "banking:account:dto", allEntries = true),
            @CacheEvict(value = "banking:loans:userApproved", allEntries = true),
            @CacheEvict(value = "banking:loans:repayments", allEntries = true),
            @CacheEvict(value = "banking:transactions:list", allEntries = true),
            @CacheEvict(value = "banking:transactions:user", allEntries = true)
    })
    @Transactional
    public LoanApplication approveLoan(Long loanId) {
        LoanApplication loan = applicationRepo.findById(loanId)
                .orElseThrow(() -> new RuntimeException("Loan not found"));

        if (loan.isApproved() || "APPROVED".equalsIgnoreCase(loan.getStatus())) {
            throw new ForbiddenException("Loan already approved");
        }

        loan.setApproved(true);
        loan.setStatus("APPROVED");
        loan.setApprovedAt(LocalDateTime.now());
        loan.setMonthlyEmi(loan.getDue_amount() / 6);
        loan.setNextDueDate(LocalDateTime.now().plusMonths(1));
        applicationRepo.save(loan);
        User user =userRepository.findByUsername(loan.getUsername()).orElseThrow(()->new ResourceNotFoundException("user not found"));
        user.setHasLoan(true);
        user.setLoanamount(loan.getAmount());
        user.setRemaining(loan.getDue_amount());
        user.setDueDate(loan.getNextDueDate());
        userService.updateUser(user.getId(),user);
        bankPoolService.deduct(loan.getAmount());

        BankAccount account = bankRepo.findByUserUsername(loan.getUsername())
                .orElseThrow(() -> new ResourceNotFoundException("Bank account not found"));
        account.setBalance(account.getBalance() + loan.getAmount());
        bankService.updateUserBalance(
                account.getUser().getUsername(),
                account.getBalance() + loan.getAmount()
        );

        Transaction tx = new Transaction();
        tx.setSenderAccount("BANK");
        tx.setReceiverAccount(account.getAccountNumber());
        tx.setAmount(loan.getAmount());
        tx.setBalance(account.getBalance());
        tx.setTimestamp(LocalDateTime.now());
        tx.setFraud_probability(0);
        tx.setIs_fraud(0);
        tx.setIsHighRisk(0);
        tx.setIsForeign(0);
        transactionService.checkFraud(tx);

        LoanRepayment r = new LoanRepayment();
        r.setLoanId(loanId);
        r.setUsername(loan.getUsername());
        r.setAmountPaid(0);
        r.setRemainingBalance(loan.getDue_amount());
        r.setPaymentDate(LocalDateTime.now());
        repaymentRepo.save(r);

        buisnessLoggingService.log("LOAN APPROVAL", r.getUsername(),
                " APPROVED OF LOAN OF " + r.getRemainingBalance());
        return loan;
    }

    public PagedResponse<LoanApplicationResponseDto> getPendingLoans(int page, int size) {
        if (page < 0) page = 0;
        if (size <= 0) size = 20;
        if (size > 100) size = 100;

        List<LoanApplicationResponseDto> content = cachedLists.getUserPendingLoanCached(page, size);
        long totalElements = applicationRepo.count();
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

    public PagedResponse<LoanApplicationResponseDto> searchLoans(
            String username,
            Double minAmount,
            int page,
            int size
    ) {
        List<LoanApplicationResponseDto> content =
                cachedLists.getUserLoansCached(username, minAmount, page, size);
        User user = userService.ifUserExists(username);
        long totalElements = applicationRepo.count();
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

    @Caching(evict = {
            @CacheEvict(value = "banking:credit:score", allEntries = true),
            @CacheEvict(value = "banking:account:dto", allEntries = true),
            @CacheEvict(value = "banking:loans:userApproved", allEntries = true),
            @CacheEvict(value = "banking:loans:repayments", allEntries = true),
            @CacheEvict(value = "banking:transactions:list", allEntries = true),
            @CacheEvict(value = "banking:transactions:user", allEntries = true),
            @CacheEvict(value = "banking:loans:userrepaylist", allEntries = true)
    })
    @Transactional
    public LoanRepayment repayLoan(Long loanId, double amount, String key) {

        if (idempotencyRepository.existsById(key)) {
            return null;
        }

        LoanApplication loan = applicationRepo.findById(loanId)
                .orElseThrow(() -> new ResourceNotFoundException("Loan not found"));

        if (!loan.getStatus().equals("APPROVED")) {
            throw new ResourceNotFoundException("Loan not approved yet");
        }

        User user = userRepository.findByUsername(loan.getUsername())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        double remaining = loan.getDue_amount();

        if (amount < loan.getAmount() / 6) {
            throw new ForbiddenException("cant pay less");
        }

        if (amount > remaining) {
            throw new ForbiddenException("You are trying to pay more than owed");
        }

        BankAccount account = bankRepo.findByUserUsername(loan.getUsername())
                .orElseThrow(() -> new RuntimeException("Bank account not found"));

        if (account.getBalance() < amount) {
            throw new ForbiddenException("Insufficient balance");
        }

        account.setBalance(account.getBalance() - amount);
        bankService.updateUserBalance(account.getUser().getUsername(), account.getBalance());

        LocalDateTime now = LocalDateTime.now();
        double newRemaining = remaining - amount;

        if (newRemaining <= 0 && loan.getApprovedAt().plusDays(10).isBefore(now)) {
            user.setCreditScore(Math.min(900, user.getCreditScore() + 20));
        } else if (now.isBefore(loan.getNextDueDate()) || now.isEqual(loan.getNextDueDate())) {
            user.setCreditScore(Math.min(900, user.getCreditScore() + 6));
        } else {
            user.setCreditScore(Math.max(300, user.getCreditScore() - 12));
        }

        userService.updateUser(account.getUser().getId().longValue(), user);

        Transaction tx = new Transaction();
        tx.setSenderAccount(account.getAccountNumber());
        tx.setReceiverAccount("BANK");
        tx.setAmount(amount);
        tx.setBalance(account.getBalance());
        tx.setTimestamp(LocalDateTime.now());
        tx.setFraud_probability(0.0);
        tx.setIs_fraud(0);
        tx.setIsHighRisk(0);
        tx.setIsForeign(0);
        tx.setUserId(account.getUser().getId().intValue());

        transactionService.checkFraud(tx);

        IdempotencyKey idempotencyKey = new IdempotencyKey();
        idempotencyKey.setKey(key);
        idempotencyKey.setCreatedAt(LocalDateTime.now());

        LoanRepayment repayment = new LoanRepayment();
        repayment.setLoanId(loanId);
        repayment.setUsername(loan.getUsername());
        repayment.setAmountPaid(amount);
        repayment.setPaymentDate(now);
        repayment.setRemainingBalance(newRemaining);

        repaymentRepo.save(repayment);
        bankPoolService.add(amount);

        if (newRemaining <= 0) {
            loan.setStatus("PAID");
            loan.setMonthsRemaining(0);
            user.setHasLoan(false);
            user.setLoanamount(0);
            user.setRemaining(0);
            repaymentRepo.deleteAllByUsername(user.getUsername());
        } else {
            loan.setMonthsRemaining(loan.getMonthsRemaining() - 1);
            loan.setNextDueDate(loan.getNextDueDate().plusMonths(1));
            user.setLoanamount(loan.getAmount());
            user.setRemaining(newRemaining);
            user.setDueDate(loan.getNextDueDate());
        }

        loan.setDue_amount(newRemaining);

        userService.updateUser(account.getUser().getId().longValue(), user);
        applicationRepo.save(loan);
        idempotencyRepository.save(idempotencyKey);

        buisnessLoggingService.log("REPAID", account.getAccountNumber(),
                "PAID " + amount + " REMAINING " + newRemaining);

        return repayment;
    }

    @Cacheable(
            value = "banking:loans:userApproved",
            key = "#username + ':' + #status",
            unless = "#result == null || #result.isEmpty()"
    )
    public List<LoanApplication> getUserApprovedLoans(String username, String status) {
        System.out.println("DB HIT USERAPPROVEDLOANS: username=" + username + " status=" + status);
        List<LoanApplication> loan = applicationRepo.findByUsernameAndStatus(username, status);
        return loan;
    }

    public LoanSummaryDTO getLoanSummary(Long loanId) {
        System.out.println("DB HIT -> getLoanSummary : loanId=" + loanId);

        LoanApplication loan = applicationRepo.findById(loanId)
                .orElseThrow(() -> new ResourceNotFoundException("Loan not found"));

        List<LoanRepayment> payments = repaymentRepo.findByLoanIdOrderByPaymentDateDesc(loanId);
        double paid = payments.stream().mapToDouble(LoanRepayment::getAmountPaid).sum();
        double remaining = loan.getDue_amount();

        return new LoanSummaryDTO(
                loan.getId(),
                loan.getAmount(),
                remaining,
                loan.getMonthlyEmi(),
                loan.getNextDueDate(),
                loan.getMonthsRemaining()
        );
    }

    public PagedResponse<LoanRepaymentResponseDto> repayList(int page, int size) {
        if (page < 0) page = 0;
        if (size <= 0) size = 20;
        if (size > 100) size = 100;

        System.out.println("DB HIT REPAYLIST: page=" + page);
        List<LoanRepaymentResponseDto> content = cachedLists.getAllRepayList(page, size);

        long totalElements = repaymentRepo.count();
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
    @Cacheable(
            value = "banking:loans:userrepaylist",
            key = "#username",
            unless = "#result == null || #result.isEmpty()"
    )
    public List<LoanRepayment> userrepay(String username)
    {
        List<LoanRepayment> loan;
        if (username != null && !username.isBlank()) {
             loan =repaymentRepo.findByUsernameOrderByPaymentDateDesc(username);
        }else{
            throw new ResourceNotFoundException("Loan not found for this user");

        }

        return loan;

    }

    private LoanRepaymentResponseDto toDto(LoanRepayment t) {
        return new LoanRepaymentResponseDto(
                t.getId(),
                t.getLoanId(),
                t.getUsername(),
                t.getAmountPaid(),
                t.getPaymentDate(),
                t.getRemainingBalance()
        );
    }

    private double totalPaid(Long loanId) {
        return repaymentRepo.findByLoanIdOrderByPaymentDateDesc(loanId)
                .stream()
                .mapToDouble(LoanRepayment::getAmountPaid)
                .sum();
    }

    private int monthsBetween(LocalDateTime start, LocalDateTime end) {
        if (end.isBefore(start)) return 0;
        int months = (end.getYear() - start.getYear()) * 12 + (end.getMonthValue() - start.getMonthValue());
        if (end.getDayOfMonth() < start.getDayOfMonth()) months = Math.max(months - 1, 0);
        return months;
    }

    private LocalDateTime dueDateForInstallment(LocalDateTime approvedAt, int installmentNo) {
        LocalDateTime base = approvedAt.plusMonths(installmentNo);
        YearMonth ym = YearMonth.from(base);
        int dom = Math.min(approvedAt.getDayOfMonth(), ym.lengthOfMonth());
        return LocalDateTime.of(ym.getYear(), ym.getMonth(), dom, approvedAt.getHour(),
                approvedAt.getMinute(), approvedAt.getSecond());
    }

    private int clampCredit(int v) {
        if (v < 300) return 300;
        if (v > 900) return 900;
        return v;
    }
}