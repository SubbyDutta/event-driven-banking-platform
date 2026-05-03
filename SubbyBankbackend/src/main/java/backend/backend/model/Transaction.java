package backend.backend.model;

import jakarta.persistence.*;
import lombok.Data;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Data
public class Transaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String senderAccount;
    private String receiverAccount;
    private double amount;
    private int hour;
    private double balance;
    private double avg_amount;
    private int isForeign;
    private int isHighRisk;
    private double fraud_probability;
    private int is_fraud;
    private int userId;
    private LocalDateTime timestamp;

    @PrePersist
    public void prePersist() {
        this.timestamp = LocalDateTime.now();
        this.hour = this.timestamp.getHour();
    }

}