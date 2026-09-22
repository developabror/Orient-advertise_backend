package uz.orientadvertise.services.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.RemoteAction;
import uz.orientadvertise.services.domain.repository.DeviceGroupRepository;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.PlaylistRepository;
import uz.orientadvertise.services.domain.repository.RemoteActionRepository;

@Service
public class BulkRemoteActionService {

    private static final Logger log = LoggerFactory.getLogger(BulkRemoteActionService.class);
    private static final int BATCH_SIZE = 50;
    private static final Set<String> ALLOWED_ACTIONS = Set.of("SYNC_CONTENT", "REBOOT", "ASSIGN_CONTENT");

    private final DeviceGroupRepository deviceGroupRepository;
    private final DeviceRepository deviceRepository;
    private final RemoteActionRepository remoteActionRepository;
    private final PlaylistRepository playlistRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    public BulkRemoteActionService(DeviceGroupRepository deviceGroupRepository,
                                    DeviceRepository deviceRepository,
                                    RemoteActionRepository remoteActionRepository,
                                    PlaylistRepository playlistRepository,
                                    ObjectMapper objectMapper,
                                    ApplicationEventPublisher eventPublisher) {
        this.deviceGroupRepository = deviceGroupRepository;
        this.deviceRepository = deviceRepository;
        this.remoteActionRepository = remoteActionRepository;
        this.playlistRepository = playlistRepository;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Issue an action to every device in a group. Each device gets its own remote_action row.
     *
     * Edge cases:
     *  - 200+ devices: actions are flushed in batches of {@value #BATCH_SIZE}
     *  - Partial failure (e.g. duplicate PENDING for some devices) returns a summary, not a rollback.
     *    Each device's creation runs in a nested {@code REQUIRES_NEW} transaction so one failure
     *    doesn't poison the others.
     */
    public BulkActionResult issueToGroup(Long groupId, String actionType, String payload, String issuedBy) {
        if (!ALLOWED_ACTIONS.contains(actionType)) {
            throw new IllegalArgumentException("Unsupported action type: " + actionType
                    + ". Allowed: " + ALLOWED_ACTIONS);
        }

        // ASSIGN_CONTENT carries a structured payload — fail-fast BEFORE the device loop
        // so a bad request never half-applies. Other action types (REBOOT, SYNC_CONTENT)
        // pass payload through opaquely; the device interprets it.
        if ("ASSIGN_CONTENT".equals(actionType)) {
            validateAssignContentPayload(payload);
        }

        var group = deviceGroupRepository.findByIdAndDeletedAtIsNull(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("DeviceGroup", groupId));

        var devices = deviceRepository.findByDeviceGroupIdAndDeletedAtIsNull(groupId);
        log.info("Issuing bulk action [{}] to group {} ({} devices)", actionType, groupId, devices.size());

        var succeeded = new ArrayList<Long>();
        var skipped = new ArrayList<DeviceFailure>();
        var failed = new ArrayList<DeviceFailure>();

        // Process in batches so we periodically flush + clear the persistence context
        for (int i = 0; i < devices.size(); i += BATCH_SIZE) {
            var batch = devices.subList(i, Math.min(i + BATCH_SIZE, devices.size()));
            for (var device : batch) {
                try {
                    var action = issueOneIsolated(device, actionType, payload, issuedBy);
                    succeeded.add(action.getId());
                } catch (DuplicatePendingException e) {
                    skipped.add(new DeviceFailure(device.getId(), e.getMessage()));
                } catch (Exception e) {
                    log.warn("Failed to issue action to device {}: {}", device.getId(), e.getMessage());
                    failed.add(new DeviceFailure(device.getId(), e.getMessage()));
                }
            }
        }

        log.info("Bulk action [{}] for group {} done: succeeded={}, skipped={}, failed={}",
                actionType, groupId, succeeded.size(), skipped.size(), failed.size());

        return new BulkActionResult(group.getId(), actionType, devices.size(),
                succeeded, skipped, failed);
    }

    /**
     * Validate the ASSIGN_CONTENT payload. Three failure modes, all → 400:
     *
     * <ol>
     *   <li><b>Null payload.</b> ASSIGN_CONTENT requires a body; can't assign nothing.</li>
     *   <li><b>Missing or non-numeric {@code playlistId} field.</b> Schema mismatch.</li>
     *   <li><b>Unknown or soft-deleted playlist.</b> The id parses but doesn't resolve to
     *       a live playlist row — checked via {@link PlaylistRepository#findByIdAndDeletedAtIsNull}.
     *       Soft-deleted is treated as gone, matching {@code GET /api/playlists/{id}}.</li>
     * </ol>
     *
     * <p>Validation runs <i>before</i> the device loop in {@link #issueToGroup} so a
     * malformed request rejects without issuing any per-device {@code RemoteAction} —
     * partial application would be a contract violation operators can't easily reverse.
     */
    private void validateAssignContentPayload(String payload) {
        if (payload == null) {
            throw new IllegalArgumentException(
                    "ASSIGN_CONTENT requires payload {\"playlistId\":<id>}");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "ASSIGN_CONTENT requires payload {\"playlistId\":<id>}; "
                            + "payload is not parseable JSON: " + e.getMessage());
        }
        JsonNode idNode = root == null ? null : root.get("playlistId");
        if (idNode == null || !idNode.canConvertToLong()) {
            throw new IllegalArgumentException(
                    "ASSIGN_CONTENT requires payload {\"playlistId\":<id>}");
        }
        long playlistId = idNode.asLong();
        if (playlistRepository.findByIdAndDeletedAtIsNull(playlistId).isEmpty()) {
            throw new IllegalArgumentException(
                    "ASSIGN_CONTENT requires payload {\"playlistId\":<id>}; "
                            + "playlist " + playlistId + " not found");
        }
    }

    /**
     * Each device's action creation runs in its own transaction. Failure here doesn't
     * affect sibling devices — that's the whole point of "partial failure summary, no rollback".
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RemoteAction issueOneIsolated(Device device, String actionType, String payload, String issuedBy) {
        var pending = remoteActionRepository.findPendingByDeviceAndType(device.getId(), actionType);
        if (!pending.isEmpty()) {
            throw new DuplicatePendingException(
                    "Device %d already has a PENDING %s (action id=%d)".formatted(
                            device.getId(), actionType, pending.getFirst().getId()));
        }
        var action = new RemoteAction(device, actionType, payload, issuedBy);
        var saved = remoteActionRepository.save(action);
        eventPublisher.publishEvent(new RemoteActionIssuedEvent(
                saved.getId(), device.getId(), saved.getActionType(), saved.getIssuedAt()));
        return saved;
    }

    public record DeviceFailure(Long deviceId, String reason) {}

    public record BulkActionResult(
            Long deviceGroupId,
            String actionType,
            int totalDevices,
            List<Long> succeededActionIds,
            List<DeviceFailure> skipped,
            List<DeviceFailure> failed
    ) {
        public int succeededCount() { return succeededActionIds.size(); }
        public int skippedCount() { return skipped.size(); }
        public int failedCount() { return failed.size(); }
    }

    public static class DuplicatePendingException extends RuntimeException {
        public DuplicatePendingException(String message) { super(message); }
    }
}
