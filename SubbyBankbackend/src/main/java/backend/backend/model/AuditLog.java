package backend.backend.model;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Data
public class AuditLog {

    @Id
    @GeneratedValue
    private Long id;

    private String username;
    private String endpoint;
    private String method;
    private int statusCode;
    private LocalDateTime timestamp;

}
