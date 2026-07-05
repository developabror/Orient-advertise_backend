package uz.orientadvertise.services.service;

import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.model.RemoteAction;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.PlaylistItemRepository;

/**
 * Issues playlist transport commands (PREV / NEXT / JUMP) to a device as a
 * {@link RemoteAction}. The action is durably queued and picked up by the device
 * either immediately (open WebSocket) or on its next heartbeat poll.
 *
 * <p>Edge cases:
 * <ul>
 *   <li>JUMP to invalid position → {@link IllegalArgumentException} (mapped to 400).
 *       Range is validated server-side before queuing.</li>
 *   <li>Device offline → action carries a 10-minute {@code expiresAt}; if the device
 *       hasn't picked it up by then, the scheduled expirer marks it EXPIRED so it
 *       doesn't surface as a stale command on a much-later reconnect.</li>
 *   <li>Duplicate PENDING control while one is still in-flight → rejected by
 *       {@link RemoteActionService} (one transport command at a time).</li>
 * </ul>
 */
@Service
public class PlaylistControlService {

    public static final Duration CONTROL_TIMEOUT = Duration.ofMinutes(10);
    public static final String ACTION_TYPE = "PLAYLIST_CONTROL";

    private final DeviceRepository deviceRepository;
    private final ContentAssignmentService assignmentService;
    private final PlaylistItemRepository playlistItemRepository;
    private final RemoteActionService remoteActionService;

    public PlaylistControlService(DeviceRepository deviceRepository,
                                   ContentAssignmentService assignmentService,
                                   PlaylistItemRepository playlistItemRepository,
                                   RemoteActionService remoteActionService) {
        this.deviceRepository = deviceRepository;
        this.assignmentService = assignmentService;
        this.playlistItemRepository = playlistItemRepository;
        this.remoteActionService = remoteActionService;
    }

    @Transactional
    public RemoteAction issueControl(Long deviceId, ControlAction action, Integer position, String issuedBy) {
        var device = deviceRepository.findByIdAndDeletedAtIsNull(deviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Device", deviceId));

        var assignment = assignmentService.resolveForDevice(device, Instant.now());
        if (assignment == null || assignment.getPlaylist() == null) {
            throw new IllegalArgumentException(
                    "Device " + deviceId + " has no assigned playlist; cannot issue playback control");
        }

        if (action == ControlAction.JUMP) {
            if (position == null) {
                throw new IllegalArgumentException("position is required for JUMP action");
            }
            // JUMP addresses the device's DELIVERED list — the 0-based, contiguously re-indexed
            // deliverable subset (DeviceSyncService.isDeliverable), NOT the raw DB item set.
            // Using the same predicate /sync uses for the order means an operator jump lands on
            // the clip the device actually shows, instead of being off by the skipped items.
            long deliverableCount = playlistItemRepository
                    .findByPlaylistIdOrderByPositionAsc(assignment.getPlaylist().getId())
                    .stream()
                    .filter(DeviceSyncService::isDeliverable)
                    .count();
            if (position < 0 || position >= deliverableCount) {
                throw new IllegalArgumentException(
                        "JUMP position " + position + " is out of range [0, " + deliverableCount + ")");
            }
        }

        String payload = action == ControlAction.JUMP
                ? "{\"action\":\"JUMP\",\"position\":" + position + "}"
                : "{\"action\":\"" + action.name() + "\"}";

        return remoteActionService.issue(device, ACTION_TYPE, payload, issuedBy, CONTROL_TIMEOUT);
    }

    public enum ControlAction { PREV, NEXT, JUMP }
}
