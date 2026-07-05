package uz.orientadvertise.services.service;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.model.DeviceStatusView;
import uz.orientadvertise.services.domain.model.Event;
import uz.orientadvertise.services.domain.model.RemoteAction;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.DeviceStatusViewRepository;
import uz.orientadvertise.services.domain.repository.EventRepository;
import uz.orientadvertise.services.domain.repository.RemoteActionRepository;

/**
 * Aggregate diagnostic snapshot for a device. Response is cached for 30 seconds (Redis
 * cache name {@code diagnostics}, TTL configured in {@code RedisConfig}) — the operator
 * console typically polls this while troubleshooting and we don't want to repeatedly hit
 * five tables for the same device.
 *
 * <p>Edge case: a never-heartbeated device returns a populated envelope with null /
 * empty fields, not 404. The 404 is reserved for an unknown / soft-deleted device id.
 */
@Service
public class DeviceDiagnosticsService {

    private static final Logger log = LoggerFactory.getLogger(DeviceDiagnosticsService.class);
    private static final int RECENT_EVENTS = 10;
    private static final int RECENT_ACTIONS = 5;

    private final DeviceRepository deviceRepository;
    private final DeviceStatusViewRepository statusViewRepository;
    private final EventRepository eventRepository;
    private final RemoteActionRepository remoteActionRepository;

    public DeviceDiagnosticsService(DeviceRepository deviceRepository,
                                     DeviceStatusViewRepository statusViewRepository,
                                     EventRepository eventRepository,
                                     RemoteActionRepository remoteActionRepository) {
        this.deviceRepository = deviceRepository;
        this.statusViewRepository = statusViewRepository;
        this.eventRepository = eventRepository;
        this.remoteActionRepository = remoteActionRepository;
    }

    @Cacheable(value = "diagnostics", key = "#deviceId")
    @Transactional(readOnly = true)
    public DiagnosticsView getDiagnostics(Long deviceId) {
        var device = deviceRepository.findByIdAndDeletedAtIsNull(deviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Device", deviceId));

        // Top 10 events for this device, newest first.
        // For a never-heartbeated device this list is empty.
        var events = eventRepository.findByDeviceIdOrderByOccurredAtDesc(deviceId).stream()
                .limit(RECENT_EVENTS)
                .map(EventSummary::from)
                .toList();

        // Top 5 actions, newest issued first.
        var actions = remoteActionRepository.findByDeviceIdOrderByIssuedAtDesc(deviceId).stream()
                .limit(RECENT_ACTIONS)
                .map(ActionSummary::from)
                .toList();

        long pendingCount = remoteActionRepository.countPendingByDevice(deviceId);

        // Heartbeat-derived status from the view (single source of truth), not the raw column.
        String status = statusViewRepository.findById(deviceId)
                .map(DeviceStatusView::getComputedStatus)
                .map(Enum::name)
                .orElse(null);

        log.debug("Built diagnostics [device={}, events={}, actions={}, pending={}]",
                deviceId, events.size(), actions.size(), pendingCount);

        return new DiagnosticsView(
                device.getId(),
                device.getSerialNumber(),
                device.getName(),
                status,
                device.getLastHeartbeatAt(),
                device.getCurrentContentVersion(),
                device.getLastKnownIp(),
                pendingCount,
                events,
                actions,
                Instant.now());
    }

    public record DiagnosticsView(
            Long deviceId,
            String serialNumber,
            String name,
            String status,
            Instant lastHeartbeatAt,
            String currentContentVersion,
            String lastKnownIp,
            long pendingActionCount,
            List<EventSummary> recentEvents,
            List<ActionSummary> recentActions,
            Instant generatedAt
    ) implements Serializable {}

    public record EventSummary(Long id, String eventType, String priority, String payload,
                                Instant occurredAt) implements Serializable {
        public static EventSummary from(Event e) {
            return new EventSummary(e.getId(), e.getEventType(), e.getPriority().name(),
                    e.getPayload(), e.getOccurredAt());
        }
    }

    public record ActionSummary(Long id, String actionType, String status, String payload,
                                 String issuedBy, Instant issuedAt, Instant expiresAt,
                                 Instant confirmedAt) implements Serializable {
        public static ActionSummary from(RemoteAction a) {
            return new ActionSummary(a.getId(), a.getActionType(), a.getStatus().name(),
                    a.getPayload(), a.getIssuedBy(), a.getIssuedAt(),
                    a.getExpiresAt(), a.getConfirmedAt());
        }
    }
}
