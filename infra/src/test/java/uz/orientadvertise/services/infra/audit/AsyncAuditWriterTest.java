package uz.orientadvertise.services.infra.audit;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.orientadvertise.services.domain.audit.AuditEntry;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AsyncAuditWriterTest {

    private AuditLogRepository repository;
    private AsyncAuditWriter writer;

    @BeforeEach
    void setUp() {
        repository = mock(AuditLogRepository.class);
        writer = new AsyncAuditWriter(repository);
    }

    @Test
    void write_savesAuditEntry() {
        var entry = new AuditEntry("admin", "POST", "/service/files", 200,
                "{\"file\":\"test.txt\"}", "{\"objectName\":\"abc\"}", Instant.now());

        when(repository.save(any(AuditLog.class))).thenReturn(new AuditLog(
                "admin", "POST", "/service/files", 200, null, null, Instant.now()));

        writer.record(entry);
        verify(repository).save(any(AuditLog.class));
    }

    @Test
    void write_doesNotThrowOnRepositoryFailure() {
        var entry = new AuditEntry("admin", "DELETE", "/service/files/x", 204,
                null, null, Instant.now());

        when(repository.save(any(AuditLog.class))).thenThrow(new RuntimeException("DB down"));

        assertDoesNotThrow(() -> writer.record(entry));
    }

    @Test
    void write_handlesNullBodies() {
        var entry = new AuditEntry("admin", "DELETE", "/service/files/x", 204,
                null, null, Instant.now());

        when(repository.save(any(AuditLog.class))).thenReturn(new AuditLog(
                "admin", "DELETE", "/service/files/x", 204, null, null, Instant.now()));

        assertDoesNotThrow(() -> writer.record(entry));
        verify(repository).save(any(AuditLog.class));
    }
}
