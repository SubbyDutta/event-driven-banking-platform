package backend.backend.service.findoc;

import backend.backend.configuration.SubbyProperties;
import backend.backend.messaging.NonRetriableException;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * HTTP client for findoc-verify. One bean owns all outbound calls so the
 * circuit-breaker wrapping, timeout, and API-key header are applied uniformly.
 *
 * <p>Error classification:
 * <ul>
 *   <li><b>4xx</b> (incl. 400 "Incomplete KYC document set", 401, 403, 409) →
 *       {@link NonRetriableException}. These never succeed on retry; the
 *       consumer should DLQ the event.</li>
 *   <li><b>5xx, network errors, timeouts</b> → {@link RetriableException}.
 *       Consumers should let these bubble so SQS redelivers.</li>
 * </ul>
 *
 * <p>The circuit breaker ("findoc") is tuned in application.yml; it opens after
 * the configured failure rate so a spiraling downstream doesn't exhaust the
 * thread pool with synchronous waits.
 */
@Component
public class FindocVerifyClient {

    private static final Logger log = LoggerFactory.getLogger(FindocVerifyClient.class);

    private final WebClient web;
    private final Duration timeout;

    public FindocVerifyClient(WebClient.Builder builder, SubbyProperties properties) {
        SubbyProperties.Findoc cfg = properties.findoc();
        WebClient.Builder b = builder.baseUrl(cfg.baseUrl());
        if (cfg.apiKey() != null && !cfg.apiKey().isBlank()) {
            b = b.defaultHeader("X-API-Key", cfg.apiKey());
        } else {
            log.warn("FindocVerifyClient constructed without an API key — calls will 401 unless findoc-verify is in bootstrap mode");
        }
        b = b.filter(propagateCorrelationId());
        this.web = b.build();
        this.timeout = Duration.ofSeconds(cfg.timeoutSeconds() <= 0 ? 60 : cfg.timeoutSeconds());
    }

    private static ExchangeFilterFunction propagateCorrelationId() {
        return ExchangeFilterFunction.ofRequestProcessor(req -> {
            String cid = MDC.get("correlationId");
            if (cid == null || cid.isBlank()) return Mono.just(req);
            return Mono.just(ClientRequest.from(req).header("X-Correlation-Id", cid).build());
        });
    }

