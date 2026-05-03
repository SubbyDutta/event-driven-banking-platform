package backend.backend.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BuisnessLog {
    @Id
    @GeneratedValue
    private Long id;

    private String action;
    private String username;
    private String details;

    public BuisnessLog(String action, String username, String details, Instant timestamp) {

        this.action = action;
        this.username = username;
        this.details = details;
        this.timestamp = timestamp;
    }

    private Instant timestamp;

}
