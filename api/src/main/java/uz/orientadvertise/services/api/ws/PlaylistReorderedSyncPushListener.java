package uz.orientadvertise.services.api.ws;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import uz.orientadvertise.services.domain.content.SyncDispatcher;
import uz.orientadvertise.services.service.ContentAssignmentService;
import uz.orientadvertise.services.service.PlaylistReorderedEvent;

/**
 * Pushes a {@code SYNC_CONTENT} notification to currently-connected devices the
 * instant an operator mutates a playlist (add / remove / move / reorder /
 * set-duration). Sibling of {@link AssignmentConfirmedSyncPushListener}.
 *
 * <p>Subscribes with {@link TransactionPhase#AFTER_COMMIT} so a rolled-back
 * mutation (validation throw, FK violation, etc.) never produces a push.
 * Offline / WS-disconnected devices currently bound to the affected playlist
 * via active assignments pick up the change on their next heartbeat poll via
 * {@code DeviceSyncService.computeSyncPlan} — the status-free fallback path.
 *
 * <p>The device set is resolved against <em>current</em> active assignments at
 * listener time, so an assignment that lands between the playlist edit and the
 * listener fire is included.
 */
@Component
public class PlaylistReorderedSyncPushListener {

    private static final Logger log = LoggerFactory.getLogger(PlaylistReorderedSyncPushListener.class);
    private static final String REASON = "playlist-reordered";

    private final SyncDispatcher syncDispatcher;
    private final ContentAssignmentService assignmentService;

    public PlaylistReorderedSyncPushListener(SyncDispatcher syncDispatcher,
                                              ContentAssignmentService assignmentService) {
        this.syncDispatcher = syncDispatcher;
        this.assignmentService = assignmentService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPlaylistReordered(PlaylistReorderedEvent event) {
        var deviceIds = assignmentService.resolveDeviceIdsForActivePlaylist(
                event.playlistId(), Instant.now());
        if (deviceIds == null || deviceIds.isEmpty()) {
            log.debug("Playlist {} mutated but no devices are currently bound — no push",
                    event.playlistId());
            return;
        }
        log.info("Pushing sync to {} device(s) for mutated playlist {}",
                deviceIds.size(), event.playlistId());
        syncDispatcher.dispatchSyncToDevices(deviceIds, REASON);
    }
}
