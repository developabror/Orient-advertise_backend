package uz.orientadvertise.services.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import uz.orientadvertise.services.domain.model.EntityAuditLog;
import uz.orientadvertise.services.domain.model.EntityAuditLog.Action;
import uz.orientadvertise.services.domain.repository.EntityAuditLogRepository;

@Service
public class EntityAuditService {

    private static final Logger log = LoggerFactory.getLogger(EntityAuditService.class);

    private final EntityAuditLogRepository repository;

    public EntityAuditService(EntityAuditLogRepository repository) {
        this.repository = repository;
    }

    @Async("auditExecutor")
    public void logChange(String entityType, Long entityId, Action action,
                          String changedBy, String oldValueJson, String newValueJson) {
        try {
            repository.save(new EntityAuditLog(entityType, entityId, action,
                    changedBy, oldValueJson, newValueJson));
        } catch (Exception e) {
            log.warn("Entity audit write failed (non-critical): {}", e.getMessage());
        }
    }

    public List<EntityAuditLog> getHistory(String entityType, Long entityId) {
        return repository.findByEntityTypeAndEntityIdOrderByChangedAtDesc(entityType, entityId);
    }
}
