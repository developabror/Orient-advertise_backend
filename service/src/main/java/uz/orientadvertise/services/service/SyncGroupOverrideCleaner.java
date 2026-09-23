package uz.orientadvertise.services.service;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import uz.orientadvertise.services.domain.model.ContentAssignment;
import uz.orientadvertise.services.domain.repository.ContentAssignmentRepository;
import uz.orientadvertise.services.domain.repository.SyncGroupPlaybackOverrideRepository;

/**
 * Retires an operator group-jump override when the playlist underneath it is edited (VG-10).
 *
 * <p>{@code /sync} used to do this: each grouped device, on noticing that the override's
 * {@code contentVersion} no longer matched, deleted the row itself. That is a write on a shared row
 * from every member at once — and a Spring Data derived delete, which loads the entity and removes
 * it at flush time, outside the surrounding try/catch. Members that lost the race hit an optimistic
 * lock failure on a DELETE matching 0 rows: HTTP 500 and a Telegram alert for a device whose sync was
 * otherwise fine (it recovered on retry).
 *
 * <p>The edit is a single event, so it is cleaned up once, here. A stale override was never harmful
 * in the first place — {@code DeviceSyncService.overrideMatches} and
 * {@code SyncGroupPlaybackService} both ignore one whose content moved on, and the next jump
 * overwrites the row — so this is housekeeping, and a bulk {@code DELETE … WHERE assignment_id IN}
 * affects 0 rows instead of throwing when someone got there first.
 *
 * <p>{@code AFTER_COMMIT} so an edit that rolls back never retires a live jump, with
 * {@code fallbackExecution} because playlist mutations can be published outside a transaction, and
 * {@code REQUIRES_NEW} because after commit there is no transaction left to write in.
 */
@Component
public class SyncGroupOverrideCleaner {

    private static final Logger log = LoggerFactory.getLogger(SyncGroupOverrideCleaner.class);

    private final ContentAssignmentRepository assignmentRepository;
    private final SyncGroupPlaybackOverrideRepository overrideRepository;

    public SyncGroupOverrideCleaner(ContentAssignmentRepository assignmentRepository,
                                    SyncGroupPlaybackOverrideRepository overrideRepository) {
        this.assignmentRepository = assignmentRepository;
        this.overrideRepository = overrideRepository;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onPlaylistReordered(PlaylistReorderedEvent event) {
        List<Long> assignmentIds = assignmentRepository
                .findActiveByPlaylistId(event.playlistId(), Instant.now()).stream()
                .map(ContentAssignment::getId)
                .toList();
        if (assignmentIds.isEmpty()) {
            return;
        }
        int removed = overrideRepository.deleteByAssignmentIdIn(assignmentIds);
        if (removed > 0) {
            log.info("Retired {} group-jump override(s) after playlist {} was edited — the groups "
                    + "fall back to the new version's shared anchor", removed, event.playlistId());
        }
    }
}
