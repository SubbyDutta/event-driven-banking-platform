package backend.backend.service.findoc;

import java.util.Map;

/**
 * Builder shape for {@link FindocVerifyClient#submitLoan(LoanSubmitRequest)}.
 * Mirrors the form fields on findoc-verify's {@code POST /api/v1/loan-origination/submit}
 * (see {@code findoc-verify/src/api/applications.py::submit_loan}).
 *
 * <p>Applicant identity fields ({@code applicantName}, {@code email}, {@code phone},
 * {@code applicantDob}) are pinned from the authenticated User row upstream;
 * NEVER populate them from user form input in a loan context. That anchor is
 * what enables findoc-verify's cross-doc validation to detect borrowed-docs
 * fraud (Layer 2 of our three-layer identity defense — see
 * {@code LoanFindocResultConsumer}).
 */
public class LoanSubmitRequest {

    private String externalId;
    private String applicantName;
    private String email;
    private String phone;
    private String applicantDob;

    /**
     * Required-document set: keyed by the findoc-verify form field name
     * ({@code bank_statement_1}, {@code payslip_2}, {@code employment_letter},
     * {@code itr}, {@code credit_report}, ...).
     */
    private Map<String, DocBytes> documents;

    public static class DocBytes {
        private final byte[] bytes;
        private final String filename;
        private final String contentType;

        public DocBytes(byte[] bytes, String filename, String contentType) {
            this.bytes = bytes;
            this.filename = filename == null || filename.isBlank() ? "unnamed" : filename;
            this.contentType = contentType == null || contentType.isBlank()
                    ? "application/octet-stream" : contentType;
        }

        public byte[] getBytes() { return bytes; }
        public String getFilename() { return filename; }
        public String getContentType() { return contentType; }
    }

    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }
    public String getApplicantName() { return applicantName; }
    public void setApplicantName(String applicantName) { this.applicantName = applicantName; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getApplicantDob() { return applicantDob; }
    public void setApplicantDob(String applicantDob) { this.applicantDob = applicantDob; }
    public Map<String, DocBytes> getDocuments() { return documents; }
    public void setDocuments(Map<String, DocBytes> documents) { this.documents = documents; }
}
