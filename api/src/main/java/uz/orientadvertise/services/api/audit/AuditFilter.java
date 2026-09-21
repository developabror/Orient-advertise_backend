package uz.orientadvertise.services.api.audit;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.springframework.web.util.UrlPathHelper;
import uz.orientadvertise.services.domain.audit.AuditEntry;
import uz.orientadvertise.services.domain.audit.AuditRecorder;

@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class AuditFilter extends OncePerRequestFilter {

    private static final Set<String> AUDITED_METHODS = Set.of("POST", "PUT", "DELETE", "PATCH");

    /** Request caching stops just above this; bodies above it (request or response) are not parsed — only noted. */
    static final int MAX_CACHED_BYTES = 256 * 1024;

    /**
     * AUTH-06: requests whose bodies are credentials in their entirety (old/new passwords, a reset
     * token, a freshly minted API key) — the audit keeps method, path, status and principal only.
     * Matched on the DECODED path within the application, so {@code /api/%61uth/…} can't slip past.
     * Login and device registration are NOT here: their usernames/serials are the audit trail, and
     * their passwords/tokens are masked like everywhere else.
     */
    private static final Set<String> BODYLESS = Set.of(
            "POST /api/me/password",
            "POST /api/auth/reset-password",
            "POST /api/auth/refresh",
            "POST /api/admin/api-keys");
    static final String OMITTED_CREDENTIALS = "[omitted: credential endpoint]";
    /** Bodies of a failed device-agent call: never buffered, so never available. */
    static final String OMITTED_AGENT_BODIES = "[omitted: unaudited device-agent body]";

    /**
     * DATA-01: calls the DEVICE AGENT makes on its own, on a timer, with no human behind them.
     * A <b>successful</b> one is not audited at all, and neither request nor response is ever
     * buffered for these paths — an unaudited call costs nothing rather than being wrapped and then
     * thrown away. A <b>failed</b> one (status ≥ 400) still records a bodyless entry: see
     * {@link #recordOutcomeOnly}.
     *
     * <p>Why: every always-on device beats every 120 s and flushes playback on top of that, and each
     * audit row carries the whole request <i>and</i> response body. On the test server 98.6% of
     * {@code audit_log} was this traffic — roughly 130 MB per device over the retention window — and
     * <b>nothing reads the table</b>: there is no API and no UI over it. The rows are pure cost, and
     * they are also the least informative ones in it (the same beat, forever). The device's real
     * trail is {@code device.last_seen_at}, {@code event}, {@code playback_log} and the action rows.
     *
     * <p>Scope is deliberately narrow: only these five agent endpoints. Everything else under
     * {@code /api/devices/**} is an operator or admin write — register, issue an action, playlist
     * control, start/stop remote, open a re-registration window, edit, delete, location, volume —
     * and stays audited. Matched on method + the DECODED path within the application, like
     * {@link #BODYLESS}, so {@code /api/devices/1/%68eartbeat} cannot slip past.
     */
    private static final Map<String, List<String>> DEVICE_AGENT_ENDPOINTS = Map.of(
            "POST", List.of(
                    "/api/devices/*/heartbeat",
                    "/api/devices/*/sync/confirm",
                    "/api/devices/*/actions/*/confirm",
                    "/api/devices/*/playback",
                    "/api/devices/*/remote/*/ack"));

    /** Thread-safe and pattern-caching; {@code *} spans one path segment, never a {@code /}. */
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

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

        if (isDeviceAgentTraffic(request)) {
            try {
                filterChain.doFilter(request, response);
            } finally {
                recordOutcomeOnly(request, response);
            }
            return;
        }

        var cachedRequest = new ContentCachingRequestWrapper(request, MAX_CACHED_BYTES + 1);
        var cachedResponse = new ContentCachingResponseWrapper(response);

        try {
            filterChain.doFilter(cachedRequest, cachedResponse);
        } finally {
            try {
                writeAudit(cachedRequest, cachedResponse);
            } finally {
                // Even an Error from auditing must not leave the client with an empty body.
                cachedResponse.copyBodyToResponse();
            }
        }
    }

    /**
     * A device-agent call that FAILED keeps a row: principal, method, path, status and time, with
     * no bodies — nothing was buffered, so there are none to keep, and the skip's whole point is
     * that buffering them is what cost the disk.
     *
     * <p>Without this, dropping the agent endpoints from the audit would also drop the only trail
     * of a device using its token against <i>another</i> device's id: the {@code @PreAuthorize}
     * IDOR rejection is a 403 raised during the controller invocation, so it unwinds back through
     * this filter and is recorded here. Volume is not a concern — a healthy fleet produces none of
     * these, and a fleet producing many is exactly what an operator needs to see.
     *
     * <p>Note what this still cannot see: an authentication failure (401) is emitted by the
     * security filter chain, which runs <i>before</i> this filter and short-circuits, so it never
     * reaches here. That is unchanged by DATA-01 and is not what this method is for.
     */
    private void recordOutcomeOnly(HttpServletRequest request, HttpServletResponse response) {
        if (response.getStatus() < 400) {
            return;
        }
        try {
            auditRecorder.record(new AuditEntry(
                    extractPrincipal(),
                    request.getMethod(),
                    request.getRequestURI(),
                    response.getStatus(),
                    OMITTED_AGENT_BODIES,
                    OMITTED_AGENT_BODIES,
                    Instant.now()));
        } catch (Exception e) {
            // Audit must never fail the original request.
            logger.warn("Failed to create audit entry: " + e.getMessage());
        }
    }

    /** See {@link #DEVICE_AGENT_ENDPOINTS}. Called before anything is buffered. */
    private boolean isDeviceAgentTraffic(HttpServletRequest request) {
        List<String> patterns = DEVICE_AGENT_ENDPOINTS.get(request.getMethod());
        if (patterns == null) {
            return false;
        }
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        for (String pattern : patterns) {
            if (PATH_MATCHER.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    private void writeAudit(ContentCachingRequestWrapper request, ContentCachingResponseWrapper response) {
        try {
            String requestBody;
            String responseBody;
            if (BODYLESS.contains(request.getMethod() + " "
                    + UrlPathHelper.defaultInstance.getPathWithinApplication(request))) {
                requestBody = OMITTED_CREDENTIALS;
                responseBody = OMITTED_CREDENTIALS;
            } else {
                byte[] requestBytes = request.getContentAsByteArray();
                requestBody = requestBytes.length > MAX_CACHED_BYTES
                        ? "[omitted: body larger than " + MAX_CACHED_BYTES + " bytes]"
                        : AuditBodySanitizer.sanitize(requestBytes);
                responseBody = response.getContentSize() > MAX_CACHED_BYTES
                        ? "[omitted: " + response.getContentSize() + " bytes]"
                        : AuditBodySanitizer.sanitize(response.getContentAsByteArray());
            }

            var entry = new AuditEntry(
                    extractPrincipal(),
                    request.getMethod(),
                    request.getRequestURI(),
                    response.getStatus(),
                    requestBody,
                    responseBody,
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
}
