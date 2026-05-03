package backend.backend.service;

import backend.backend.Dtos.BankAccountResponseDto;
import backend.backend.Exception.BadRequestException;
import backend.backend.configuration.RazorpayProperties;
import backend.backend.model.IdempotencyKey;
import backend.backend.model.Transaction;
import backend.backend.model.User;
import backend.backend.repository.IdempotencyRepository;
import backend.backend.requests_response.PaymentVerifyRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.razorpay.*;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RequiredArgsConstructor
@Service
public class RazorpayService {

    private static final Logger log = LoggerFactory.getLogger(RazorpayService.class);

    private final RazorpayProperties razorpayProperties;
    private final UserService userService;
    private final TransactionService transactionService;
    private final BankService bankService;
    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;

    String keyId;
    String keySecret;
    String webhookSecret;

    private RazorpayClient razorpayClient;

    @PostConstruct
    public void init() {
        this.keyId = razorpayProperties.getKeyId();
        this.keySecret = razorpayProperties.getSecret();
        this.webhookSecret = razorpayProperties.getWebhookSecret();
        try {
            this.razorpayClient = new RazorpayClient(keyId, keySecret);
        } catch (RazorpayException e) {

            throw new IllegalStateException("Failed to initialise RazorpayClient", e);
        }
        if (webhookSecret == null || webhookSecret.isBlank()) {
            log.warn("razorpay.webhook-secret is blank — /api/payment/webhook will reject all calls. " +
                    "Configure it in the Razorpay dashboard and set RAZORPAY_WEBHOOK_SECRET.");
        }
    }

    @CircuitBreaker(name = "razorpay", fallbackMethod = "createOrderFallback")
    @Bulkhead(name = "razorpay")
    @Retry(name = "razorpay")
    public Order createOrder(int amountInRupees, String receiptId, String username) throws RazorpayException {
        JSONObject orderRequest = new JSONObject();
        orderRequest.put("amount", amountInRupees * 100);
        orderRequest.put("currency", "INR");
        orderRequest.put("receipt", receiptId);
        orderRequest.put("payment_capture", 1);
        if (username != null && !username.isBlank()) {

            JSONObject notes = new JSONObject();
            notes.put("username", username);
            orderRequest.put("notes", notes);
        }

        return razorpayClient.orders.create(orderRequest);
    }

    @SuppressWarnings("unused")
    private Order createOrderFallback(int amountInRupees, String receiptId, String username, Throwable t) {
        log.error("razorpay createOrder fallback (amount={}, receipt={}, user={}): {}",
                amountInRupees, receiptId, username, t.toString());
        throw new BadRequestException("Payment provider is temporarily unavailable. Please retry in a moment.");
    }

