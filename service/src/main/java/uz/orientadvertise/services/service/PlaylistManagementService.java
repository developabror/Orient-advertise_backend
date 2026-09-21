package uz.orientadvertise.services.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.model.Playlist;
import uz.orientadvertise.services.domain.model.PlaylistItem;
import uz.orientadvertise.services.domain.model.Project;
import uz.orientadvertise.services.domain.repository.ContentAssignmentRepository;
import uz.orientadvertise.services.domain.repository.PlaylistItemRepository;
import uz.orientadvertise.services.domain.repository.PlaylistRepository;
import uz.orientadvertise.services.domain.repository.ProjectRepository;
import uz.orientadvertise.services.service.OperatorScopeResolver.ScopedProjects;

/**
 * Lifecycle + read paths for {@link Playlist}.
 *
 * <p>Validation rules:
 * <ul>
 *   <li>Page size capped at {@value #MAX_PAGE_SIZE} (mapped to 400).</li>
 *   <li>Duplicate {@code (project_id, name)} on create or rename returns 409 — the DB
 *       UNIQUE constraint catches this too, but the app-level pre-check turns the
 *       error into a clean message before the {@code DataIntegrityViolationException}
 *       fires.</li>
 *   <li>Soft-deleted rows are invisible to every read path.</li>
 *   <li>Deleting a playlist referenced by any non-DRAFT/non-CANCELLED assignment is
 *       refused with 409 — see {@link #softDelete}.</li>
 * </ul>
 */
@Service
public class PlaylistManagementService {

    public static final int MAX_PAGE_SIZE = 100;

    private static final Logger log = LoggerFactory.getLogger(PlaylistManagementService.class);

    private final PlaylistRepository playlistRepository;
    private final PlaylistItemRepository playlistItemRepository;
    private final ContentAssignmentRepository assignmentRepository;
    private final ProjectRepository projectRepository;
    private final OperatorScopeResolver operatorScopeResolver;

    public PlaylistManagementService(PlaylistRepository playlistRepository,
                                      PlaylistItemRepository playlistItemRepository,
                                      ContentAssignmentRepository assignmentRepository,
                                      ProjectRepository projectRepository,
                                      OperatorScopeResolver operatorScopeResolver) {
        this.playlistRepository = playlistRepository;
        this.playlistItemRepository = playlistItemRepository;
        this.assignmentRepository = assignmentRepository;
        this.projectRepository = projectRepository;
        this.operatorScopeResolver = operatorScopeResolver;
    }

    /** Aggregate stats for one playlist row in the listing. */
    public record PlaylistStats(long itemCount, long totalDurationSeconds) {
        public static final PlaylistStats EMPTY = new PlaylistStats(0L, 0L);
    }

    /** Service-layer view: a playlist plus its computed stats. */
    public record PlaylistView(Playlist playlist, PlaylistStats stats) {}

    /** Detail variant: includes the full ordered item list. */
    public record PlaylistDetailView(Playlist playlist, List<PlaylistItem> items, long totalDurationSeconds) {}

    @Transactional(readOnly = true)
    public Page<PlaylistView> list(Long projectId, String name, Pageable pageable) {
        if (pageable.getPageSize() > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Page size cannot exceed " + MAX_PAGE_SIZE);
        }
        ScopedProjects scope = operatorScopeResolver.resolve();
        if (scope.isEmptyScope()) {
            return Page.empty(pageable);
        }
        String normalizedName = (name == null || name.isBlank()) ? null : name.trim();

        Page<Playlist> page = playlistRepository.findFiltered(projectId, normalizedName, scope.narrowingIds(), pageable);
        if (page.isEmpty()) {
            return page.map(p -> new PlaylistView(p, PlaylistStats.EMPTY));
        }
        Map<Long, PlaylistStats> stats = aggregatesFor(
                page.getContent().stream().map(Playlist::getId).toList());
        return page.map(p -> new PlaylistView(p, stats.getOrDefault(p.getId(), PlaylistStats.EMPTY)));
    }

