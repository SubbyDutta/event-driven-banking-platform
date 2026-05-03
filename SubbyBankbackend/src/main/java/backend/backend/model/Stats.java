package backend.backend.model;

import jakarta.persistence.Entity;
import lombok.Data;

@Data
public class Stats {
    private double totalTransactions;
    private double totalAccounts;
    private double totalUsers;

}
