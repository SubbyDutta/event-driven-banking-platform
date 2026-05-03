package backend.backend.service;

import backend.backend.Dtos.*;
import backend.backend.model.*;
import backend.backend.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CachedLists {
    private final BankAccountRepository bankRepo;
    private final LoanApplicationRepository applicationRepo;
    private final TransactionRepository  transactionRepository;
    private final UserRepository userRepo;
    private final LoanRepaymentRepository repaymentRepo;
    private final BuisnessLoggingRepository buisnessLoggingRepository;

    @Cacheable(
            value = "banking:accounts:list",
            key = "'page:' + #page + ':size:' + #size",
            sync = true
    )
    public List<BankAccountResponseDto> getAllAccounts(int page, int size) {
        if (page < 0) page = 0;
        if (size <= 0) size = 20;
        if (size > 100) size = 100;
        System.out.println("DB HIT -> getAllBankAccount : page=" + page + " size=" + size);

        Pageable pageable = PageRequest.of(page, size, Sort.by("id").descending());
        Page<BankAccount> pageResult = bankRepo.findAll(pageable);

        return pageResult.getContent()
                .stream()
                .map(this::toDtoAccounts)
                .toList();
    }

    @Cacheable(
            value = "banking:loans:pending",
            key = "'page:' + #page + ':size:' + #size",

            sync = true
    )
    public List<LoanApplicationResponseDto> getUserPendingLoanCached(
            Integer page,
            Integer size
    ) {
        System.out.println("DB HIT -> getUserPendingLoans: page=" + page + " size=" + size);

        int p = (page == null || page < 0) ? 0 : page;
        int s = (size == null || size <= 0) ? 20 : size;
        if (s > 100) s = 100;

        Pageable pageable = PageRequest.of(p, s, Sort.by("approvedAt").descending());
        Page<LoanApplication> pageResult = applicationRepo.findByApprovedFalseAndStatusNotIgnoreCase("REJECTED", pageable);

        return pageResult.getContent()
                .stream()
                .map(this::toDtoLoanApplications)
                .toList();
    }
    @Cacheable(
            value = "banking:logs:byaction",
            key = "'a:' + #action + ':p:' + #page + ':s:' + #size",
            sync = true
    )
    public List<BuisnessLoggingResponseDto> getBuisnessLogs(
            String action,
            Integer page,
            Integer size
    ){
        int p = (page == null || page < 0) ? 0 : page;
        int s = (size == null || size <= 0) ? 20 : size;
        if (s > 100) s = 100;
        System.out.println("DB HIT -> getLogsByAction: action=" + action + " page=" + p);
        Pageable pageable = PageRequest.of(p, s, Sort.by("timestamp").descending());
        Page<BuisnessLog> pageResult = buisnessLoggingRepository.findByAction(action,pageable);
        return pageResult.getContent().stream().map(this::toDtoLogs).toList();

    }

    @Cacheable(
            value = "banking:loans:list",
            key = "'u:' + #username + ':min:' + #minAmount + ':p:' + #page + ':s:' + #size",

            sync = true
    )
    public List<LoanApplicationResponseDto> getUserLoansCached(
            String username,
            Double minAmount,
            Integer page,
            Integer size
    ) {
        System.out.println("DB HIT -> getUserLoans: username=" + username);

        int p = (page == null || page < 0) ? 0 : page;
        int s = (size == null || size <= 0) ? 20 : size;
        if (s > 100) s = 100;

        Pageable pageable = PageRequest.of(p, s, Sort.by("approvedAt").descending());
        Page<LoanApplication> pageResult = applicationRepo.searchLoans(username, minAmount, pageable);

        return pageResult.getContent()
                .stream()
                .map(this::toDtoLoanApplications)
                .toList();
    }

    @Cacheable(
            value = "banking:loans:repayments",
            key = "'page:' + #page + ':size:' + #size",

            sync = true
    )
    public List<LoanRepaymentResponseDto> getAllRepayList(int page, int size) {
        if (page < 0) page = 0;
        if (size <= 0) size = 20;
        if (size > 100) size = 100;
        System.out.println("DB HIT -> getAllLoanRepayments : page=" + page);

        Pageable pageable = PageRequest.of(page, size, Sort.by("id").descending());
        Page<LoanRepayment> pageResult = repaymentRepo.findAllByOrderByPaymentDateDesc(pageable);

        return pageResult.getContent()
                .stream()
                .map(this::toDtoLoanRepayments)
                .toList();
    }
    @Cacheable(
            value = "banking:logs:list",
            key = "'page:' + #page + ':size:' + #size",
            sync = true
    )
    public List<BuisnessLoggingResponseDto> getAllLogs(int page,int size)
    {
        if (page < 0) page = 0;
        if (size <= 0) size = 20;
        if (size > 100) size = 100;
        System.out.println("DB HIT -> getAllLogs: page=" + page);
        Pageable pageable = PageRequest.of(page, size, Sort.by("timestamp").descending());
        Page<BuisnessLog> pageResult=buisnessLoggingRepository.findAllByOrderByTimestampDesc(pageable);

        return pageResult.getContent()
                .stream()
                .map(this::toDtoLogs)
                .toList();
    }

    @Cacheable(
            value = "banking:admin:loans:list",
            key = "'st:' + #status + ':p:' + #page + ':s:' + #size",
            sync = true
    )
    public AdminLoanPage getAdminLoansCached(LoanLifecycleStatus status, int page, int size) {
        if (page < 0) page = 0;
        if (size <= 0) size = 20;
        if (size > 200) size = 200;
        System.out.println("DB HIT -> getAdminLoans: status=" + status + " page=" + page);

        Pageable pageable = PageRequest.of(page, size, Sort.by("id").descending());
        Page<LoanApplication> pg = (status != null)
                ? applicationRepo.findByLifecycleStatus(status, pageable)
                : applicationRepo.findAll(pageable);

        List<Map<String, Object>> items = pg.getContent().stream()
                .map(CachedLists::adminLoanToListItem)
                .toList();
        return new AdminLoanPage(items, pg.getNumber(), pg.getSize(), pg.getTotalElements(), pg.getTotalPages());
    }

    private static final ObjectMapper LIST_ITEM_MAPPER = new ObjectMapper();

    public static Map<String, Object> adminLoanToListItem(LoanApplication l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("loanAppId", l.getExternalId());
        m.put("loanId", l.getId());
        m.put("userId", l.getUserId());
        m.put("username", l.getUsername());
        m.put("amount", l.getAmount());
        m.put("purpose", l.getPurpose() == null ? null : l.getPurpose().name());
        m.put("lifecycleStatus", l.getLifecycleStatus() == null ? null : l.getLifecycleStatus().name());
        m.put("riskBand", l.getRiskBand());
        m.put("interestRate", l.getInterestRate());
        m.put("submittedAt", l.getSubmittedAt());
        m.put("decidedAt", l.getDecidedAt());
        m.put("findocLoanApplicationId", l.getFindocLoanApplicationId());
        m.put("mlRecommendation", l.getMlRecommendation());
        m.put("findocRecommendation", extractFindocRecommendation(l.getLoanReportJson()));
        m.put("docReevalResult", l.getDocReevalResult());
        m.put("docReevalRunNumber", l.getDocReevalRunNumber());
        m.put("docReevalAt", l.getDocReevalAt());
        return m;
    }

    private static String extractFindocRecommendation(String reportJson) {
        if (reportJson == null || reportJson.isBlank()) return null;
        try {
            JsonNode root = LIST_ITEM_MAPPER.readTree(reportJson);
            JsonNode rec = root.get("recommendation");
            return rec == null || rec.isNull() ? null : rec.asText();
        } catch (Exception ignored) {
            return null;
        }
    }

    public record AdminLoanPage(List<Map<String, Object>> content,
                                int page, int size,
                                long totalElements, int totalPages) {}

    @Cacheable(
            value = "banking:admin:kyc:users",
            key = "'st:' + #status + ':q:' + #q + ':p:' + #page + ':s:' + #size",
            sync = true
    )
    public AdminKycUserPage getAdminKycUsersCached(KycStatus status, String q, int page, int size) {
        if (page < 0) page = 0;
        if (size <= 0) size = 25;
        if (size > 100) size = 100;
        System.out.println("DB HIT -> getAdminKycUsers: status=" + status + " q=" + q + " page=" + page);

        Pageable pageable = PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "kycSubmittedAt", "id"));
        Page<User> pg = userRepo.searchForKycAdmin(status, q, pageable);

        List<Map<String, Object>> items = pg.getContent().stream()
                .map(CachedLists::adminKycUserToListItem)
                .toList();
        return new AdminKycUserPage(items, pg.getNumber(), pg.getSize(), pg.getTotalElements(), pg.getTotalPages());
    }

    public static Map<String, Object> adminKycUserToListItem(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", u.getId());
        m.put("username", u.getUsername());
        m.put("email", u.getEmail());
        m.put("mobile", u.getMobile());
        m.put("firstname", u.getFirstname());
        m.put("lastname", u.getLastname());
        m.put("kycStatus", u.getKycStatus() == null ? KycStatus.NONE.name() : u.getKycStatus().name());
        m.put("kycSubmittedAt", u.getKycSubmittedAt());
        m.put("kycDecidedAt", u.getKycDecidedAt());
        m.put("accountActive", u.isAccountActive());
        m.put("findocKycApplicationId", u.getFindocKycApplicationId());
        return m;
    }

    public record AdminKycUserPage(List<Map<String, Object>> content,
                                   int page, int size,
                                   long totalElements, int totalPages) {}

    @Cacheable(
            value = "banking:transactions:user",
            key = "'u:' + #userId + ':p:' + #page + ':s:' + #size + ':f:' + #from + ':t:' + #to + ':min:' + #minAmount + ':max:' + #maxAmount",
            sync = true
    )
    public List<TransactionDto> getUserTransactionsCached(
            int userId,
            Integer page,
            Integer size,
            LocalDate from,
            LocalDate to,
            Double minAmount,
            Double maxAmount
    ) {
        System.out.println("DB HIT -> getUserTransactionsFiltered: userId=" + userId);

        int p = (page == null || page < 0) ? 0 : page;
        int s = (size == null || size <= 0) ? 20 : size;
        if (s > 100) s = 100;

        Pageable pageable = PageRequest.of(p, s, Sort.by("timestamp").descending());

        LocalDateTime fromTs = from != null ? from.atStartOfDay() : null;
        LocalDateTime toTs = to != null ? to.plusDays(1).atStartOfDay() : null;

        Page<Transaction> pageResult = transactionRepository.searchByUserWithFilters(
                userId,
                fromTs,
                toTs,
                minAmount,
                maxAmount,
                pageable
        );

        return pageResult.getContent()
                .stream()
                .map(this::toDtoTransactions)
                .toList();
    }

    @Cacheable(
            value = "banking:transactions:list",
            key = "'page:' + #page + ':size:' + #size",

            sync = true
    )
    public List<TransactionDto> getAllTransactionsCached(int page, int size) {
        if (page < 0) page = 0;
        if (size <= 0) size = 20;
        if (size > 100) size = 100;
        System.out.println("DB HIT -> getAllTransactions : page=" + page);

        Pageable pageable = PageRequest.of(page, size, Sort.by("timestamp").descending());
        Page<Transaction> pageResult = transactionRepository.findAllByOrderByTimestampDesc(pageable);

        return pageResult.getContent()
                .stream()
                .map(this::toDtoTransactions)
                .toList();
    }

    @Cacheable(
            value = "banking:users:list",
            key = "'page:' + #page + ':size:' + #size",

            sync = true
    )
    public List<UserResponseDto> getAllUserCached(int page, int size) {
        if (page < 0) page = 0;
        if (size <= 0) size = 20;
        if (size > 100) size = 100;
        System.out.println("DB HIT -> getAllUsers: page=" + page);

        Pageable pageable = PageRequest.of(page, size, Sort.by("id").descending());
        Page<User> pageResult = userRepo.findAll(pageable);

        return pageResult.getContent()
                .stream()
                .map(this::toDtoUser)
                .toList();
    }

    private UserResponseDto toDtoUser(User u) {
        return new UserResponseDto(
                u.getId(),
                u.getUsername(),
                u.getFirstname(),
                u.getLastname(),
                u.getEmail(),
                u.getMobile(),
                u.getRole(),
                u.getCreditScore(),
                u.getUpdatedAt(),
                u.isHasLoan(),
                u.getLoanamount(),
                u.getRemaining(),
                u.getDueDate()

        );
    }

    private TransactionDto toDtoTransactions(Transaction t) {
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

    public LoanApplicationResponseDto toDtoLoanApplications(LoanApplication a) {
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

    private LoanRepaymentResponseDto toDtoLoanRepayments(LoanRepayment t) {
        return new LoanRepaymentResponseDto(
                t.getId(),
                t.getLoanId(),
                t.getUsername(),
                t.getAmountPaid(),
                t.getPaymentDate(),
                t.getRemainingBalance()
        );
    }

    private BankAccountResponseDto toDtoAccounts(BankAccount b) {
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
    private BuisnessLoggingResponseDto toDtoLogs(BuisnessLog b){

               return new BuisnessLoggingResponseDto(
                       b.getId(),
                       b.getUsername(),
                       b.getAction(),
                       b.getDetails(),
                       b.getTimestamp()

               );
    }
}