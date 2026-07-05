package uz.orientadvertise.services.service;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.orientadvertise.services.common.util.ContentVersionHasher;
import uz.orientadvertise.services.common.util.ContentVersionHasher.FileRef;
import uz.orientadvertise.services.domain.model.ContentAssignment;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.repository.PlaylistItemRepository;

/**
 * Computes the expected content version for a device given its currently-resolved
 * assignment. Hash inputs include the assignment's monotonic {@code versionNumber}
 * so rolling back to identical playlist content still produces a new version.
 */
@Service
public class ContentVersionService {

    private final ContentAssignmentService assignmentService;
    private final PlaylistItemRepository playlistItemRepository;

    public ContentVersionService(ContentAssignmentService assignmentService,
                                  PlaylistItemRepository playlistItemRepository) {
        this.assignmentService = assignmentService;
        this.playlistItemRepository = playlistItemRepository;
    }

    /**
     * Compute the version hash for a device at a given time, or null if the device
     * has no resolved assignment (no content → no version).
     */
    @Transactional(readOnly = true)
    public String computeExpectedVersion(Device device, Instant atTime) {
        ContentAssignment assignment = assignmentService.resolveForDevice(device, atTime);
        if (assignment == null || assignment.getPlaylist() == null) {
            return null;
        }
        return computeForAssignment(assignment);
    }

    public String computeForAssignment(ContentAssignment assignment) {
        var playlist = assignment.getPlaylist();
        var items = playlistItemRepository.findByPlaylistIdOrderByPositionAsc(playlist.getId());
        List<FileRef> refs = items.stream()
                .map(it -> {
                    var file = it.getContentFile();
                    // Effective per-item duration (override dwell ?? file's natural duration) — the same
                    // authoritative-duration rule the /sync schedule uses; folding it in keeps the
                    // content version stable per slot-timing set (a dwell edit yields a new version).
                    Integer effective = it.getDurationSeconds() != null
                            ? it.getDurationSeconds()
                            : (file == null ? null : file.getDurationSeconds());
                    return new FileRef(
                            file == null ? null : file.getId(),
                            file == null ? null : file.getProcessedStorageKey(),
                            effective);
                })
                .toList();
        return ContentVersionHasher.hash(
                assignment.getId(), assignment.getVersionNumber(), playlist.getId(), refs);
    }
}
