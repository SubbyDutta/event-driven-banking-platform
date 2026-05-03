package backend.backend.service.loan;

import backend.backend.model.User;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Layer 3 of the loan-origination identity-fraud defense.
 *
 * <p><b>Threat model.</b> A user clears KYC with their own Aadhaar / PAN (the
 * encrypted values are stored on {@code users}). At loan-apply time they
 * upload payslips / bank statements / ITR / credit-report for someone else —
 * typically a person with higher income — to spoof creditworthiness. Layers
 * 1 (controller pins applicant identity from the User row, never trusts the
 * form) and 2 (findoc-verify's cross-doc validation, anchored by the pinned
 * identity) catch most of these. This guard is the last line: it walks the
 * full findoc-verify report looking for any identity field and compares it
 * against the KYC-verified ground truth. Any strong mismatch forces a hard
 * reject and the ML step is skipped.
 *
 * <p>The guard works even if findoc-verify misses the fraud: PAN is a
 * 10-character unique-per-person identifier, so a PAN extracted from any loan
 * document that differs from the user's KYC PAN is by definition a different
 * person. Name fuzzy match uses token-set overlap with a conservative
 * threshold.
 *
 * <p>Output: a {@link Report} summary the caller persists into
 * {@code loan_report_json.identityChecks[]} (a Java-side enrichment) and a
 * boolean recommendation to reject.
 */
@Component
public class KycIdentityGuard {

    private static final Logger log = LoggerFactory.getLogger(KycIdentityGuard.class);

    /** PAN format: AAAAA9999A. */
    private static final Pattern PAN_RX = Pattern.compile("^[A-Z]{5}[0-9]{4}[A-Z]$");
    /** Name tokens we walk for, lowercased. */
    private static final Set<String> NAME_FIELDS = new HashSet<>(Arrays.asList(
            "name", "applicantname", "applicant_name", "fullname", "full_name",
            "customername", "customer_name", "accountholdername", "account_holder_name",
            "beneficiary", "beneficiaryname", "beneficiary_name",
            "employeename", "employee_name", "employeedName",
            "taxpayername", "tax_payer_name", "taxpayer",
            "nameonpan", "name_on_pan", "nameonaadhaar", "name_on_aadhaar",
            "holdername", "holder_name"
    ));
    private static final Set<String> PAN_FIELDS = new HashSet<>(Arrays.asList(
            "pan", "pannumber", "pan_number", "permanent_account_number",
            "panno", "pan_no"
    ));
    private static final Set<String> DOB_FIELDS = new HashSet<>(Arrays.asList(
            "dob", "dateofbirth", "date_of_birth", "birthdate", "birth_date",
            "dateof_birth"
    ));

    /** Name tokens short enough to be noise — "mr", "mrs", "s/o", initials. */
    private static final Set<String> NAME_STOPWORDS = new HashSet<>(Arrays.asList(
            "mr", "mrs", "ms", "shri", "smt", "dr", "kumar", "kumari",
            "son", "daughter", "wife", "so", "do", "wo", "s", "d", "w"
    ));

    /**
     * A text value at a {@code name}-class field qualifies as a candidate
     * applicant name only if it looks like a human name — at least two
     * whitespace-separated tokens. Compliance-check names and similar
     * metadata slugs ("pan_format", "dob_consistency") fail this shape test
     * and are ignored, preventing false-positive mismatches when the walker
     * traverses complianceChecks[] / fraudSignals[] subtrees.
     */
    private static boolean looksLikeHumanName(String raw) {
        if (raw == null) return false;
        String trimmed = raw.trim();
        if (trimmed.length() < 3) return false;

        if (!trimmed.contains(" ") && !trimmed.contains("\t")) return false;

        if (trimmed.contains("_") || trimmed.matches(".*[a-z]-[a-z].*")) return false;
        return true;
    }

    /** Name token-set overlap threshold below which we flag a mismatch. */
    private static final double NAME_MATCH_THRESHOLD = 0.5;

    public Report evaluate(User user, JsonNode loanReport) {
        Report report = new Report();
        if (user == null) {
            return report;
        }

        String expectedName = normalizeName(
                safe(user.getFirstname()) + " " + safe(user.getLastname()));
        String expectedPan = user.getPanNumber() == null ? null
                : user.getPanNumber().trim().toUpperCase();
        LocalDate expectedDob = user.getDob();

        if (loanReport == null || loanReport.isNull()) {
            return report;
        }

        List<String> names = new ArrayList<>();
        List<String> pans = new ArrayList<>();
        List<String> dobs = new ArrayList<>();
        walk(loanReport, names, pans, dobs, new ArrayList<>(), 0);

        if (expectedPan != null && !expectedPan.isBlank()) {
            for (String extracted : pans) {
                String norm = extracted.trim().toUpperCase();
                if (!PAN_RX.matcher(norm).matches()) continue;
                if (!norm.equals(expectedPan)) {
                    report.pans.add(new Finding("pan_mismatch",
                            "KYC PAN differs from PAN found in loan documents",
                            expectedPan, norm));
                }
            }
        }

        if (!expectedName.isBlank()) {
            Set<String> expectedTokens = tokenize(expectedName);
            for (String extracted : names) {
                String normExtracted = normalizeName(extracted);
                if (normExtracted.isBlank()) continue;
                Set<String> extractedTokens = tokenize(normExtracted);
                if (extractedTokens.isEmpty()) continue;
                double overlap = tokenSetOverlap(expectedTokens, extractedTokens);
                if (overlap < NAME_MATCH_THRESHOLD) {
                    report.names.add(new Finding("name_mismatch",
                            String.format("Name token overlap %.2f below threshold %.2f",
                                    overlap, NAME_MATCH_THRESHOLD),
                            expectedName, normExtracted));
                }
            }
        }

        if (expectedDob != null) {
            String expectedIso = expectedDob.toString();
            for (String extracted : dobs) {
                LocalDate parsed = tryParseDob(extracted);
                if (parsed == null) continue;
                if (!parsed.toString().equals(expectedIso)) {
                    report.dobs.add(new Finding("dob_mismatch",
                            "KYC DOB differs from DOB found in loan documents",
                            expectedIso, parsed.toString()));
                }
            }
        }

        boolean reject = !report.pans.isEmpty()
                || !report.dobs.isEmpty()
                || report.names.size() >= 2;
        report.rejected = reject;
        if (reject) {
            report.summary = buildSummary(report);
        }
        return report;
    }

