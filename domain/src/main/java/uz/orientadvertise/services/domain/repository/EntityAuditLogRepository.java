package uz.orientadvertise.services.domain.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import uz.orientadvertise.services.domain.model.EntityAuditLog;

public interface EntityAuditLogRepository extends JpaRepository<EntityAuditLog, Long> {

    List<EntityAuditLog> findByEntityTypeAndEntityIdOrderByChangedAtDesc(String entityType, Long entityId);
}
