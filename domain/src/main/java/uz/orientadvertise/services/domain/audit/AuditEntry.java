package uz.orientadvertise.services.domain.audit;

import java.time.Instant;

public record AuditEntry(
        String principal,
        String method,
        String path,
        int responseStatus,
        String requestBody,
        String responseBody,
        Instant timestamp
) {}