    /** Append an {@code identityChecks} array to the stored report JSON so the
     *  admin UI shows exactly which field on which doc failed. */
    public void annotate(ObjectNode reportRoot, Report r) {
        if (reportRoot == null || r == null) return;
        ArrayNode arr = reportRoot.putArray("identityChecks");
        for (Finding f : r.pans) arr.add(toNode(reportRoot, f));
        for (Finding f : r.names) arr.add(toNode(reportRoot, f));
        for (Finding f : r.dobs) arr.add(toNode(reportRoot, f));
    }

    /** DFS into every JsonNode capturing values at recognized field names. */
    private void walk(JsonNode node, List<String> names, List<String> pans,
                      List<String> dobs, List<String> path, int depth) {
        if (node == null || node.isNull() || depth > 12) return;

        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                String fieldLower = entry.getKey().toLowerCase(Locale.ROOT).replace("-", "_");
                String collapsed = fieldLower.replace("_", "");

                path.add(fieldLower);
                JsonNode v = entry.getValue();
                if (v.isTextual()) {
                    String text = v.asText();
                    if (text == null || text.isBlank()) {
                        path.remove(path.size() - 1);
                        return;
                    }
                    if (NAME_FIELDS.contains(collapsed) || NAME_FIELDS.contains(fieldLower)) {

                        if (looksLikeHumanName(text)) {
                            names.add(text);
                        }
                    } else if (PAN_FIELDS.contains(collapsed) || PAN_FIELDS.contains(fieldLower)) {
                        pans.add(text);
                    } else if (DOB_FIELDS.contains(collapsed) || DOB_FIELDS.contains(fieldLower)) {
                        dobs.add(text);
                    }
                } else {
                    walk(v, names, pans, dobs, path, depth + 1);
                }
                path.remove(path.size() - 1);
            });
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                walk(item, names, pans, dobs, path, depth + 1);
            }
        }
    }

    /** Uppercase, keep alphanumerics+spaces, collapse whitespace, drop stopwords. */
    static String normalizeName(String raw) {
        if (raw == null) return "";
        String stripped = raw.toUpperCase().replaceAll("[^A-Z0-9 ]", " ").replaceAll("\\s+", " ").trim();
        if (stripped.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (String tok : stripped.split(" ")) {
            String low = tok.toLowerCase();
            if (tok.length() >= 2 && !NAME_STOPWORDS.contains(low)) {
                if (out.length() > 0) out.append(' ');
                out.append(tok);
            }
        }
        return out.toString();
    }

    static Set<String> tokenize(String normalizedName) {
        Set<String> s = new HashSet<>();
        for (String tok : normalizedName.split(" ")) {
            if (!tok.isBlank()) s.add(tok);
        }
        return s;
    }

    /** |A ∩ B| / min(|A|, |B|) — lenient when one side has fewer tokens
     *  (e.g. "Subham Dutta" vs "Subham K Dutta" should still pass). */
    static double tokenSetOverlap(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        int intersect = 0;
        for (String t : a) if (b.contains(t)) intersect++;
        return (double) intersect / Math.min(a.size(), b.size());
    }

    /** Accept ISO, dd/mm/yyyy, dd-mm-yyyy, dd.mm.yyyy — common Indian doc formats. */
    private static LocalDate tryParseDob(String s) {
        if (s == null) return null;
        String trim = s.trim();
        try { return LocalDate.parse(trim); } catch (DateTimeParseException ignored) {}
        Matcher m = Pattern.compile("^(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{4})$").matcher(trim);
        if (m.matches()) {
            try {
                int d = Integer.parseInt(m.group(1));
                int mo = Integer.parseInt(m.group(2));
                int y = Integer.parseInt(m.group(3));
                return LocalDate.of(y, mo, d);
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static String buildSummary(Report r) {
        StringBuilder sb = new StringBuilder("Loan documents appear to belong to another person (identity mismatch with KYC record): ");
        List<String> bits = new ArrayList<>();
        if (!r.pans.isEmpty()) bits.add("PAN mismatch");
        if (!r.dobs.isEmpty()) bits.add("DOB mismatch");
        if (r.names.size() >= 2) bits.add("name mismatch on " + r.names.size() + " documents");
        return sb.append(String.join(", ", bits)).append(".").toString();
    }

    private static ObjectNode toNode(ObjectNode parent, Finding f) {
        ObjectNode n = parent.objectNode();
        n.put("check", f.check);
        n.put("status", "fail");
        n.put("details", f.details);
        n.put("expected", f.expected);
        n.put("found", f.found);
        return n;
    }

    private static String safe(String s) { return s == null ? "" : s; }

    public static final class Report {
        public final List<Finding> names = new ArrayList<>();
        public final List<Finding> pans = new ArrayList<>();
        public final List<Finding> dobs = new ArrayList<>();
        public boolean rejected;
        public String summary;

        public boolean isRejected() { return rejected; }
        public String getSummary() { return summary; }
    }

    public record Finding(String check, String details, String expected, String found) {}
}
