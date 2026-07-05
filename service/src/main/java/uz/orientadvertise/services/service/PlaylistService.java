package uz.orientadvertise.services.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.domain.model.Playlist;
import uz.orientadvertise.services.domain.model.PlaylistItem;
import uz.orientadvertise.services.domain.repository.PlaylistItemRepository;
import uz.orientadvertise.services.domain.repository.PlaylistRepository;

@Service
public class PlaylistService {

    private final PlaylistRepository playlistRepository;
    private final PlaylistItemRepository itemRepository;

    public PlaylistService(PlaylistRepository playlistRepository, PlaylistItemRepository itemRepository) {
        this.playlistRepository = playlistRepository;
        this.itemRepository = itemRepository;
    }

    @Transactional(readOnly = true)
    public List<PlaylistItem> getItems(Long playlistId) {
        return itemRepository.findByPlaylistIdOrderByPositionAsc(playlistId);
    }

    @Transactional
    public PlaylistItem addItem(Long playlistId, ContentFile contentFile, int position, Integer durationSeconds) {
        var playlist = playlistRepository.findByIdAndDeletedAtIsNull(playlistId)
                .orElseThrow(() -> new ResourceNotFoundException("Playlist", playlistId));

        // Defense in depth: even if the caller fetched the file before a concurrent
        // soft-delete, re-check here. Once a content row carries a deletedAt timestamp it
        // is invisible to listings, detail, and advertiser access — letting playlist
        // builders ignore that and slip a hidden file in would defeat the contract.
        if (contentFile.getDeletedAt() != null) {
            throw new ResourceNotFoundException("ContentFile", contentFile.getId());
        }

        // Shift existing items at and after the insert position
        itemRepository.shiftPositionsForInsert(playlistId, position);
        itemRepository.flush();

        var item = new PlaylistItem(playlist, contentFile, position, durationSeconds);
        playlist.markUpdated();
        return itemRepository.save(item);
    }

    @Transactional
    public void removeItem(Long playlistId, Long itemId) {
        var item = itemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("PlaylistItem", itemId));

        int removedPosition = item.getPosition();
        itemRepository.delete(item);
        itemRepository.flush();

        // Compact: shift all items after removed position up by 1
        itemRepository.compactPositionsAfterRemoval(playlistId, removedPosition);

        playlistRepository.findByIdAndDeletedAtIsNull(playlistId)
                .ifPresent(Playlist::markUpdated);
    }

    /**
     * Move an item from one position to another within a playlist.
     * All affected positions are updated atomically in a single transaction.
     *
     * Strategy: temporarily set moved item to -1 (sentinel), shift affected range,
     * then set moved item to target position.
     */
    @Transactional
    public void reorderItem(Long playlistId, Long itemId, int newPosition) {
        var item = itemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("PlaylistItem", itemId));

        int oldPosition = item.getPosition();
        if (oldPosition == newPosition) {
            return;
        }

        // Step 1: Move item out of the way (sentinel position)
        item.setPosition(-1);
        itemRepository.saveAndFlush(item);

        // Step 2: Shift affected range
        if (oldPosition < newPosition) {
            // Moving down: shift items in (old, new] up by 1
            itemRepository.shiftPositionsUp(playlistId, oldPosition, newPosition);
        } else {
            // Moving up: shift items in [new, old) down by 1
            itemRepository.shiftPositionsDown(playlistId, newPosition, oldPosition);
        }
        itemRepository.flush();

        // Step 3: Place item at target position
        item.setPosition(newPosition);
        itemRepository.save(item);

        playlistRepository.findByIdAndDeletedAtIsNull(playlistId)
                .ifPresent(Playlist::markUpdated);
    }

    /**
     * Bulk reorder: set all positions from an ordered list of item IDs.
     * All positions are updated atomically in a single transaction.
     */
    @Transactional
    public void reorderAll(Long playlistId, List<Long> orderedItemIds) {
        var items = itemRepository.findByPlaylistIdOrderByPositionAsc(playlistId);

        // Step 1: Set all positions to negative sentinels to avoid unique constraint violations
        for (int i = 0; i < items.size(); i++) {
            items.get(i).setPosition(-(i + 1));
        }
        itemRepository.saveAllAndFlush(items);

        // Step 2: Assign new positions based on the ordered ID list
        for (int i = 0; i < orderedItemIds.size(); i++) {
            var targetId = orderedItemIds.get(i);
            var item = items.stream()
                    .filter(it -> it.getId().equals(targetId))
                    .findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException("PlaylistItem", targetId));
            item.setPosition(i);
        }
        itemRepository.saveAll(items);

        playlistRepository.findByIdAndDeletedAtIsNull(playlistId)
                .ifPresent(Playlist::markUpdated);
    }
}