    public boolean verifySignature(String orderId, String paymentId, String signature) {
        try {
            String payload = orderId + "|" + paymentId;
            return Utils.verifySignature(payload, signature, keySecret);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean verifyWebhookSignature(String rawBody, String signature) {
        if (webhookSecret == null || webhookSecret.isBlank()) return false;
        if (rawBody == null || signature == null) return false;
        try {
            return Utils.verifyWebhookSignature(rawBody, signature, webhookSecret);
        } catch (Exception e) {
            log.warn("razorpay webhook signature verification threw: {}", e.toString());
            return false;
        }
    }

    @Caching(evict = {
            @CacheEvict(value = "banking:account:dto", allEntries = true),
            @CacheEvict(value = "banking:transactions:list", allEntries = true),
            @CacheEvict(value = "banking:transactions:user", allEntries = true)
    })
    @Transactional(timeout = 10)
    public void verifyAndCredit(PaymentVerifyRequest req, String key) {
        boolean verified = verifySignature(
                req.razorpay_order_id(),
                req.razorpay_payment_id(),
                req.razorpay_signature()
        );
        if (!verified) {
            throw new BadRequestException("Invalid Razorpay signature");
        }
        double amount = parseAmount(req.amount());
        creditTopupIdempotent(req.username(), amount, paymentIdempotencyKey(req.razorpay_payment_id()));
    }

    public enum CreditOutcome { CREDITED, ALREADY_CREDITED }

    @Caching(evict = {
            @CacheEvict(value = "banking:account:dto", allEntries = true),
            @CacheEvict(value = "banking:transactions:list", allEntries = true),
            @CacheEvict(value = "banking:transactions:user", allEntries = true)
    })
    @Transactional(timeout = 10)
    public CreditOutcome creditTopupIdempotent(String username, double amount, String idempotencyKey) {
        if (bankService.isIdempotent(idempotencyKey)) {
            log.info("razorpay topup already processed; skipping (idempotencyKey={})", idempotencyKey);
            return CreditOutcome.ALREADY_CREDITED;
        }
        if (amount <= 0) {
            throw new BadRequestException("Invalid amount: " + amount);
        }

        User user = userService.ifUserExists(username);
        BankAccountResponseDto account = bankService.getAccountByid(user.getId());

        bankService.updateUserBalance(username, account.balance() + amount);

        Transaction txn = buildTransaction(user, account, amount);

        transactionService.checkFraud(txn);

        IdempotencyKey row = new IdempotencyKey();
        row.setKey(idempotencyKey);
        row.setCreatedAt(LocalDateTime.now());
        idempotencyRepository.save(row);
        return CreditOutcome.CREDITED;
    }

    public static String paymentIdempotencyKey(String razorpayPaymentId) {
        return "rzp_pay:" + razorpayPaymentId;
    }

    public static class WebhookOutcome {
        public final int httpStatus;
        public final Map<String, Object> body;

        private WebhookOutcome(int httpStatus, Map<String, Object> body) {
            this.httpStatus = httpStatus;
            this.body = body;
        }

        public static WebhookOutcome ok(Map<String, Object> body) { return new WebhookOutcome(200, body); }
        public static WebhookOutcome badRequest(Map<String, Object> body) { return new WebhookOutcome(400, body); }
        public static WebhookOutcome unprocessable(Map<String, Object> body) { return new WebhookOutcome(422, body); }
        public static WebhookOutcome serverError(Map<String, Object> body) { return new WebhookOutcome(500, body); }
    }

    public WebhookOutcome processWebhookPayload(String rawBody) {
        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody);
        } catch (Exception e) {
            log.warn("razorpay webhook body parse failed: {}", e.toString());
            return WebhookOutcome.badRequest(Map.of("error", "invalid_json"));
        }

        String event = root.path("event").asText("");
        if (!"payment.captured".equals(event)) {
            log.info("razorpay webhook event {} acknowledged but not credited", event);
            return WebhookOutcome.ok(linkedMap("status", "ignored", "event", event));
        }

        JsonNode entity = root.path("payload").path("payment").path("entity");
        String paymentId = entity.path("id").asText("");
        long amountPaise = entity.path("amount").asLong(0);
        String username = entity.path("notes").path("username").asText("");

        if (paymentId.isBlank() || amountPaise <= 0) {
            log.warn("razorpay webhook payment.captured missing id/amount: {}", entity);
            return WebhookOutcome.unprocessable(Map.of("error", "missing_fields"));
        }
        if (username.isBlank()) {
            log.error("razorpay webhook payment.captured has no notes.username (paymentId={}). " +
                    "Order was created without the username note — check PaymentController flow.", paymentId);
            return WebhookOutcome.unprocessable(Map.of("error", "no_user_mapping"));
        }

        double amountRupees = amountPaise / 100.0;
        CreditOutcome outcome;
        try {
            outcome = creditTopupIdempotent(username, amountRupees, paymentIdempotencyKey(paymentId));
        } catch (Exception e) {
            log.error("razorpay webhook credit failed (paymentId={}, user={}): {}",
                    paymentId, username, e.toString());
            return WebhookOutcome.serverError(Map.of(
                    "error", "credit_failed",
                    "message", e.getMessage() == null ? "" : e.getMessage()));
        }

        String status = outcome == CreditOutcome.CREDITED ? "credited" : "already_credited";
        return WebhookOutcome.ok(linkedMap("status", status, "paymentId", paymentId));
    }

    private static Map<String, Object> linkedMap(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }

    private double parseAmount(String amountStr) {
        if (amountStr == null || amountStr.isBlank())
            throw new BadRequestException("Amount missing");

        double amount = Double.parseDouble(amountStr);
        if (amount <= 0)
            throw new BadRequestException("Invalid amount");

        return amount;
    }

    private Transaction buildTransaction(User user, BankAccountResponseDto account, double amount) {
        Transaction txn = new Transaction();
        txn.setSenderAccount("RAZORPAY_TOPUP");
        txn.setReceiverAccount(account.accountNumber());
        txn.setAmount(amount);
        txn.setBalance(account.balance());
        txn.setIsForeign(0);
        txn.setIsHighRisk(0);
        txn.setFraud_probability(0);
        txn.setIs_fraud(0);
        txn.setUserId(user.getId().intValue());
        return txn;
    }

    public Map<String, Object> prepareCheckoutOrder(Map<String, Object> data, String username) {
        try {
            int amount = (int) data.get("amount");
            String receipt = "txn_" + System.currentTimeMillis();

            Order order = createOrder(amount, receipt, username);

            JSONObject response = new JSONObject();
            response.put("orderId", order.get("id").toString());
            response.put("amount", order.get("amount").toString());
            response.put("currency", order.get("currency").toString());
            response.put("key", keyId);

            return response.toMap();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create Razorpay order", e);
        }
    }
}