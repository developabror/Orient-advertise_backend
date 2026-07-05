package uz.orientadvertise.services.service;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.domain.model.Playlist;
import uz.orientadvertise.services.domain.model.PlaylistItem;
import uz.orientadvertise.services.domain.repository.ContentFileRepository;
import uz.orientadvertise.services.domain.repository.PlaylistItemRepository;
import uz.orientadvertise.services.domain.repository.PlaylistRepository;

/**
 * CRUD + ordering operations for {@link PlaylistItem}.
 *
 * <p>The {@code playlist_item} table carries {@code UNIQUE(playlist_id, position)}, so any
 * mid-transaction state where two items share a position would violate the constraint and
 * roll the whole change back. Every position-mutating method below uses the sentinel
 * pattern documented in the README's <i>Playlist Position Ordering</i> section: rows in
 * flux are first parked on negative positions (which the constraint allows because no
 * existing row uses them) and only relocated to their final target position once the
 * range is clear. All four methods are strictly {@code @Transactional} so an exception
 * mid-shuffle rolls back to the pre-call state — partial reorders are impossible.
 */
@Service
public class PlaylistItemService {

    private static final Logger log = LoggerFactory.getLogger(PlaylistItemService.class);

    private final PlaylistRepository playlistRepository;
    private final PlaylistItemRepository itemRepository;
    private final ContentFileRepository contentFileRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final OperatorScopeResolver operatorScopeResolver;

    public PlaylistItemService(PlaylistRepository playlistRepository,
                                PlaylistItemRepository itemRepository,
                                ContentFileRepository contentFileRepository,
                                ApplicationEventPublisher eventPublisher,
                                OperatorScopeResolver operatorScopeResolver) {
        this.playlistRepository = playlistRepository;
        this.itemRepository = itemRepository;
        this.contentFileRepository = contentFileRepository;
        this.eventPublisher = eventPublisher;
        this.operatorScopeResolver = operatorScopeResolver;
    }

    /** Operator scope guard on the owning playlist — out-of-scope ⇒ 404 (never a 403 existence oracle). */
    private void assertPlaylistInScope(Playlist playlist) {
        if (operatorScopeResolver.resolve().excludes(playlist.getProject().getId())) {
            throw new ResourceNotFoundException("Playlist", playlist.getId());
        }
    }

    /**
     * Add a content file to a playlist.
     *
     * <ul>
     *   <li>{@code position == null} → append to the tail.</li>
     *   <li>{@code position} must be in {@code [0, currentSize]}; the upper bound is
     *       {@code currentSize} (not {@code currentSize - 1}) because appending counts.</li>
     *   <li>The content file must exist, not be soft-deleted, and be {@code READY}. The
     *       READY gate is a UX-level check — a file mid-transcoding has no playable URL,
     *       and an INVALID/FAILED file would never play. The user surface returns 400.</li>
     * </ul>
     */
    @Transactional
    public PlaylistItem addItem(Long playlistId, Long contentFileId,
                                  Integer position, Integer durationOverride) {
        Playlist playlist = playlistRepository.findByIdAndDeletedAtIsNull(playlistId)
                .orElseThrow(() -> new ResourceNotFoundException("Playlist", playlistId));
        assertPlaylistInScope(playlist);

        // Soft-deleted content collapses to the same 404 a missing row would — by design,
        // matches the GET /api/content/{id} contract.
        ContentFile content = contentFileRepository.findById(contentFileId)
                .filter(f -> f.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("ContentFile", contentFileId));

        if (content.getStatus() != ContentFile.Status.READY) {
            throw new IllegalArgumentException(
                    "Content file " + contentFileId + " is not READY (status: "
                            + content.getStatus() + ")");
        }

        int currentSize = itemRepository.findByPlaylistIdOrderByPositionAsc(playlistId).size();
        int targetPosition = position == null ? currentSize : position;
        if (targetPosition < 0 || targetPosition > currentSize) {
            throw new IllegalArgumentException(
                    "Position must be in [0, " + currentSize + "], got: " + targetPosition);
        }

        // Append doesn't need a shift — only inserts at or before an existing row do.
        if (targetPosition < currentSize) {
            itemRepository.shiftPositionsForInsert(playlistId, targetPosition);
            itemRepository.flush();
        }

        var item = new PlaylistItem(playlist, content, targetPosition, durationOverride);
        playlist.markUpdated();
        var saved = itemRepository.save(item);
        log.info("Added playlist item [playlistId={} itemId={} contentFileId={} position={}]",
                playlistId, saved.getId(), contentFileId, targetPosition);
        eventPublisher.publishEvent(new PlaylistReorderedEvent(playlistId));
        return saved;
    }

