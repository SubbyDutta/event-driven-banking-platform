package backend.backend.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
public class LoanEligibilityRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String username;
    private double income;
    private String pan;
    private String adhar;
    private double creditScore;
    private double requestedAmount;
    private double amount_to_pay;
    private double balance;
    private double avg_transaction;
    private double maxamoount;
    private boolean eligible;
    private double probability;
    private boolean applied = false;
    @PrePersist
    public void prePersist() {
        this.maxamoount=2.5*requestedAmount;
    }

}