    @CircuitBreaker(name = "findoc")
    public FindocSubmitResponse submitKyc(KycSubmitRequest req) {
        if (req == null) throw new IllegalArgumentException("req is required");
        if (req.getAadhaarBytes() == null || req.getPanBytes() == null) {
            throw new IllegalArgumentException("aadhaar + pan bytes are required for KYC submit");
        }

        MultipartBodyBuilder mb = new MultipartBodyBuilder();
        mb.part("applicant_name", req.getApplicantName());
        mb.part("email", req.getEmail());
        mb.part("phone", req.getPhone());
        if (req.getExternalId() != null) {
            mb.part("external_id", req.getExternalId());
        }
        if (req.getApplicantDob() != null && !req.getApplicantDob().isBlank()) {
            mb.part("applicant_dob", req.getApplicantDob());
        }
        addFilePart(mb, "aadhaar", req.getAadhaarBytes(),
                firstNonBlank(req.getAadhaarFilename(), "aadhaar.pdf"),
                firstNonBlank(req.getAadhaarContentType(), "application/octet-stream"));
        addFilePart(mb, "pan", req.getPanBytes(),
                firstNonBlank(req.getPanFilename(), "pan.pdf"),
                firstNonBlank(req.getPanContentType(), "application/octet-stream"));
        if (req.getSelfieBytes() != null) {
            addFilePart(mb, "selfie", req.getSelfieBytes(),
                    firstNonBlank(req.getSelfieFilename(), "selfie.jpg"),
                    firstNonBlank(req.getSelfieContentType(), "image/jpeg"));
        }

        try {
            return web.post()
                    .uri("/api/v1/kyc/submit")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(mb.build()))
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, resp -> resp.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(body -> new NonRetriableException(
                                    "findoc-verify 4xx on POST /kyc/submit: "
                                            + resp.statusCode().value() + " " + body)))
                    .onStatus(HttpStatusCode::is5xxServerError, resp -> resp.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(body -> new RetriableException(
                                    "findoc-verify 5xx on POST /kyc/submit: "
                                            + resp.statusCode().value() + " " + body)))
                    .bodyToMono(FindocSubmitResponse.class)
                    .block(timeout);
        } catch (NonRetriableException | RetriableException e) {
            throw e;
        } catch (Exception e) {

            throw new RetriableException("findoc-verify call failed: " + e.getMessage(), e);
        }
    }

    /**
     * Submit a full loan-origination package to findoc-verify.
     *
     * <p>Identity fields ({@code applicant_name}, {@code email}, {@code phone},
     * {@code applicant_dob}) are pinned from the authenticated User row — a
     * downstream Java consumer populated them from the KYC record rather than
     * from form input. This is Layer 1 of our three-layer identity defense
     * (see {@code LoanFindocResultConsumer}). findoc-verify's cross-doc
     * validation then compares these anchored values against each doc's
     * extracted name / PAN.
     *
     * <p>{@code external_id} is our {@code loanAppId} — findoc-verify treats a
     * repeat submission with the same value as idempotent and returns 200 with
     * the prior row instead of creating a duplicate.
     */
    @CircuitBreaker(name = "findoc")
    public FindocSubmitResponse submitLoan(LoanSubmitRequest req) {
        if (req == null) throw new IllegalArgumentException("req is required");
        if (req.getDocuments() == null || req.getDocuments().isEmpty()) {
            throw new IllegalArgumentException("At least one loan document is required");
        }

        MultipartBodyBuilder mb = new MultipartBodyBuilder();
        mb.part("applicant_name", req.getApplicantName());
        mb.part("email", req.getEmail());
        mb.part("phone", req.getPhone());
        if (req.getExternalId() != null) {
            mb.part("external_id", req.getExternalId());
        }
        if (req.getApplicantDob() != null && !req.getApplicantDob().isBlank()) {
            mb.part("applicant_dob", req.getApplicantDob());
        }

        req.getDocuments().forEach((fieldName, doc) -> {
            if (doc == null || doc.getBytes() == null) return;
            addFilePart(mb, fieldName, doc.getBytes(),
                    firstNonBlank(doc.getFilename(), fieldName + ".pdf"),
                    firstNonBlank(doc.getContentType(), "application/octet-stream"));
        });

        try {
            return web.post()
                    .uri("/api/v1/loan-origination/submit")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(mb.build()))
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, resp -> resp.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(body -> new NonRetriableException(
                                    "findoc-verify 4xx on POST /loan-origination/submit: "
                                            + resp.statusCode().value() + " " + body)))
                    .onStatus(HttpStatusCode::is5xxServerError, resp -> resp.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(body -> new RetriableException(
                                    "findoc-verify 5xx on POST /loan-origination/submit: "
                                            + resp.statusCode().value() + " " + body)))
                    .bodyToMono(FindocSubmitResponse.class)
                    .block(timeout);
        } catch (NonRetriableException | RetriableException e) {
            throw e;
        } catch (Exception e) {
            throw new RetriableException("findoc-verify call failed: " + e.getMessage(), e);
        }
    }

    /**
     * Proxy for the admin UI's KYC-detail page. Returns the raw findoc-verify
     * {@code ApplicationDetail} JSON so the admin screen can render the full
     * pipeline timeline (documents, compliance checks, cross-doc validations,
     * fraud signals, overrides) without the Java side needing to model each.
     */
    @CircuitBreaker(name = "findoc")
    public JsonNode getApplicationDetail(String applicationId) {
        if (applicationId == null || applicationId.isBlank()) {
            throw new IllegalArgumentException("applicationId is required");
        }
        try {
            return web.get()
                    .uri("/api/v1/applications/{id}", applicationId)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, resp -> resp.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(body -> new NonRetriableException(
                                    "findoc-verify 4xx on GET /applications/" + applicationId
                                            + ": " + resp.statusCode().value() + " " + body)))
                    .onStatus(HttpStatusCode::is5xxServerError, resp -> resp.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(body -> new RetriableException(
                                    "findoc-verify 5xx on GET /applications/" + applicationId
                                            + ": " + resp.statusCode().value() + " " + body)))
                    .bodyToMono(JsonNode.class)
                    .block(timeout);
        } catch (NonRetriableException | RetriableException e) {
            throw e;
        } catch (Exception e) {
            throw new RetriableException("findoc-verify call failed: " + e.getMessage(), e);
        }
    }

    @CircuitBreaker(name = "findoc")
    public void overrideDecision(String applicationId, FindocOverrideRequest body) {
        if (applicationId == null || applicationId.isBlank()) {
            throw new IllegalArgumentException("applicationId is required");
        }
        try {
            web.post()
                    .uri("/api/v1/applications/{id}/override", applicationId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, resp -> resp.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(b -> new NonRetriableException(
                                    "findoc-verify 4xx on POST /applications/" + applicationId
                                            + "/override: " + resp.statusCode().value() + " " + b)))
                    .onStatus(HttpStatusCode::is5xxServerError, resp -> resp.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(b -> new RetriableException(
                                    "findoc-verify 5xx on POST /applications/" + applicationId
                                            + "/override: " + resp.statusCode().value() + " " + b)))
                    .toBodilessEntity()
                    .block(timeout);
        } catch (NonRetriableException | RetriableException e) {
            throw e;
        } catch (Exception e) {
            throw new RetriableException("findoc-verify call failed: " + e.getMessage(), e);
        }
    }

    @CircuitBreaker(name = "findoc")
    public JsonNode listPolicyThresholds() {
        try {
            return web.get()
                    .uri("/api/v1/admin/policy/thresholds")
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, resp -> resp.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(b -> new NonRetriableException(
                                    "findoc-verify 4xx on GET /admin/policy/thresholds: "
                                            + resp.statusCode().value() + " " + b)))
                    .onStatus(HttpStatusCode::is5xxServerError, resp -> resp.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(b -> new RetriableException(
                                    "findoc-verify 5xx on GET /admin/policy/thresholds: "
                                            + resp.statusCode().value() + " " + b)))
                    .bodyToMono(JsonNode.class)
                    .block(timeout);
        } catch (NonRetriableException | RetriableException e) {
            throw e;
        } catch (Exception e) {
            throw new RetriableException("findoc-verify call failed: " + e.getMessage(), e);
        }
    }

    @CircuitBreaker(name = "findoc")
    public JsonNode updatePolicyThreshold(String key, Object body) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key is required");
        }
        try {
            return web.put()
                    .uri("/api/v1/admin/policy/thresholds/{key}", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, resp -> resp.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(b -> new NonRetriableException(
                                    "findoc-verify 4xx on PUT /admin/policy/thresholds/" + key
                                            + ": " + resp.statusCode().value() + " " + b)))
                    .onStatus(HttpStatusCode::is5xxServerError, resp -> resp.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(b -> new RetriableException(
                                    "findoc-verify 5xx on PUT /admin/policy/thresholds/" + key
                                            + ": " + resp.statusCode().value() + " " + b)))
                    .bodyToMono(JsonNode.class)
                    .block(timeout);
        } catch (NonRetriableException | RetriableException e) {
            throw e;
        } catch (Exception e) {
            throw new RetriableException("findoc-verify call failed: " + e.getMessage(), e);
        }
    }

    private static void addFilePart(MultipartBodyBuilder mb, String name, byte[] bytes,
                                    String filename, String contentType) {
        ByteArrayResource res = new ByteArrayResource(bytes) {
            @Override public String getFilename() { return filename; }
        };
        mb.part(name, res)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "form-data; name=\"" + name + "\"; filename=\"" + filename + "\"")
                .header(HttpHeaders.CONTENT_TYPE, contentType);
    }

    private static String firstNonBlank(String a, String fallback) {
        return (a != null && !a.isBlank()) ? a : fallback;
    }
}