    /**
     * Remove an item, then compact subsequent positions so the playlist stays a dense
     * 0..N-1 sequence. The {@code playlistId} on the URL is verified against the item's
     * actual parent so callers can't pass a mismatched pair to alter another playlist.
     */
    @Transactional
    public void removeItem(Long playlistId, Long itemId) {
        var item = loadItemForPlaylist(playlistId, itemId);
        assertPlaylistInScope(item.getPlaylist());
        int removedPosition = item.getPosition();

        itemRepository.delete(item);
        itemRepository.flush();
        itemRepository.compactPositionsAfterRemoval(playlistId, removedPosition);

        playlistRepository.findByIdAndDeletedAtIsNull(playlistId).ifPresent(Playlist::markUpdated);
        log.info("Removed playlist item [playlistId={} itemId={} freedPosition={}]",
                playlistId, itemId, removedPosition);
        eventPublisher.publishEvent(new PlaylistReorderedEvent(playlistId));
    }

    /**
     * Move a single item to a new position. Implementation:
     * <ol>
     *   <li>Park the moving item at sentinel position {@code -1} (vacates its slot).</li>
     *   <li>Shift every item between old and new positions one step toward the vacated
     *       slot — direction depends on whether the move is up or down.</li>
     *   <li>Place the moving item at the target position.</li>
     * </ol>
     * The constraint never sees two rows on the same non-negative position.
     */
    @Transactional
    public PlaylistItem moveItem(Long playlistId, Long itemId, int toPosition) {
        var item = loadItemForPlaylist(playlistId, itemId);
        assertPlaylistInScope(item.getPlaylist());
        int currentSize = itemRepository.findByPlaylistIdOrderByPositionAsc(playlistId).size();
        if (toPosition < 0 || toPosition >= currentSize) {
            throw new IllegalArgumentException(
                    "toPosition must be in [0, " + (currentSize - 1) + "], got: " + toPosition);
        }
        int oldPosition = item.getPosition();
        if (oldPosition == toPosition) {
            return item;
        }

        // Step 1: park at sentinel
        item.setPosition(-1);
        itemRepository.saveAndFlush(item);

        // Step 2: shift the affected range by one toward the vacated slot
        if (oldPosition < toPosition) {
            itemRepository.shiftPositionsUp(playlistId, oldPosition, toPosition);
        } else {
            itemRepository.shiftPositionsDown(playlistId, toPosition, oldPosition);
        }
        itemRepository.flush();

        // Step 3: place at target
        item.setPosition(toPosition);
        var saved = itemRepository.save(item);

        playlistRepository.findByIdAndDeletedAtIsNull(playlistId).ifPresent(Playlist::markUpdated);
        log.info("Moved playlist item [playlistId={} itemId={} {} -> {}]",
                playlistId, itemId, oldPosition, toPosition);
        eventPublisher.publishEvent(new PlaylistReorderedEvent(playlistId));
        return saved;
    }

