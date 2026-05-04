package backend.backend.service.loan;

import backend.backend.model.User;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves the Layer-3 identity-fraud defense works end-to-end against realistic
 * loan-report JSON shapes. These scenarios mirror the failure modes we built
 * the guard for:
 *
 * <ul>
 *   <li>Clean match → pass. (The KYC-verified user uploaded their own docs.)</li>
 *   <li>Different PAN on any extracted document → hard reject. (PAN is unique;
 *       one mismatch is definitive proof the doc is someone else's.)</li>
 *   <li>Different DOB on any extracted document → hard reject.</li>
 *   <li>Different name on 2+ docs → hard reject. (Single-doc OCR noise is
 *       tolerated; two independent docs naming a different person are not.)</li>
 *   <li>Initial-style name variance → pass. ("Subham K Dutta" vs "Subham Dutta"
 *       must not false-positive.)</li>
 * </ul>
 */
class KycIdentityGuardTest {

    private KycIdentityGuard guard;
    private ObjectMapper om;

    @BeforeEach
    void setup() {
        guard = new KycIdentityGuard();
        om = new ObjectMapper();
    }

    private User subham() {
        User u = new User();
        u.setFirstname("Subham");
        u.setLastname("Dutta");
        u.setPanNumber("ABCDE1234F");
        u.setDob(LocalDate.of(1998, 3, 15));
        return u;
    }

    @Test
    void clean_match_passes() throws Exception {
        JsonNode report = om.readTree("""
            {
              "report": {
                "documents": [
                  { "extracted": { "name": "SUBHAM DUTTA", "pan": "ABCDE1234F", "dob": "1998-03-15" } },
                  { "extracted": { "accountHolderName": "Subham Dutta" } }
                ]
              }
            }
            """);
        KycIdentityGuard.Report r = guard.evaluate(subham(), report);
        assertFalse(r.isRejected(), "clean docs should not trigger reject");
        assertEquals(0, r.pans.size());
        assertEquals(0, r.dobs.size());
    }

    @Test
    void pan_mismatch_rejects_hard() throws Exception {

        JsonNode report = om.readTree("""
            {
              "report": {
                "documents": [
                  { "extracted": { "name": "SUBHAM DUTTA", "pan": "XYZAB9999Z" } }
                ]
              }
            }
            """);
        KycIdentityGuard.Report r = guard.evaluate(subham(), report);
        assertTrue(r.isRejected(), "PAN mismatch should be a hard reject");
        assertEquals(1, r.pans.size());
        assertTrue(r.getSummary().toLowerCase().contains("pan"));
    }

    @Test
    void dob_mismatch_rejects_hard() throws Exception {
        JsonNode report = om.readTree("""
            {
              "complianceChecks": [
                { "name": "dob_consistency", "status": "pass",
                  "details": { "dateOfBirth": "1975-11-02" } }
              ]
            }
            """);
        KycIdentityGuard.Report r = guard.evaluate(subham(), report);
        assertTrue(r.isRejected(), "DOB mismatch should be a hard reject");
        assertEquals(1, r.dobs.size());
    }

    @Test
    void single_name_mismatch_tolerated_as_ocr_noise() throws Exception {
        JsonNode report = om.readTree("""
            {
              "report": {
                "documents": [
                  { "extracted": { "name": "Rajesh Kumar" } }
                ]
              }
            }
            """);
        KycIdentityGuard.Report r = guard.evaluate(subham(), report);
        assertFalse(r.isRejected(), "single name miss on one doc is OCR noise — not enough to reject");
        assertEquals(1, r.names.size());
    }

    @Test
    void two_name_mismatches_rejects() throws Exception {

        JsonNode report = om.readTree("""
            {
              "report": {
                "documents": [
                  { "extracted": { "employeeName": "Rajesh Kumar" } },
                  { "extracted": { "accountHolderName": "Rajesh Kumar" } }
                ]
              }
            }
            """);
        KycIdentityGuard.Report r = guard.evaluate(subham(), report);
        assertTrue(r.isRejected(), "name mismatches on 2 independent docs = reject");
        assertEquals(2, r.names.size());
    }

    @Test
    void middle_initial_variance_does_not_false_positive() throws Exception {


        JsonNode report = om.readTree("""
            {
              "report": {
                "documents": [
                  { "extracted": { "name": "SUBHAM K DUTTA" } },
                  { "extracted": { "name": "Subham Dutta" } }
                ]
              }
            }
            """);
        KycIdentityGuard.Report r = guard.evaluate(subham(), report);
        assertFalse(r.isRejected(), "middle initial should not trip the guard");
    }

    @Test
    void annotation_injects_identity_checks_block() throws Exception {
        JsonNode report = om.readTree("""
            {
              "report": {
                "documents": [
                  { "extracted": { "pan": "ZZZZZ0000Z" } }
                ]
              }
            }
            """);
        var mutable = (com.fasterxml.jackson.databind.node.ObjectNode) report;
        KycIdentityGuard.Report r = guard.evaluate(subham(), mutable);
        guard.annotate(mutable, r);
        assertTrue(mutable.has("identityChecks"));
        assertEquals(1, mutable.get("identityChecks").size());
        assertEquals("fail", mutable.get("identityChecks").get(0).get("status").asText());
    }

    @Test
    void pan_is_case_and_whitespace_insensitive() throws Exception {
        JsonNode report = om.readTree("""
            { "report": { "documents": [{ "extracted": { "pan": "  abcde1234f  " } }] } }
            """);
        KycIdentityGuard.Report r = guard.evaluate(subham(), report);
        assertFalse(r.isRejected(), "same PAN in different case + padding should match");
    }

    @Test
    void missing_user_pan_still_checks_name_and_dob() throws Exception {
        User u = subham();
        u.setPanNumber(null);
        JsonNode report = om.readTree("""
            {
              "report": {
                "documents": [
                  { "extracted": { "dob": "01/01/2000" } }
                ]
              }
            }
            """);
        KycIdentityGuard.Report r = guard.evaluate(u, report);
        assertTrue(r.isRejected(), "DOB check still fires without a PAN on file");
    }
}
