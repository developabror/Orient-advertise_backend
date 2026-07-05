package uz.orientadvertise.services.api.advice;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import uz.orientadvertise.services.common.exception.AuthenticationException;
import uz.orientadvertise.services.common.exception.InvalidUploadException;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.common.exception.StorageUnavailableException;
import uz.orientadvertise.services.common.telegram.TelegramMessageBuilder;
import uz.orientadvertise.services.common.telegram.TelegramMessageBuilder.Severity;
import uz.orientadvertise.services.domain.notification.TelegramNotifier;
import uz.orientadvertise.services.service.exception.AssignmentTimeOverlapException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final int STACK_FRAMES = 5;

    @Value("${app.error.include-stacktrace:false}")
    private boolean includeStacktrace;

    // Both wired via ObjectProvider so @WebMvcTest slices that don't load infra (no
    // TelegramNotifier, no auditExecutor) still get a working GlobalExceptionHandler —
    // it simply skips the Telegram forward when either dep is absent.
    private final ObjectProvider<TelegramNotifier> telegramProvider;
    private final ObjectProvider<Executor> asyncExecutorProvider;

    public GlobalExceptionHandler(ObjectProvider<TelegramNotifier> telegramProvider,
                                    @Qualifier("auditExecutor") ObjectProvider<Executor> asyncExecutorProvider) {
        this.telegramProvider = telegramProvider;
        this.asyncExecutorProvider = asyncExecutorProvider;
    }

    // --- 401 Unauthorized ---

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthentication(AuthenticationException e) {
        var correlationId = correlationId();
        log.warn("Authentication failed [correlationId={}]: {}", correlationId, e.getMessage());
        return buildResponse(HttpStatus.UNAUTHORIZED, "Unauthorized", e.getMessage(), correlationId, null);
    }

    // --- 403 Forbidden ---

    @ExceptionHandler(uz.orientadvertise.services.common.exception.AccessForbiddenException.class)
    public ResponseEntity<ErrorResponse> handleAccessForbidden(
            uz.orientadvertise.services.common.exception.AccessForbiddenException e) {
        var correlationId = correlationId();
        log.debug("Access forbidden [correlationId={}]: {}", correlationId, e.getMessage());
        return buildResponse(HttpStatus.FORBIDDEN, "Forbidden", e.getMessage(), correlationId, null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException e) {
        var correlationId = correlationId();
        log.warn("Access denied [correlationId={}]: {}", correlationId, e.getMessage());
        return buildResponse(HttpStatus.FORBIDDEN, "Forbidden", "Insufficient permissions", correlationId, null);
    }

    // --- 404 Not Found ---

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException e) {
        var correlationId = correlationId();
        log.debug("Resource not found [correlationId={}]: {}", correlationId, e.getMessage());
        return buildResponse(HttpStatus.NOT_FOUND, "Not Found", e.getMessage(), correlationId, null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException e) {
        var correlationId = correlationId();
        return buildResponse(HttpStatus.NOT_FOUND, "Not Found",
                "No endpoint found for %s %s".formatted(e.getHttpMethod(), e.getResourcePath()),
                correlationId, null);
    }

    // --- 400 Bad Request (Validation) ---

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        var correlationId = correlationId();
        var fieldErrors = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldError(fe.getField(), fe.getDefaultMessage(), fe.getRejectedValue()))
                .toList();
        log.debug("Validation failed [correlationId={}]: {} field error(s)", correlationId, fieldErrors.size());
        return buildResponse(HttpStatus.BAD_REQUEST, "Validation Failed",
                "Request validation failed — see fieldErrors for details", correlationId, fieldErrors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException e) {
        var correlationId = correlationId();
        var fieldErrors = e.getConstraintViolations().stream()
                .map(cv -> new FieldError(
                        cv.getPropertyPath().toString(),
                        cv.getMessage(),
                        cv.getInvalidValue()))
                .toList();
        return buildResponse(HttpStatus.BAD_REQUEST, "Validation Failed",
                "Constraint violation — see fieldErrors for details", correlationId, fieldErrors);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleMethodValidation(HandlerMethodValidationException e) {
        var correlationId = correlationId();
        var fieldErrors = e.getAllErrors().stream()
                .map(err -> new FieldError("parameter", err.getDefaultMessage(), null))
                .toList();
        return buildResponse(HttpStatus.BAD_REQUEST, "Validation Failed",
                "Method parameter validation failed", correlationId, fieldErrors);
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MissingServletRequestPartException.class})
    public ResponseEntity<ErrorResponse> handleMissingParam(Exception e) {
        var correlationId = correlationId();
        return buildResponse(HttpStatus.BAD_REQUEST, "Bad Request", e.getMessage(), correlationId, null);
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(
            org.springframework.http.converter.HttpMessageNotReadableException e) {
        var correlationId = correlationId();
        // Cause is typically a Jackson MismatchedInputException for unknown enum values,
        // type mismatches, or malformed JSON. Surface as 400, not 500.
        var rootMsg = e.getMostSpecificCause() != null
                ? e.getMostSpecificCause().getMessage()
                : e.getMessage();
        log.debug("Malformed request body [correlationId={}]: {}", correlationId, rootMsg);
        return buildResponse(HttpStatus.BAD_REQUEST, "Bad Request",
                "Malformed request body", correlationId, null);
    }

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException e) {
        var correlationId = correlationId();
        // Typically fires when a @RequestParam doesn't bind cleanly — unknown enum value,
        // bad ISO date, non-numeric Long, etc. 400, not 500.
        var msg = "Invalid value for parameter '%s'".formatted(e.getName());
        log.debug("Parameter type mismatch [correlationId={}]: {}", correlationId, msg);
        return buildResponse(HttpStatus.BAD_REQUEST, "Bad Request", msg, correlationId, null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
        var correlationId = correlationId();
        log.debug("Bad request [correlationId={}]: {}", correlationId, e.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, "Bad Request", e.getMessage(), correlationId, null);
    }

    // --- 405 Method Not Allowed ---

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(
            org.springframework.web.HttpRequestMethodNotSupportedException e) {
        var correlationId = correlationId();
        // Without this handler, the catch-all turns it into a 500 — masking what is
        // really a client-side mistake (GET on a POST-only endpoint, etc.).
        return buildResponse(HttpStatus.METHOD_NOT_ALLOWED, "Method Not Allowed",
                e.getMessage(), correlationId, null);
    }

    /**
     * Time-overlap conflict on assignment create/confirm. Resolves ahead of the generic
     * {@link #handleIllegalState} (it's a more specific type) so the 409 carries a
     * machine-readable {@code details.conflicts} payload — each conflicting assignment's id,
     * playlist (id + name), status, and UTC window — letting the frontend render "Replace
     * <playlist> (until X)?" and offer a later start or a replace. The
     * free-text {@code message} stays non-leaky: internal ids live in {@code details}, not prose.
     */
    @ExceptionHandler(AssignmentTimeOverlapException.class)
    public ResponseEntity<ErrorResponse> handleAssignmentTimeOverlap(AssignmentTimeOverlapException e) {
        var correlationId = correlationId();
        log.debug("Assignment time overlap [correlationId={}]: target={}:{}, {} conflict(s)",
                correlationId, e.getTargetType(), e.getTargetId(), e.getConflicts().size());
        var conflicts = e.getConflicts().stream()
                .map(c -> new ConflictWindow(c.id(), c.playlistId(), c.playlistName(), c.status(),
                        c.startTime(), c.endTime(), c.conflictingDeviceIds()))
                .toList();
        var details = new ConflictDetails(
                "ASSIGNMENT_TIME_OVERLAP",
                e.getTargetType() != null ? e.getTargetType().name() : null,
                e.getTargetId(),
                conflicts);
        return buildResponse(HttpStatus.CONFLICT, "Conflict", e.getMessage(), correlationId, null, details);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException e) {
        var correlationId = correlationId();
        // Defensive: a no-arg or null-message IllegalStateException would otherwise emit a
        // blank `message`, leaving the frontend modal/inline extractor with nothing to show.
        // Keep the 409 contract holding by construction, not by every throw site's discipline.
        var message = (e.getMessage() != null && !e.getMessage().isBlank())
                ? e.getMessage()
                : "The request conflicts with the current state of the resource.";
        log.debug("Conflict [correlationId={}]: {}", correlationId, message);
        return buildResponse(HttpStatus.CONFLICT, "Conflict", message, correlationId, null);
    }

    // --- 429 Too Many Requests ---

    @ExceptionHandler(uz.orientadvertise.services.common.exception.RateLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleRateLimit(
            uz.orientadvertise.services.common.exception.RateLimitExceededException e) {
        var correlationId = correlationId();
        log.info("Rate limit exceeded [correlationId={}]: {}", correlationId, e.getMessage());
        return buildResponse(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests",
                e.getMessage(), correlationId, null);
    }

    // --- 503 Service Unavailable ---

    @ExceptionHandler(InvalidUploadException.class)
    public ResponseEntity<ErrorResponse> handleInvalidUpload(InvalidUploadException e) {
        var correlationId = correlationId();
        log.debug("Upload rejected [correlationId={}]: {}", correlationId, e.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, "Invalid Upload", e.getMessage(), correlationId, null);
    }

    @ExceptionHandler(StorageUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleStorageUnavailable(StorageUnavailableException e) {
        var correlationId = correlationId();
        log.error("Storage unavailable [correlationId={}]: {}", correlationId, e.getMessage());
        return buildResponse(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable",
                e.getMessage(), correlationId, null);
    }

    // --- 500 Internal Server Error (catch-all) ---

    /**
     * The catch-all for genuinely unexpected exceptions. This is the ONLY handler
     * that forwards to Telegram — every 4xx case above (validation, not-found,
     * forbidden, conflict, rate-limit) is an "expected" client-side outcome, and
     * Telegram'ing those would drown operators in noise.
     *
     * <p>The Telegram broadcast runs on the {@code auditExecutor} so a slow Telegram
     * API doesn't add latency to the (already failing) HTTP response.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e, HttpServletRequest request) {
        var correlationId = correlationId();
        log.error("Unexpected error [correlationId={}]: {}", correlationId, e.getMessage(), e);

        forwardToTelegram(e, request, correlationId);

        var message = includeStacktrace
                ? e.getClass().getName() + ": " + e.getMessage()
                : "An unexpected error occurred. Reference: " + correlationId;
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                message, correlationId, null);
    }

    private void forwardToTelegram(Exception e, HttpServletRequest request, String correlationId) {
        TelegramNotifier telegram = telegramProvider.getIfAvailable();
        Executor asyncExecutor = asyncExecutorProvider.getIfAvailable();
        if (telegram == null || asyncExecutor == null) {
            // Test slices or telegram-disabled deployments — no broadcast, but the HTTP
            // 500 still flows through the normal log statement above.
            return;
        }

        // Capture context on the request thread (request attributes won't be available
        // once we hop to the audit executor).
        String method = request != null ? request.getMethod() : "?";
        String path = request != null ? safePath(request) : "?";
        String user = currentPrincipal();
        String exClass = e.getClass().getName();
        String exMsg = e.getMessage();
        String stackSummary = formatStackSummary(e);

        try {
            CompletableFuture.runAsync(() -> {
                try {
                    for (String chunk : renderChunks(method, path, user, exClass, exMsg,
                            stackSummary, correlationId)) {
                        // ERROR severity, not FATAL — 500s are bursty and should be
                        // rate-limited. The summary aggregator surfaces the burst count
                        // every 5 minutes if many fire at once.
                        telegram.broadcastMarkdown(chunk, Severity.ERROR);
                    }
                } catch (Throwable t) {
                    // Async path — must not bubble back into anything. Log at debug
                    // (warn would risk feeding the Telegram appender's recursion path).
                    log.debug("Telegram 500-forward failed [correlationId={}]: {}",
                            correlationId, t.getMessage());
                }
            }, asyncExecutor);
        } catch (Throwable t) {
            // The auditExecutor is configured to silently drop on overflow, but the
            // executor itself could throw if it's shutting down. Defense in depth.
            log.debug("Could not dispatch Telegram 500-forward [correlationId={}]: {}",
                    correlationId, t.getMessage());
        }
    }

    /**
     * Render the 500 forward as one or more Telegram-Markdown chunks via the shared
     * {@link TelegramMessageBuilder}. The builder enforces severity emoji, escaping,
     * per-field 500-char truncation, and 4096-char chunking — call sites no longer need
     * to manage any of that locally.
     */
    static List<String> renderChunks(String method, String path, String user,
                                       String exClass, String exMsg,
                                       String stackSummary, String correlationId) {
        var kv = new java.util.LinkedHashMap<String, String>();
        kv.put("method", method);
        kv.put("path", path);
        kv.put("user", user);
        kv.put("correlation", correlationId);
        kv.put("exception", exClass);
        if (exMsg != null && !exMsg.isBlank()) {
            kv.put("message", exMsg);
        }
        var b = TelegramMessageBuilder.builder()
                .severity(Severity.ERROR)
                .title("HTTP 500 — Unhandled exception")
                .kvBlock(kv);
        if (stackSummary != null) {
            b.codeBlock("stack", stackSummary);
        }
        return b.buildChunks();
    }

    private static String safePath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String query = request.getQueryString();
        return query == null ? uri : uri + "?" + query;
    }

    /**
     * Best-effort principal lookup. {@code "anonymous"} when no auth, {@code "?"} when
     * the SecurityContext is empty/inaccessible. Note this is the public principal name
     * (username for JWT, 8-char API-key prefix for X-API-Key) — never an internal id.
     */
    private static String currentPrincipal() {
        try {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated()) return "anonymous";
            String name = auth.getName();
            return name == null || name.isBlank() ? "anonymous" : name;
        } catch (Exception ignored) {
            return "?";
        }
    }

    private static String formatStackSummary(Throwable t) {
        if (t == null) return null;
        StackTraceElement[] frames = t.getStackTrace();
        if (frames == null || frames.length == 0) return null;
        var sb = new StringBuilder();
        int max = Math.min(STACK_FRAMES, frames.length);
        for (int i = 0; i < max; i++) {
            sb.append("  at ").append(frames[i]).append('\n');
        }
        if (frames.length > max) {
            sb.append("  ... (").append(frames.length - max).append(" more)\n");
        }
        return sb.toString();
    }

    // --- Uniform response builder ---

    private ResponseEntity<ErrorResponse> buildResponse(HttpStatus status, String error, String message,
                                                         String correlationId, List<FieldError> fieldErrors) {
        return buildResponse(status, error, message, correlationId, fieldErrors, null);
    }

    private ResponseEntity<ErrorResponse> buildResponse(HttpStatus status, String error, String message,
                                                         String correlationId, List<FieldError> fieldErrors,
                                                         Object details) {
        var response = new ErrorResponse(status.value(), error, message, correlationId,
                Instant.now().toString(), fieldErrors, details);
        return ResponseEntity.status(status).body(response);
    }

    private static String correlationId() {
        return UUID.randomUUID().toString();
    }

    public record ErrorResponse(
            int status,
            String error,
            String message,
            String correlationId,
            String timestamp,
            List<FieldError> fieldErrors,
            // Optional structured payload for errors that carry machine-readable context (e.g.
            // ASSIGNMENT_TIME_OVERLAP). Omitted from the wire when null so every other error
            // response keeps its existing shape.
            @JsonInclude(JsonInclude.Include.NON_NULL)
            Object details
    ) {}

    public record FieldError(String field, String message, Object rejectedValue) {}

    /**
     * Structured {@code details} for {@link AssignmentTimeOverlapException} (409). {@code code}
     * is a stable machine token; {@code conflicts} carry each clashing assignment's id + UTC
     * window so the frontend can localize "already booked until X".
     */
    public record ConflictDetails(String code, String targetType, Long targetId,
                                  List<ConflictWindow> conflicts) {}

    public record ConflictWindow(Long id, Long playlistId, String playlistName, String status,
                                 Instant startTime, Instant endTime, List<Long> conflictingDeviceIds) {}
}