    /**
     * Reorder every item in one call. The supplied {@code orderedItemIds} must exactly
     * match the current item set — no missing ids, no extras, no duplicates. Strict
     * equality means the FE submits an authoritative ordering rather than a partial
     * patch, which avoids the ambiguity of "what should the other items do?".
     *
     * <p>Strategy: stamp every item with a unique negative sentinel position (so the
     * uniqueness constraint never trips even if pairs of items would otherwise swap),
     * then walk the supplied id list and assign {@code 0..N-1}.
     */
    @Transactional
    public List<PlaylistItem> reorderAll(Long playlistId, List<Long> orderedItemIds) {
        Playlist playlist = playlistRepository.findByIdAndDeletedAtIsNull(playlistId)
                .orElseThrow(() -> new ResourceNotFoundException("Playlist", playlistId));
        assertPlaylistInScope(playlist);
        Objects.requireNonNull(orderedItemIds, "orderedItemIds");

        var existing = itemRepository.findByPlaylistIdOrderByPositionAsc(playlistId);
        Set<Long> existingIds = existing.stream()
                .map(PlaylistItem::getId)
                .collect(Collectors.toSet());
        Set<Long> providedIds = new HashSet<>(orderedItemIds);

        // Three failure modes collapsed into one message — the request is wrong either
        // way and the FE handles it the same: re-fetch detail, recompute the list, retry.
        if (providedIds.size() != orderedItemIds.size()
                || !existingIds.equals(providedIds)) {
            throw new IllegalArgumentException(
                    "orderedItemIds must exactly match the current item set; expected "
                            + existingIds + ", got " + orderedItemIds);
        }

        // Step 1: park each row on a unique negative position
        for (int i = 0; i < existing.size(); i++) {
            existing.get(i).setPosition(-(i + 1));
        }
        itemRepository.saveAllAndFlush(existing);

        // Step 2: assign target positions per the supplied order
        for (int i = 0; i < orderedItemIds.size(); i++) {
            Long targetId = orderedItemIds.get(i);
            int targetPosition = i;
            existing.stream()
                    .filter(it -> it.getId().equals(targetId))
                    .findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException("PlaylistItem", targetId))
                    .setPosition(targetPosition);
        }
        itemRepository.saveAllAndFlush(existing);

        playlistRepository.findByIdAndDeletedAtIsNull(playlistId).ifPresent(Playlist::markUpdated);
        log.info("Reordered playlist [playlistId={} itemCount={}]", playlistId, existing.size());
        eventPublisher.publishEvent(new PlaylistReorderedEvent(playlistId));

        // Fetch-join contentFile so the controller's DTO mapping (which runs after
        // this @Transactional method returns and the session is closed) can read
        // contentFile fields without a LazyInitializationException.
        return itemRepository.findByPlaylistIdWithContentFile(playlistId);
    }

    /**
     * Set or clear the per-item duration override. {@code durationSeconds == null} clears
     * the override so the device-side {@code GET /api/devices/{id}/playlist} falls back to
     * the source content file's natural duration — see
     * {@link DeviceSyncService#getPlaylistView}.
     *
     * <p>Defense-in-depth range check: the controller's {@code @Min(1) @Max(86400)} stops
     * out-of-range values at the request boundary, but the service rejects them too so a
     * direct caller (test, internal code) cannot bypass the contract. {@code 0} is invalid
     * because a zero-duration item would never play; the upper cap of 24 hours is well
     * past any reasonable single-clip length.
     */
    @Transactional
    public PlaylistItem setDuration(Long playlistId, Long itemId, Integer durationSeconds) {
        // Soft-deleted playlist surfaces as 404, matching the user's contract — we check
        // BEFORE loading the item so the caller sees the same response whether the
        // playlist is gone or the item is missing.
        Playlist playlistForScope = playlistRepository.findByIdAndDeletedAtIsNull(playlistId)
                .orElseThrow(() -> new ResourceNotFoundException("Playlist", playlistId));
        assertPlaylistInScope(playlistForScope);

        if (durationSeconds != null && (durationSeconds < 1 || durationSeconds > 86400)) {
            throw new IllegalArgumentException(
                    "durationSeconds must be in [1, 86400] or null to clear, got: "
                            + durationSeconds);
        }

        var item = loadItemForPlaylist(playlistId, itemId);
        item.setDurationSeconds(durationSeconds);
        playlistRepository.findByIdAndDeletedAtIsNull(playlistId).ifPresent(Playlist::markUpdated);
        log.info("Set playlist item duration [playlistId={} itemId={} durationSeconds={}]",
                playlistId, itemId, durationSeconds);
        eventPublisher.publishEvent(new PlaylistReorderedEvent(playlistId));
        return item;
    }

    private PlaylistItem loadItemForPlaylist(Long playlistId, Long itemId) {
        var item = itemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("PlaylistItem", itemId));
        // Mismatched (playlistId, itemId) collapses to 404. Returning 400 would leak the
        // existence of the item to a caller who doesn't own its actual playlist; 404 is
        // safer and matches "not found in this playlist" intuitively.
        if (item.getPlaylist() == null
                || !Objects.equals(item.getPlaylist().getId(), playlistId)) {
            throw new ResourceNotFoundException("PlaylistItem", itemId);
        }
        return item;
    }
}
