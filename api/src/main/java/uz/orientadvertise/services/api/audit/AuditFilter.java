package uz.orientadvertise.services.api.audit;

import java.io.IOException;
import java.time.Instant;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;
import uz.orientadvertise.services.common.util.SensitiveFieldMasker;
import uz.orientadvertise.services.domain.audit.AuditEntry;
import uz.orientadvertise.services.domain.audit.AuditRecorder;

@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class AuditFilter extends OncePerRequestFilter {

    private static final Set<String> AUDITED_METHODS = Set.of("POST", "PUT", "DELETE", "PATCH");
    private static final int MAX_BODY_LOG_SIZE = 10_000;

    private final AuditRecorder auditRecorder;

    public AuditFilter(AuditRecorder auditRecorder) {
        this.auditRecorder = auditRecorder;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        if (!AUDITED_METHODS.contains(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        var cachedRequest = new ContentCachingRequestWrapper(request);
        var cachedResponse = new ContentCachingResponseWrapper(response);

        try {
            filterChain.doFilter(cachedRequest, cachedResponse);
        } finally {
            writeAudit(cachedRequest, cachedResponse);
            cachedResponse.copyBodyToResponse();
        }
    }

    private void writeAudit(ContentCachingRequestWrapper request, ContentCachingResponseWrapper response) {
        try {
            var principal = extractPrincipal();
            var requestBody = extractBody(request.getContentAsByteArray());
            var responseBody = extractBody(response.getContentAsByteArray());

            var entry = new AuditEntry(
                    principal,
                    request.getMethod(),
                    request.getRequestURI(),
                    response.getStatus(),
                    SensitiveFieldMasker.mask(requestBody),
                    SensitiveFieldMasker.mask(responseBody),
                    Instant.now()
            );

            auditRecorder.record(entry);
        } catch (Exception e) {
            // Audit must never fail the original request
            logger.warn("Failed to create audit entry: " + e.getMessage());
        }
    }

    private String extractPrincipal() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()) {
            return auth.getName();
        }
        return "anonymous";
    }

    private String extractBody(byte[] content) {
        if (content == null || content.length == 0) {
            return null;
        }
        var body = new String(content, 0, Math.min(content.length, MAX_BODY_LOG_SIZE));
        if (content.length > MAX_BODY_LOG_SIZE) {
            body += "...[truncated]";
        }
        return body;
    }
}
