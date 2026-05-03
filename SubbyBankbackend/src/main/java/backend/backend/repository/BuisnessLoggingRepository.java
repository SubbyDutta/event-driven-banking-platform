package backend.backend.repository;

import backend.backend.model.BuisnessLog;
import backend.backend.model.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BuisnessLoggingRepository extends JpaRepository<BuisnessLog, Long> {

    @Query("SELECT b FROM BuisnessLog b WHERE b.action LIKE %:action%")
    Page<BuisnessLog> findByAction(@Param("action") String action, Pageable pageable);

    Page<BuisnessLog> findAllByOrderByTimestampDesc(Pageable pageable);

}
