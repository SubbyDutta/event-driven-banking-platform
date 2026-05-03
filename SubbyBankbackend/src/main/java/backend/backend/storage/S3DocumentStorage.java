package backend.backend.storage;

import backend.backend.configuration.SubbyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import java.io.IOException;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Thin adapter over the shared {@code subby-documents} S3 bucket used to stage
 * KYC / loan documents before findoc-verify fetches them.
 *
 * <p>Key layout:
 * <ul>
 *   <li>{@code kyc/{userId}/{docType}/{uuid}_{filename}}</li>
 *   <li>{@code loans/{loanAppId}/{docType}/{uuid}_{filename}}</li>
 * </ul>
 *
 * <p>Every upload:
 * <ul>
 *   <li>Is rejected above {@code subby.s3.max-upload-bytes} (default 10 MB).</li>
 *   <li>Has its SHA-256 computed and stored as S3 object metadata
 *       ({@code x-amz-meta-sha256}) for tamper detection.</li>
 *   <li>Is stored with a content-type from the original upload (sanitized).</li>
 * </ul>
 */
@Component
public class S3DocumentStorage {

    private static final Logger log = LoggerFactory.getLogger(S3DocumentStorage.class);

    private final S3Client s3Client;
    private final SubbyProperties properties;

    public S3DocumentStorage(S3Client s3Client, SubbyProperties properties) {
        this.s3Client = s3Client;
        this.properties = properties;
    }

    /** Upload for a KYC flow. */
    public String putKycDocument(String userId, DocType docType, MultipartFile file) {
        String key = String.format("kyc/%s/%s/%s_%s",
                sanitize(userId), docType.name().toLowerCase(), UUID.randomUUID(), sanitizeFilename(file));
        return putInternal(key, file);
    }

    /** Upload for a loan application flow. */
    public String putLoanDocument(String loanAppId, DocType docType, MultipartFile file) {
        String key = String.format("loans/%s/%s/%s_%s",
                sanitize(loanAppId), docType.name().toLowerCase(), UUID.randomUUID(), sanitizeFilename(file));
        return putInternal(key, file);
    }

    /** Generic overload matching the prompt signature. */
    public String put(String loanAppId, DocType docType, MultipartFile file) {
        return putLoanDocument(loanAppId, docType, file);
    }

    private String putInternal(String key, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file is empty");
        }
        long size = file.getSize();
        long max = properties.s3().maxUploadBytes();
        if (size > max) {
            throw new IllegalArgumentException(
                    "Upload " + size + " bytes exceeds limit " + max);
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read upload into memory", e);
        }

        String sha256 = sha256Hex(bytes);
        String contentType = Objects.requireNonNullElse(file.getContentType(), "application/octet-stream");

        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(properties.s3().bucket())
                .key(key)
                .contentType(contentType)
                .contentLength(size)
                .metadata(Map.of(
                        "sha256", sha256,
                        "original-filename", sanitizeFilename(file)
                ))
                .build();

        s3Client.putObject(request, RequestBody.fromBytes(bytes));
        log.info("s3.put bucket={} key={} bytes={} sha256={}",
                properties.s3().bucket(), key, size, sha256);
        return key;
    }

    /**
     * Issue a short-lived presigned GET URL for findoc-verify to download the
     * staged document. TTL comes from {@code subby.s3.presigned-ttl-seconds}.
     */
    public URI presignedDownloadUrl(String s3Key, Duration ttl) {
        Duration effectiveTtl = (ttl != null && !ttl.isZero() && !ttl.isNegative())
                ? ttl
                : Duration.ofSeconds(properties.s3().presignedTtlSeconds());

        GetObjectRequest getRequest = GetObjectRequest.builder()
                .bucket(properties.s3().bucket())
                .key(s3Key)
                .build();

        GetObjectPresignRequest presign = GetObjectPresignRequest.builder()
                .signatureDuration(effectiveTtl)
                .getObjectRequest(getRequest)
                .build();

        try (S3Presigner presigner = S3Presigner.builder()
                .s3Client(s3Client)
                .build()) {
            return presigner.presignGetObject(presign).url().toURI();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate presigned URL for " + s3Key, e);
        }
    }

    /**
     * Find the most-recently-uploaded KYC document of a given type for a user.
     * Used by the loan pipeline to forward the verified Aadhaar/PAN to
     * findoc-verify at loan-submit time without making the user re-upload —
     * which also closes an identity-security hole (a user cannot supply
     * someone else's loan ID docs; only their own KYC-verified ones are used).
     */
    public Optional<String> findLatestKycKey(String userId, DocType docType) {
        String prefix = String.format("kyc/%s/%s/", sanitize(userId), docType.name().toLowerCase());
        ListObjectsV2Request req = ListObjectsV2Request.builder()
                .bucket(properties.s3().bucket())
                .prefix(prefix)
                .build();
        ListObjectsV2Response resp = s3Client.listObjectsV2(req);
        List<S3Object> contents = resp.contents();
        if (contents == null || contents.isEmpty()) return Optional.empty();
        return contents.stream()
                .max(Comparator.comparing(S3Object::lastModified))
                .map(S3Object::key);
    }

    /** Download the raw bytes. Intended for admin/debug paths; prefer presigned URLs for callers. */
    public byte[] downloadBytes(String s3Key) {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(properties.s3().bucket())
                .key(s3Key)
                .build();
        try (var response = s3Client.getObject(request)) {
            return response.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to download " + s3Key, e);
        }
    }

    private static String sanitize(String segment) {
        if (segment == null || segment.isBlank()) {
            throw new IllegalArgumentException("path segment is required");
        }
        return segment.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static String sanitizeFilename(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) return "unnamed";

        String base = name.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) base = base.substring(slash + 1);
        return base.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available on this JVM", e);
        }
    }

}
