package uz.orientadvertise.services.api.ws;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import uz.orientadvertise.services.domain.content.DevicePushChannel;
import uz.orientadvertise.services.domain.content.DevicePushFrames;
import uz.orientadvertise.services.domain.content.PushMessageType;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.RemoteActionRepository;
import uz.orientadvertise.services.service.ContentVersionService;

/**
 * Persistent device→server channel.
 *
 * <p>Path: {@code /ws/devices/{id}} — the path segment after {@code /ws/devices/}
 * identifies the device. The handler maintains a single live socket per device.
 *
 * <p>Edge cases:
 * <ul>
 *   <li>Multiple connections from same device → accept new, close old (atomic
 *       {@code put} + best-effort close on the prior).</li>
 *   <li>Missed messages on reconnect → on connection establishment the handler
 *       replays {@link PushMessageType#ACTION_PENDING} for any queued remote_actions
 *       and {@link PushMessageType#SYNC_REQUIRED} if the device's last-known content
 *       version differs from the server-computed expected version.</li>
 * </ul>
 */
@Component
public class DeviceWebSocketHandler extends TextWebSocketHandler implements DevicePushChannel {

    private static final Logger log = LoggerFactory.getLogger(DeviceWebSocketHandler.class);

    private final Map<Long, WebSocketSession> sessionsByDevice = new ConcurrentHashMap<>();
    private final Map<String, Long> deviceIdBySession = new ConcurrentHashMap<>();

    // Lazily-resolved so the handler still loads in test contexts where service beans
    // aren't present. Replay is a best-effort enhancement, not a hard dependency.
    private final ObjectProvider<RemoteActionRepository> remoteActionRepoProvider;
    private final ObjectProvider<DeviceRepository> deviceRepoProvider;
    private final ObjectProvider<ContentVersionService> versionServiceProvider;

    public DeviceWebSocketHandler(ObjectProvider<RemoteActionRepository> remoteActionRepoProvider,
                                   ObjectProvider<DeviceRepository> deviceRepoProvider,
                                   ObjectProvider<ContentVersionService> versionServiceProvider) {
        this.remoteActionRepoProvider = remoteActionRepoProvider;
        this.deviceRepoProvider = deviceRepoProvider;
        this.versionServiceProvider = versionServiceProvider;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        var deviceId = parseDeviceId(session);
        if (deviceId == null) {
            log.warn("WebSocket connection without deviceId — closing");
            try { session.close(CloseStatus.BAD_DATA); } catch (IOException ignored) {}
            return;
        }

        // Edge case: multiple connections from same device — accept new, close old.
        var prior = sessionsByDevice.put(deviceId, session);
        deviceIdBySession.put(session.getId(), deviceId);
        if (prior != null) {
            log.info("Device {} reconnected — closing prior session {}", deviceId, prior.getId());
            try { prior.close(CloseStatus.POLICY_VIOLATION.withReason("superseded")); }
            catch (IOException ignored) {}
        } else {
            log.info("Device WebSocket connected [deviceId={}]", deviceId);
        }

        // Edge case: missed messages on reconnect — replay pending.
        replayPending(session, deviceId);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        var deviceId = deviceIdBySession.remove(session.getId());
        if (deviceId != null) {
            sessionsByDevice.remove(deviceId, session);
            log.info("Device WebSocket disconnected [deviceId={}, status={}]", deviceId, status);
        }
    }

    /**
     * Best-effort push. Returns the count of devices that received the message;
     * any device that's offline (or whose send fails) is logged and skipped — those
     * devices will pick up the same content on their next heartbeat.
     */
    public PushResult pushToDevices(java.util.Collection<Long> deviceIds, String message) {
        var sent = new AtomicInteger();
        var skipped = new AtomicInteger();
        var failed = new AtomicInteger();
        var textMessage = new TextMessage(message);

        for (var deviceId : deviceIds) {
            var session = sessionsByDevice.get(deviceId);
            if (session == null || !session.isOpen()) {
                skipped.incrementAndGet();
                continue;
            }
            try {
                session.sendMessage(textMessage);
                sent.incrementAndGet();
            } catch (IOException e) {
                failed.incrementAndGet();
                log.warn("WS push failed for device {}: {}", deviceId, e.getMessage());
            }
        }
        return new PushResult(sent.get(), skipped.get(), failed.get());
    }

    @Override
    public boolean isConnected(Long deviceId) {
        var session = sessionsByDevice.get(deviceId);
        return session != null && session.isOpen();
    }

    /**
     * Single-device push ({@link DevicePushChannel}). Delegates to {@link #pushToDevices} so
     * there is exactly one send path; {@code true} means the frame actually reached an open
     * socket, {@code false} means offline or a failed write — never an exception.
     */
    @Override
    public boolean push(Long deviceId, String message) {
        return pushToDevices(java.util.List.of(deviceId), message).sent() == 1;
    }

    public PushResult broadcast(String message) {
        return pushToDevices(java.util.Set.copyOf(sessionsByDevice.keySet()), message);
    }

    public int connectedDeviceCount() {
        return sessionsByDevice.size();
    }

    /**
     * Replay anything the device may have missed while disconnected. Runs once
     * immediately after the socket opens.
     */
    private void replayPending(WebSocketSession session, Long deviceId) {
        var raRepo = remoteActionRepoProvider.getIfAvailable();
        var deviceRepo = deviceRepoProvider.getIfAvailable();
        var versionSvc = versionServiceProvider.getIfAvailable();

        if (raRepo != null) {
            try {
                var pending = raRepo.findPendingByDevice(deviceId);
                for (var action : pending) {
                    sendQuietly(session, DevicePushFrames.actionPending(
                            action.getId(), action.getActionType(), action.getIssuedAt()));
                }
                if (!pending.isEmpty()) {
                    log.info("Replayed {} pending action(s) to device {}", pending.size(), deviceId);
                }
            } catch (Exception e) {
                log.warn("Pending action replay failed for device {}: {}", deviceId, e.getMessage());
            }
        }

        if (deviceRepo != null && versionSvc != null) {
            try {
                deviceRepo.findByIdAndDeletedAtIsNull(deviceId).ifPresent(device -> {
                    var expected = versionSvc.computeExpectedVersion(device, Instant.now());
                    var current = device.getCurrentContentVersion();
                    if (expected != null && !expected.equals(current)) {
                        var msg = """
                                {"type":"%s","expectedVersion":"%s"}"""
                                .formatted(PushMessageType.SYNC_REQUIRED.name(), expected);
                        sendQuietly(session, msg);
                        log.info("Replayed SYNC_REQUIRED to device {} (expected={}, current={})",
                                deviceId, expected, current);
                    }
                });
            } catch (Exception e) {
                log.warn("Version replay check failed for device {}: {}", deviceId, e.getMessage());
            }
        }
    }

    private void sendQuietly(WebSocketSession session, String message) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(message));
            }
        } catch (IOException e) {
            log.warn("Replay send failed: {}", e.getMessage());
        }
    }

    /**
     * Extracts the device id from {@code /ws/devices/{id}}. Returns null if the path
     * doesn't have a numeric segment after {@code /ws/devices/}.
     */
    private Long parseDeviceId(WebSocketSession session) {
        if (session.getUri() == null) return null;
        var path = session.getUri().getPath();
        if (path == null) return null;
        int idx = path.lastIndexOf('/');
        if (idx < 0 || idx == path.length() - 1) return null;
        try {
            return Long.parseLong(path.substring(idx + 1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public record PushResult(int sent, int skipped, int failed) {}
}
