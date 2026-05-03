package backend.backend.model;

import backend.backend.configuration.PiiConverter;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Data
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String username;
    @Column(nullable = false)
    private String firstname;
    @Column(nullable = false)
    private String lastname;
    @Column(nullable = false)
    private String password;
    @Column(nullable = false,unique = true)
    private String email;
    @Column(nullable = false,unique = true)
    private String mobile;
    @Column(nullable = false)
    private String role;

    @Column(name = "dob")
    private LocalDate dob;

    private int creditScore;
    private LocalDateTime updatedAt = LocalDateTime.now();
    private boolean hasLoan;
    private double loanamount;
    private double remaining;
    private LocalDateTime dueDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "kyc_status", nullable = false, length = 32)
    private KycStatus kycStatus = KycStatus.NONE;

    @Column(name = "kyc_submitted_at")
    private Instant kycSubmittedAt;

    @Column(name = "kyc_decided_at")
    private Instant kycDecidedAt;

    @Column(name = "findoc_kyc_application_id", length = 64)
    private String findocKycApplicationId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "kyc_report_json", columnDefinition = "jsonb")
    private String kycReportJson;

    @Column(name = "kyc_decision_reason", columnDefinition = "TEXT")
    private String kycDecisionReason;

    @Convert(converter = PiiConverter.class)
    @Column(name = "aadhaar_number_encrypted", length = 512)
    private String aadhaarNumber;

    @Convert(converter = PiiConverter.class)
    @Column(name = "pan_number_encrypted", length = 512)
    private String panNumber;

    @Column(name = "account_active", nullable = false)
    private boolean accountActive = false;
}
