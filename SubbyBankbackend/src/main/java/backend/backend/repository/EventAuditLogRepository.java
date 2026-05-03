package backend.backend.repository;

import backend.backend.model.EventAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventAuditLogRepository extends JpaRepository<EventAuditLog, Long> {
}
