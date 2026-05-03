package backend.backend.model;

import jakarta.persistence.*;
import lombok.Data;

/**
 * BankAccount is 1:1 with User (by design — one user, one primary account).
 * Aadhaar / PAN used to live here duplicated from the user; after V2 added
 * them to {@code users} and V3 dropped them here, the single source of truth
 * is the user row. BankService reads encrypted PII off the user when it needs
 * to stamp something KYC-related.
 */
@Entity
@Data
public class BankAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    private User user;
    @Column(unique = true, nullable = false)
    private String accountNumber;
    private String type;
    private double balance;
    private boolean isBlocked;
    private boolean isVerified;
}
