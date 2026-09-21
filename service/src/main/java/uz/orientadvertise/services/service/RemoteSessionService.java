package uz.orientadvertise.services.service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.orientadvertise.services.common.exception.IllegalConfigurationException;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.content.DevicePushChannel;
import uz.orientadvertise.services.domain.content.PushMessageType;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.EntityAuditLog;
import uz.orientadvertise.services.domain.model.RemoteSession;
import uz.orientadvertise.services.domain.model.RemoteSession.Status;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.RemoteSessionRepository;
import uz.orientadvertise.services.service.RemoteSessionTicketService.Role;
import uz.orientadvertise.services.service.exception.RemoteCapabilityUnsupportedException;
import uz.orientadvertise.services.service.exception.RemoteControlDisabledException;

/**
 * The remote view/control <b>control plane</b>: session lifecycle, ticket minting, push to
 * device, device acks, audit.
 *
 * <p><b>No media passes through here.</b> The device and the operator browser meet on a separate
 * relay; this service only tells them where to meet and hands each a signed, role-scoped,
 * single-use ticket. That is what keeps video off a 1-vCPU box and away from the 8 KB Tomcat
 * text cap.
 *
 * <p>Delivery is best-effort-plus-fallback: the WebSocket push is an <i>optimisation</i>, the
 * heartbeat is the contract. An offline device is not an error — {@code start} still returns a
 * session, marked {@code deliveredVia=HEARTBEAT}, and the device picks it up on its next beat.
 */
@Service
public class RemoteSessionService {

    private static final Logger log = LoggerFactory.getLogger(RemoteSessionService.class);

    /** Non-terminal statuses — the "one live session per device" set. */
    private static final List<Status> LIVE_STATUSES = List.of(Status.PENDING, Status.ACTIVE);

    private static final String AUDIT_ENTITY_TYPE = "RemoteSession";

    private final RemoteSessionRepository sessionRepository;
    private final DeviceRepository deviceRepository;
    private final RemoteSessionTicketService ticketService;
    private final DevicePushChannel pushChannel;
    private final EntityAuditService auditService;
    private final RemoteProperties properties;
    private final SecureRandom random = new SecureRandom();

    public RemoteSessionService(RemoteSessionRepository sessionRepository,
                                 DeviceRepository deviceRepository,
                                 RemoteSessionTicketService ticketService,
                                 DevicePushChannel pushChannel,
                                 EntityAuditService auditService,
                                 RemoteProperties properties) {
        this.sessionRepository = sessionRepository;
        this.deviceRepository = deviceRepository;
        this.ticketService = ticketService;
        this.pushChannel = pushChannel;
        this.auditService = auditService;
        this.properties = properties;
        validateConfiguration(properties);
    }

    /**
     * Fail fast on a remote-control configuration that would only break later, at the worst
     * moment. Same discipline as the signing-secret check in {@link RemoteSessionTicketService}
     * and {@code JwtTokenProvider}: a blank relay URL would otherwise ship the literal string
     * {@code "null"} to a TV-Box, and a non-positive TTL would violate the
     * {@code chk_remote_session_expiry} CHECK constraint and surface as a 500 on the operator's
     * first click. Only enforced when the feature is enabled — a dark deployment needs no relay.
     */
    private static void validateConfiguration(RemoteProperties properties) {
        if (!properties.isEnabled()) {
            return;
        }
        if (properties.getSessionTtl() == null || properties.getSessionTtl().isZero()
                || properties.getSessionTtl().isNegative()) {
            throw new IllegalConfigurationException(
                    "app.remote.session-ttl must be a positive duration when app.remote.enabled=true");
        }
        requireUrl(properties.getRelay().getAgentUrl(), "app.remote.relay.agent-url");
        requireUrl(properties.getRelay().getViewerUrl(), "app.remote.relay.viewer-url");
    }