    @Transactional(readOnly = true)
    public PlaylistDetailView getDetail(Long id) {
        Playlist playlist = playlistRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Playlist", id));
        if (operatorScopeResolver.resolve().excludes(playlist.getProject().getId())) {
            throw new ResourceNotFoundException("Playlist", id);
        }
        List<PlaylistItem> items = playlistItemRepository.findByPlaylistIdWithContentFile(id);
        long totalDuration = items.stream()
                .mapToLong(this::resolveItemDuration)
                .sum();
        return new PlaylistDetailView(playlist, items, totalDuration);
    }

    @Transactional
    public PlaylistDetailView create(Long projectId, String name) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project", projectId));
        // Operator-scope guard (AUTHZ-02): a restricted operator may not create a playlist in a
        // project outside their assigned set. Collapses to the same 404 as a missing Project so
        // out-of-scope ids leak nothing, and runs BEFORE the duplicate check so a 409 can't
        // reveal which names exist in another tenant's project. Mirrors SyncGroup create.
        if (operatorScopeResolver.resolve().excludes(projectId)) {
            throw new ResourceNotFoundException("Project", projectId);
        }
        if (playlistRepository.existsByProjectIdAndNameAndDeletedAtIsNull(projectId, name)) {
            throw new IllegalStateException(
                    "Playlist with name '" + name + "' already exists in project " + projectId);
        }
        Playlist saved = playlistRepository.save(new Playlist(project, name, null));
        log.info("Created playlist [id={} project={} name='{}']", saved.getId(), projectId, name);
        // Fresh playlist has zero items — skip the aggregate query.
        return new PlaylistDetailView(saved, List.of(), 0L);
    }

    @Transactional
    public PlaylistDetailView rename(Long id, String name) {
        Playlist playlist = playlistRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Playlist", id));
        // No-op rename: same string → return current detail without touching the row
        // or running the duplicate guard (which would otherwise be redundant).
        if (playlist.getName().equals(name)) {
            return getDetail(id);
        }
        if (playlistRepository.existsDuplicateExcluding(playlist.getProject().getId(), name, id)) {
            throw new IllegalStateException(
                    "Playlist with name '" + name + "' already exists in project "
                            + playlist.getProject().getId());
        }
        playlist.setName(name);
        log.info("Renamed playlist [id={} newName='{}']", id, name);
        return getDetail(id);
    }

    /**
     * Soft-delete a playlist. Refuses the delete with 409 when any assignment that is
     * <i>active</i> (status not DRAFT/CANCELLED, not soft-deleted) still references the
     * playlist. Like content delete, this is intentionally not idempotent — already-
     * deleted returns 404 so the admin UI refreshes instead of silently 204-ing.
     */
    @Transactional
    public void softDelete(Long id) {
        Playlist playlist = playlistRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Playlist", id));

        long active = assignmentRepository.countActiveAssignmentsByPlaylistId(id);
        if (active > 0) {
            throw new IllegalStateException(
                    "Playlist is in use by " + active + " active assignment(s)");
        }
        playlist.softDelete();
        log.info("Soft-deleted playlist [id={} name='{}']", id, playlist.getName());
    }

    private Map<Long, PlaylistStats> aggregatesFor(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        var rows = playlistItemRepository.aggregateForPlaylists(ids);
        var out = new HashMap<Long, PlaylistStats>();
        for (Object[] row : rows) {
            Long pid = (Long) row[0];
            long count = ((Number) row[1]).longValue();
            long total = ((Number) row[2]).longValue();
            out.put(pid, new PlaylistStats(count, total));
        }
        return out;
    }

    private long resolveItemDuration(PlaylistItem item) {
        // Per-item override wins; fall back to the source content's natural duration; 0
        // when neither is set (an unprocessed file with no metadata yet).
        if (item.getDurationSeconds() != null) {
            return item.getDurationSeconds();
        }
        var cf = item.getContentFile();
        if (cf != null && cf.getDurationSeconds() != null) {
            return cf.getDurationSeconds();
        }
        return 0L;
    }
}
