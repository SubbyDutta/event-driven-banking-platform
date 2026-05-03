package backend.backend.service;

import backend.backend.configuration.SubbyProperties;
import backend.backend.events.KycDecisionMade;
import backend.backend.events.KycFinalized;
import backend.backend.events.KycSubmitted;
import backend.backend.messaging.OutboxEventPublisher;
import backend.backend.model.KycDecisionOverride;
import backend.backend.model.KycStatus;
import backend.backend.model.User;
import backend.backend.repository.KycDecisionOverrideRepository;
import backend.backend.repository.UserRepository;
import backend.backend.service.findoc.FindocOverrideRequest;
import backend.backend.service.findoc.FindocVerifyClient;
import backend.backend.service.findoc.RetriableException;
import backend.backend.storage.DocType;
import backend.backend.storage.S3DocumentStorage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class AdminKycOverrideService {

    private static final Logger log = LoggerFactory.getLogger(AdminKycOverrideService.class);

    private static final long MAX_DOC_BYTES = 10L * 1024 * 1024;
    private static final Set<String> ALLOWED_DOC_TYPES = Set.of(
            "application/pdf", "image/jpeg", "image/jpg", "image/png");
    private static final Set<String> ALLOWED_SELFIE_TYPES = Set.of(
            "image/jpeg", "image/jpg", "image/png");

    private final UserRepository userRepository;
    private final KycDecisionOverrideRepository overrideRepository;
    private final OutboxEventPublisher outbox;
    private final SubbyProperties properties;
    private final FindocVerifyClient findoc;
    private final AdminCacheEvictionService cacheEvictor;
    private final ObjectMapper objectMapper;
    private final S3DocumentStorage s3;

    public AdminKycOverrideService(UserRepository userRepository,
                                   KycDecisionOverrideRepository overrideRepository,
                                   OutboxEventPublisher outbox,
                                   SubbyProperties properties,
                                   FindocVerifyClient findoc,
                                   AdminCacheEvictionService cacheEvictor,
                                   ObjectMapper objectMapper,
                                   S3DocumentStorage s3) {
        this.userRepository = userRepository;
        this.overrideRepository = overrideRepository;
        this.outbox = outbox;
        this.properties = properties;
        this.findoc = findoc;
        this.cacheEvictor = cacheEvictor;
        this.objectMapper = objectMapper;
        this.s3 = s3;
    }

    public static class Result {
        public final boolean userNotFound;
        public final String validationError;
        public final String originalDecision;
        public final KycStatus newStatus;
        public final boolean accountActive;
        public final String overriddenBy;
        public final String findocNotifyError;

        private Result(boolean userNotFound, String validationError,
                       String originalDecision, KycStatus newStatus, boolean accountActive,
                       String overriddenBy, String findocNotifyError) {
            this.userNotFound = userNotFound;
            this.validationError = validationError;
            this.originalDecision = originalDecision;
            this.newStatus = newStatus;
            this.accountActive = accountActive;
            this.overriddenBy = overriddenBy;
            this.findocNotifyError = findocNotifyError;
        }

        public static Result userNotFound() { return new Result(true, null, null, null, false, null, null); }
        public static Result invalid(String error) { return new Result(false, error, null, null, false, null, null); }
        public static Result applied(String original, KycStatus newStatus, boolean accountActive,
                                     String admin, String findocErr) {
            return new Result(false, null, original, newStatus, accountActive, admin, findocErr);
        }

        public Map<String, Object> toResponseBody(Long userId) {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("userId", String.valueOf(userId));
            resp.put("originalDecision", originalDecision);
            resp.put("newDecision", newStatus.name());
            resp.put("accountActive", accountActive);
            resp.put("overriddenBy", overriddenBy);
            if (findocNotifyError != null) resp.put("findocNotifyError", findocNotifyError);
            return resp;
        }
    }

    @Transactional
    public Result override(Long userId, String decisionRaw, String reason,
                           boolean notifyFindoc, String adminName) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) return Result.userNotFound();

        KycStatus newStatus = parseDecision(decisionRaw);
        if (newStatus == null) {
            return Result.invalid("decision must be one of KYC_APPROVED, KYC_REJECTED, KYC_MANUAL_REVIEW");
        }

        String originalDecision = user.getKycStatus() != null ? user.getKycStatus().name() : KycStatus.NONE.name();

        KycDecisionOverride overrideRow = new KycDecisionOverride();
        overrideRow.setUserId(userId);
        overrideRow.setOriginalDecision(originalDecision);
        overrideRow.setNewDecision(newStatus.name());
        overrideRow.setReason(reason);
        overrideRow.setOverriddenBy(adminName);
        overrideRow.setNotifyFindoc(notifyFindoc);
        overrideRepository.save(overrideRow);

        user.setKycStatus(newStatus);
        user.setKycDecisionReason(reason);
        user.setKycDecidedAt(Instant.now());
        if (newStatus == KycStatus.KYC_APPROVED) {
            user.setAccountActive(true);
        }
        userRepository.save(user);

        cacheEvictor.evictKycCaches();

        String findocErr = notifyFindocIfNeeded(user, newStatus, reason, notifyFindoc, adminName);

        String userIdStr = String.valueOf(userId);
        outbox.publish(properties.topics().kycEvents(),
                KycDecisionMade.fromAdminOverride(userIdStr, newStatus.name(), reason, adminName));
        if (newStatus == KycStatus.KYC_APPROVED) {
            outbox.publish(properties.topics().kycEvents(),
                    KycFinalized.forUser(userIdStr, true));
        }

        log.info("admin.kyc.override userId={} from={} to={} admin={} notifyFindoc={} findocErr={}",
                userId, originalDecision, newStatus, adminName, notifyFindoc, findocErr);

        return Result.applied(originalDecision, newStatus, user.isAccountActive(), adminName, findocErr);
    }

    private String notifyFindocIfNeeded(User user, KycStatus newStatus, String reason,
                                        boolean notifyFindoc, String adminName) {
        if (!notifyFindoc || user.getFindocKycApplicationId() == null) return null;
        String findocRec = toFindocRecommendation(newStatus);
        try {
            findoc.overrideDecision(user.getFindocKycApplicationId(),
                    FindocOverrideRequest.builder()
                            .newRecommendation(findocRec)
                            .reason(adminName + ": " + reason)
                            .build());
            return null;
        } catch (RetriableException re) {
            log.warn("admin.kyc.override findoc notify retriable userId={} err={}", user.getId(), re.toString());
            return "findoc-verify unreachable; override recorded locally only";
        } catch (Exception e) {
            log.warn("admin.kyc.override findoc notify failed userId={} err={}", user.getId(), e.toString());
            return e.getMessage();
        }
    }

    private static KycStatus parseDecision(String raw) {
        if (raw == null) return null;
        return switch (raw.toUpperCase()) {
            case "APPROVED", "KYC_APPROVED" -> KycStatus.KYC_APPROVED;
            case "REJECTED", "KYC_REJECTED" -> KycStatus.KYC_REJECTED;
            case "MANUAL_REVIEW", "KYC_MANUAL_REVIEW" -> KycStatus.KYC_MANUAL_REVIEW;
            default -> null;
        };
    }

    private static String toFindocRecommendation(KycStatus status) {
        return switch (status) {
            case KYC_APPROVED -> "verified";
            case KYC_REJECTED -> "reject";
            case KYC_MANUAL_REVIEW -> "manual_review";
            default -> "manual_review";
        };
    }

    public Optional<Map<String, Object>> adminDetail(Long userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) return Optional.empty();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("user", toDetailItem(user));
        response.put("report", parseJson(user.getKycReportJson()));
        response.put("overrides", overrideRepository.findByUserIdOrderByCreatedAtDesc(userId));

        if (user.getFindocKycApplicationId() != null) {
            try {
                JsonNode detail = findoc.getApplicationDetail(user.getFindocKycApplicationId());
                response.put("findocApplication", detail);
            } catch (Exception e) {
                log.warn("admin.kyc.detail findoc proxy failed userId={} findocAppId={} err={}",
                        userId, user.getFindocKycApplicationId(), e.toString());
                response.put("findocApplication", null);
                response.put("findocApplicationError", e.getMessage());
            }
        } else {
            response.put("findocApplication", null);
        }

        return Optional.of(response);
    }

    private static Map<String, Object> toDetailItem(User u) {
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
        m.put("aadhaarNumber", u.getAadhaarNumber());
        m.put("panNumber", u.getPanNumber());
        m.put("decisionReason", u.getKycDecisionReason());
        return m;
    }

    private Object parseJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return json;
        }
    }

    public static class ApplyResult {
        public final int httpStatus;
        public final Map<String, Object> body;

        private ApplyResult(int httpStatus, Map<String, Object> body) {
            this.httpStatus = httpStatus;
            this.body = body;
        }

        public static ApplyResult ok(Map<String, Object> body) { return new ApplyResult(202, body); }
        public static ApplyResult conflict(Map<String, Object> body) { return new ApplyResult(409, body); }
        public static ApplyResult badRequest(String error) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("error", error);
            return new ApplyResult(400, m);
        }
    }

    @Transactional
    public ApplyResult applyKycWithDocuments(User user,
                                             MultipartFile aadhaar,
                                             MultipartFile pan,
                                             MultipartFile selfie) {
        KycStatus current = user.getKycStatus() == null ? KycStatus.NONE : user.getKycStatus();
        if (current.isInFlight()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("error", "KYC already in progress");
            m.put("kycStatus", current.name());
            return ApplyResult.conflict(m);
        }
        if (current == KycStatus.KYC_APPROVED) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("error", "Already verified");
            m.put("kycStatus", current.name());
            return ApplyResult.conflict(m);
        }

        String fileError = validateKycDoc("aadhaar", aadhaar, ALLOWED_DOC_TYPES);
        if (fileError != null) return ApplyResult.badRequest(fileError);
        fileError = validateKycDoc("pan", pan, ALLOWED_DOC_TYPES);
        if (fileError != null) return ApplyResult.badRequest(fileError);
        if (selfie != null && !selfie.isEmpty()) {
            fileError = validateKycDoc("selfie", selfie, ALLOWED_SELFIE_TYPES);
            if (fileError != null) return ApplyResult.badRequest(fileError);
        }

        String userId = String.valueOf(user.getId());
        Map<String, String> s3Keys = new LinkedHashMap<>();
        s3Keys.put("aadhaar", s3.putKycDocument(userId, DocType.AADHAAR, aadhaar));
        s3Keys.put("pan", s3.putKycDocument(userId, DocType.PAN, pan));
        if (selfie != null && !selfie.isEmpty()) {
            s3Keys.put("selfie", s3.putKycDocument(userId, DocType.OTHER, selfie));
        }

        user.setKycStatus(KycStatus.KYC_SUBMITTED);
        user.setKycSubmittedAt(Instant.now());
        user.setKycDecidedAt(null);
        user.setKycDecisionReason(null);
        userRepository.save(user);

        KycSubmitted event = KycSubmitted.forUser(
                userId, s3Keys,
                trimToNull(user.getFirstname() + " " + user.getLastname()),
                user.getEmail(), user.getMobile(),
                user.getDob());
        outbox.publish(properties.topics().kycEvents(), event);

        log.info("kyc.apply userId={} s3Keys={} eventId={}",
                userId, s3Keys.keySet(), event.getEventId());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("kycStatus", KycStatus.KYC_SUBMITTED.name());
        body.put("message", "KYC documents received; verification in progress");
        return ApplyResult.ok(body);
    }

    public Map<String, Object> kycReportPreview(String reportJson) {
        if (reportJson == null || reportJson.isBlank()) return null;
        try {
            JsonNode root = objectMapper.readTree(reportJson);
            Map<String, Object> out = new LinkedHashMap<>();
            copyIfPresent(root, out, "recommendation");
            copyIfPresent(root, out, "overallScore");
            copyIfPresent(root, out, "summary");
            JsonNode compliance = root.get("complianceSummary");
            if (compliance == null) compliance = root.get("compliance");
            if (compliance != null && !compliance.isNull()) {
                out.put("compliance", compliance);
            }
            JsonNode crossDoc = root.get("crossDoc");
            if (crossDoc == null) crossDoc = root.get("crossDocValidations");
            if (crossDoc != null && !crossDoc.isNull()) {
                out.put("crossDoc", crossDoc);
            }
            return out;
        } catch (Exception e) {
            log.warn("kyc.status report preview failed: {}", e.toString());
            return null;
        }
    }

    private static void copyIfPresent(JsonNode src, Map<String, Object> dst, String key) {
        JsonNode v = src.get(key);
        if (v != null && !v.isNull()) dst.put(key, v);
    }

    private static String validateKycDoc(String field, MultipartFile file, Set<String> allowed) {
        if (file == null || file.isEmpty()) return field + " is required";
        if (file.getSize() > MAX_DOC_BYTES) {
            return field + " exceeds 10 MB limit";
        }
        String ct = file.getContentType();
        if (ct == null || !allowed.contains(ct.toLowerCase())) {
            return field + " must be one of " + allowed + " (got " + ct + ")";
        }
        return null;
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
