package uz.orientadvertise.services.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.model.PlaylistItem;
import uz.orientadvertise.services.domain.model.RemoteAction;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.PlaylistItemRepository;

/**
 * Issues playlist transport commands (PREV / NEXT / JUMP) to a device as a
 * {@link RemoteAction}, and serves the operator UI the list those commands
 * address ({@link #getActivePlaylist}).
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
    private final PlaybackScheduleService playbackScheduleService;

    public PlaylistControlService(DeviceRepository deviceRepository,
                                   ContentAssignmentService assignmentService,
                                   PlaylistItemRepository playlistItemRepository,
                                   RemoteActionService remoteActionService,
                                   PlaybackScheduleService playbackScheduleService) {
        this.deviceRepository = deviceRepository;
        this.assignmentService = assignmentService;
        this.playlistItemRepository = playlistItemRepository;
        this.remoteActionService = remoteActionService;
        this.playbackScheduleService = playbackScheduleService;
    }

    /**
     * The playlist a device is playing right now, as the operator panel shows it and as
     * {@code JUMP} addresses it: the deliverable subset, contiguously re-indexed from 0.
     *
     * <p>Read-only and storage-free — unlike the device's own {@code GET /playlist} it presigns
     * nothing and never calls MinIO, so it is safe to open to VIEWER and cheap to poll.
     *
     * <p>A device with no assignment (or one whose assignment carries no playlist) is not an
     * error: it yields an empty view with a null {@code playlistId}, which the panel renders as
     * "no playlist assigned".
     *
     * <p>{@code scheduled} means a playback anchor exists for the assignment, i.e. the device is in
     * synchronised (group) playback. Per-device transport commands are pointless there — the device answers
     * {@code PLAYLIST_CONTROL} with {@code FAILED "SCHEDULE_MODE"} — so the UI disables them and
     * points the operator at the sync-group jump. The anchor is only *read* here
     * ({@link PlaybackScheduleService#isAnchored}); creating one is the device sync path's job.
     */
    @Transactional(readOnly = true)
    public ActivePlaylistView getActivePlaylist(Long deviceId) {
        var device = deviceRepository.findByIdAndDeletedAtIsNull(deviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Device", deviceId));

        var assignment = assignmentService.resolveForDevice(device, Instant.now());
        if (assignment == null || assignment.getPlaylist() == null) {
            return new ActivePlaylistView(deviceId, null, null, 0L, false, List.of());
        }

        var playlist = assignment.getPlaylist();
        var timeline = PlaybackSlotTimeline.of(deliverableItems(playlist.getId()));
        var items = timeline.slots().stream()
                .map(s -> new ActivePlaylistItemView(s.index(), s.position(), s.fileId(), s.title(),
                        // Never null: an item with no dwell and no media length still occupies the
                        // default slot, which is what the device plays and what the panel must show.
                        Math.round(s.slotDurationMs() / 1000.0)))
                .toList();
        boolean scheduled = playbackScheduleService.isAnchored(assignment.getId());

        return new ActivePlaylistView(deviceId, playlist.getId(), playlist.getName(),
                timeline.loopDurationMs(), scheduled, items);
    }

    /**
     * The device's DELIVERED list — the deliverable subset in playlist order
     * ({@link DeviceSyncService#isDeliverable}). {@link #getActivePlaylist} renders it and
     * {@code JUMP} is range-checked against its size, so the row an operator clicks and the
     * index the device receives cannot drift apart.
     */
    private List<PlaylistItem> deliverableItems(Long playlistId) {
        return playlistItemRepository.findByPlaylistIdOrderByPositionAsc(playlistId).stream()
                .filter(DeviceSyncService::isDeliverable)
                .toList();
    }

    /** One row of the panel: {@code index} is what JUMP takes, {@code position} the raw playlist slot. */
    public record ActivePlaylistItemView(int index, int position, Long fileId, String title,
                                         long durationSeconds) {}

    /** The device's current loop; {@code playlistId} is null when nothing is assigned. */
    public record ActivePlaylistView(Long deviceId, Long playlistId, String playlistName,
                                     long loopDurationMs, boolean scheduled,
                                     List<ActivePlaylistItemView> items) {}

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
            // Same helper the panel lists (getActivePlaylist), so its rows and this bound agree.
            long deliverableCount = deliverableItems(assignment.getPlaylist().getId()).size();
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
