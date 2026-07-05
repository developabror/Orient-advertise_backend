package uz.orientadvertise.services.infra.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import uz.orientadvertise.services.domain.audit.AuditEntry;
import uz.orientadvertise.services.domain.audit.AuditRecorder;

@Component
public class AsyncAuditWriter implements AuditRecorder {

    private static final Logger log = LoggerFactory.getLogger(AsyncAuditWriter.class);

    private final AuditLogRepository repository;

    public AsyncAuditWriter(AuditLogRepository repository) {
        this.repository = repository;
    }

    @Override
    @Async("auditExecutor")
    public void record(AuditEntry entry) {
        try {
            var entity = new AuditLog(
                    entry.principal(),
                    entry.method(),
                    entry.path(),
                    entry.responseStatus(),
                    entry.requestBody(),
                    entry.responseBody(),
                    entry.timestamp()
            );
            repository.save(entity);
            log.debug("Audit logged: {} {} {} by {}", entry.method(), entry.path(),
                    entry.responseStatus(), entry.principal());
        } catch (Exception e) {
            // Audit write failure must NEVER fail the original request
            log.warn("Audit write failed (non-critical): {}", e.getMessage());
        }
    }
}