    private static void requireUrl(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalConfigurationException(
                    property + " must be set when app.remote.enabled=true");
        }
    }

    // ----- public API -----

    /**
     * Open a session for a device.
     *
     * <ul>
     *   <li>Feature off → {@link RemoteControlDisabledException} (503).</li>
     *   <li>Unknown or soft-deleted device → {@link ResourceNotFoundException} (404).</li>
     *   <li>Device reported {@code supported=false} → {@link RemoteCapabilityUnsupportedException}
     *       (422). {@code null} means "never reported" and is <b>allowed</b> — the device simply
     *       may not act on it.</li>
     *   <li>A {@code PENDING}/{@code ACTIVE} session already exists → {@link IllegalStateException}
     *       (409) carrying a human-readable message the frontend renders verbatim.</li>
     * </ul>
     */
    @Transactional
    public RemoteSessionView start(Long deviceId, boolean viewOnly, String issuedBy) {
        requireEnabled();
        var device = deviceRepository.findByIdAndDeletedAtIsNull(deviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Device", deviceId));

        if (Boolean.FALSE.equals(device.getRemoteSupported())) {
            throw new RemoteCapabilityUnsupportedException(
                    "Device \"%s\" reported that it does not support remote control.".formatted(
                            device.getName()));
        }

        sessionRepository.findFirstByDeviceIdAndStatusIn(deviceId, LIVE_STATUSES)
                .ifPresent(existing -> {
                    throw new IllegalStateException(
                            ("A remote session for device \"%s\" is already %s (started %s). "
                                    + "Stop it before starting a new one.").formatted(
                                    device.getName(), existing.getStatus(), existing.getIssuedAt()));
                });

        var now = Instant.now();
        var session = sessionRepository.save(new RemoteSession(
                newSessionKey(), device, viewOnly, issuedBy, now.plus(properties.getSessionTtl())));

        var deliveredVia = pushStart(session, device);
        audit(session, EntityAuditLog.Action.CREATE, issuedBy, deliveredVia);
        log.info("Remote session started [session={}, device={}, viewOnly={}, deliveredVia={}, by={}]",
                session.getSessionKey(), deviceId, viewOnly, deliveredVia, issuedBy);

        return toView(session, device, ticketFor(session, Role.VIEWER), deliveredVia);
    }

    /**
     * Stop a session. <b>Idempotent</b>: an already-terminal session is a no-op that still
     * reports success, so a double-click or a retried DELETE never surfaces an error.
     *
     * <p>Deliberately still works while the feature flag is off — turning remote control off
     * must not strand a device that is already streaming.
     */
    @Transactional
    public void stop(Long deviceId, String sessionKey, String actor) {
        var session = requireOwnedSession(deviceId, sessionKey);
        if (session.isTerminal()) {
            log.debug("Stop for already-terminal session [session={}, status={}]",
                    sessionKey, session.getStatus());
            return;
        }
        session.markEnded(RemoteSession.END_REASON_OPERATOR_STOP);
        pushStop(session, deviceId);
        audit(session, EntityAuditLog.Action.UPDATE, actor, null);
        log.info("Remote session stopped [session={}, device={}, by={}]", sessionKey, deviceId, actor);
    }

    /** The device's live session, without a ticket — tickets are single-issue (re-{@code POST}). */
    @Transactional(readOnly = true)
    public Optional<RemoteSessionView> current(Long deviceId) {
        return sessionRepository.findFirstByDeviceIdAndStatusIn(deviceId, LIVE_STATUSES)
                .map(session -> toView(session, session.getDevice(), null,
                        pushChannel.isConnected(deviceId) ? DeliveryChannel.WS : DeliveryChannel.HEARTBEAT));
    }

    /**
     * Device-side ack.
     *
     * <p>{@code READY} → {@code ACTIVE} + {@code started_at} + dimensions; {@code FAILED} →
     * {@code FAILED} + error; {@code ENDED} → {@code ENDED} + reason. Acking a terminal session
     * is a 409. Acking a session that belongs to a <b>different</b> device is a <b>404</b>, never
     * a 403 — a 403 would confirm the session key exists.
     */
    @Transactional
    public RemoteSessionView ack(Long deviceId, String sessionKey, AckStatus status,
                                  Integer width, Integer height, String error, String reason) {
        var session = requireOwnedSession(deviceId, sessionKey);
        if (session.isTerminal()) {
            throw new IllegalStateException(
                    "Remote session %s is already %s and cannot be acknowledged again.".formatted(
                            sessionKey, session.getStatus()));
        }

        switch (status) {
            case READY -> session.markActive(width, height);
            case FAILED -> session.markFailed(error);
            case ENDED -> session.markEnded(
                    reason != null && !reason.isBlank() ? reason : RemoteSession.END_REASON_DEVICE_ENDED);
        }
        log.info("Remote session ack [session={}, device={}, reported={}, status={}]",
                sessionKey, deviceId, status, session.getStatus());
        return toView(session, session.getDevice(), null, null);
    }

    /**
     * What the heartbeat should tell this device to be running right now — the same
     * desired-state convergence loop as {@code desiredVolume}: the device reports what it
     * <i>is</i>, the server replies with what it <i>should be</i>.
     *
     * <p>{@link Optional#empty()} means "no session wanted — stop any running one". Returns
     * empty when the feature is off, so a disabled deployment's heartbeat is byte-identical to
     * the pre-feature one.
     */
    @Transactional(readOnly = true)
    public Optional<DesiredRemoteSession> desiredFor(Long deviceId) {
        if (!properties.isEnabled()) {
            return Optional.empty();
        }
        return sessionRepository.findFirstByDeviceIdAndStatusIn(deviceId, LIVE_STATUSES)
                .filter(session -> !session.isPastExpiry(Instant.now()))
                .map(session -> new DesiredRemoteSession(
                        session.getSessionKey(),
                        ticketService.relayUrlFor(Role.AGENT),
                        ticketFor(session, Role.AGENT),
                        session.getExpiresAt(),
                        session.isViewOnly(),
                        effectiveMaxWidth(session.getDevice()),
                        properties.getMaxFps(),
                        properties.getBitRate()));
    }

    /**
     * Flip every {@code PENDING}/{@code ACTIVE} row past its {@code expires_at} to
     * {@code EXPIRED}. Returns the number swept.
     */
    @Transactional
    public int expireStale() {
        var stale = sessionRepository.findByStatusInAndExpiresAtBefore(LIVE_STATUSES, Instant.now());
        int expired = 0;
        for (var session : stale) {
            // The query already excludes terminal rows; skipping defensively means one odd row
            // can never abort the sweep and leave the rest of the fleet's sessions unswept.
            if (session.isTerminal()) {
                continue;
            }
            session.markExpired();
            expired++;
            log.info("Expired remote session [session={}, device={}]",
                    session.getSessionKey(), session.getDevice().getId());
        }
        return expired;
    }

    // ----- internals -----

    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw new RemoteControlDisabledException(
                    "Remote control is not enabled on this server.");
        }
    }

    /**
     * Load a session by key and assert it belongs to the calling device. A key that does not
     * exist and a key that belongs to someone else are indistinguishable to the caller — both
     * 404 — so the endpoint cannot be used to probe which session keys are live.
     */
    private RemoteSession requireOwnedSession(Long deviceId, String sessionKey) {
        var session = sessionRepository.findBySessionKey(sessionKey)
                .orElseThrow(() -> new ResourceNotFoundException("RemoteSession", sessionKey));
        if (!session.getDevice().getId().equals(deviceId)) {
            log.warn("Remote session {} addressed by device {} but owned by device {}",
                    sessionKey, deviceId, session.getDevice().getId());
            throw new ResourceNotFoundException("RemoteSession", sessionKey);
        }
        return session;
    }

    /** {@code "rs_"} + 16 CSPRNG bytes as hex. Never derived from the device id (enumeration guard). */
    private String newSessionKey() {
        var bytes = new byte[16];
        random.nextBytes(bytes);
        return RemoteSession.SESSION_KEY_PREFIX + HexFormat.of().formatHex(bytes);
    }

    private String ticketFor(RemoteSession session, Role role) {
        return ticketService.mint(session.getSessionKey(), role,
                session.getDevice().getId(), session.getExpiresAt());
    }

    /**
     * Encoder width the device should use: the configured cap, further clamped down to whatever
     * the device itself reported it can do. Capability is reported, not assumed.
     */
    private int effectiveMaxWidth(Device device) {
        var reported = device.getRemoteMaxWidth();
        return reported == null ? properties.getMaxWidth()
                : Math.min(properties.getMaxWidth(), reported);
    }

    /**
     * Push the START frame. The {@code type} is formatted from
     * {@link PushMessageType#name()} — never a string literal — so the wire and the enum can
     * never drift the way {@code BatchedSyncDispatcher}'s {@code SYNC_CONTENT} did.
     */
    private DeliveryChannel pushStart(RemoteSession session, Device device) {
        var message = """
                {"type":"%s","sessionId":"%s","relayUrl":"%s","agentTicket":"%s",\
                "expiresAt":"%s","viewOnly":%b,"maxWidth":%d,"maxFps":%d,"bitRate":%d}"""
                .formatted(PushMessageType.REMOTE_SESSION_START.name(),
                        session.getSessionKey(),
                        ticketService.relayUrlFor(Role.AGENT),
                        ticketFor(session, Role.AGENT),
                        session.getExpiresAt(),
                        session.isViewOnly(),
                        effectiveMaxWidth(device),
                        properties.getMaxFps(),
                        properties.getBitRate());
        return pushQuietly(device.getId(), message) ? DeliveryChannel.WS : DeliveryChannel.HEARTBEAT;
    }

    private void pushStop(RemoteSession session, Long deviceId) {
        var message = """
                {"type":"%s","sessionId":"%s"}"""
                .formatted(PushMessageType.REMOTE_SESSION_STOP.name(), session.getSessionKey());
        pushQuietly(deviceId, message);
    }

    /**
     * A push failure must never fail the operator's request — the heartbeat is the fallback and
     * it will deliver the same desired state within one beat.
     */
    private boolean pushQuietly(Long deviceId, String message) {
        try {
            return pushChannel.push(deviceId, message);
        } catch (Exception e) {
            log.warn("Remote session push failed [device={}]: {}", deviceId, e.getMessage());
            return false;
        }
    }

    /**
     * Audit row: who, which device, which session, view-only or not, and how it was delivered.
     * The ticket is deliberately absent — an audit trail must not become a credential store.
     */
    private void audit(RemoteSession session, EntityAuditLog.Action action, String actor,
                        DeliveryChannel deliveredVia) {
        var payload = """
                {"sessionId":"%s","deviceId":%d,"status":"%s","viewOnly":%b,"expiresAt":"%s"%s}"""
                .formatted(session.getSessionKey(), session.getDevice().getId(),
                        session.getStatus(), session.isViewOnly(), session.getExpiresAt(),
                        deliveredVia == null ? "" : ",\"deliveredVia\":\"%s\"".formatted(deliveredVia));
        auditService.logChange(AUDIT_ENTITY_TYPE, session.getId(), action,
                actor == null ? "system" : actor, null, payload);
    }

    private RemoteSessionView toView(RemoteSession session, Device device, String viewerTicket,
                                      DeliveryChannel deliveredVia) {
        return new RemoteSessionView(
                session.getSessionKey(),
                device.getId(),
                session.getStatus().name(),
                ticketService.relayUrlFor(Role.VIEWER),
                viewerTicket,
                session.getExpiresAt(),
                session.isViewOnly(),
                deliveredVia == null ? null : deliveredVia.name(),
                capabilityOf(device));
    }

    private static RemoteCapabilityView capabilityOf(Device device) {
        if (device.getRemoteCapsAt() == null) {
            return null;   // never reported
        }
        return new RemoteCapabilityView(device.getRemoteSupported(), device.getRemoteInput(),
                device.getRemoteTransport(), device.getRemoteMaxWidth(),
                device.getRemoteMaxHeight(), device.getRemoteCapsAt());
    }

    // ----- views -----

    /** How the device learns about the session. {@code HEARTBEAT} is a fallback, not a failure. */
    public enum DeliveryChannel { WS, HEARTBEAT }

    /** What the device reports back about a session it was asked to run. */
    public enum AckStatus { READY, FAILED, ENDED }

    /**
     * Operator-facing session projection. {@code viewerTicket} is populated only on
     * {@code start} — tickets are single-issue, so a reconnecting viewer must start again.
     */
    public record RemoteSessionView(String sessionId, Long deviceId, String status, String relayUrl,
                                     String viewerTicket, Instant expiresAt, boolean viewOnly,
                                     String deliveredVia, RemoteCapabilityView capability) {}

    /** Last capability the device reported; {@code null} at the parent level ⇒ never reported. */
    public record RemoteCapabilityView(Boolean supported, String input, String transport,
                                        Integer maxWidth, Integer maxHeight, Instant reportedAt) {}

    /** The heartbeat's desired-state block. {@code null} on the wire ⇒ stop any running session. */
    public record DesiredRemoteSession(String sessionId, String relayUrl, String agentTicket,
                                        Instant expiresAt, boolean viewOnly,
                                        Integer maxWidth, Integer maxFps, Integer bitRate) {}
}
