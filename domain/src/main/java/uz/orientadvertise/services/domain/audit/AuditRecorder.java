package uz.orientadvertise.services.domain.audit;

public interface AuditRecorder {

    void record(AuditEntry entry);
}
