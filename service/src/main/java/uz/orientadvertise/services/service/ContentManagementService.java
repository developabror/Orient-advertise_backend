package uz.orientadvertise.services.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.orientadvertise.services.common.exception.AccessForbiddenException;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.repository.ContentFileRepository;
import uz.orientadvertise.services.domain.repository.PlaylistItemRepository;
import uz.orientadvertise.services.domain.repository.ProjectRepository;

/**
 * Lifecycle operations on {@code ContentFile} that aren't covered by upload or read paths.
 *
 * <p>Only soft-delete for now: the row is stamped with {@code deletedAt} and the
 * accompanying MinIO objects in {@code content-raw} / {@code content-processed} are
 * intentionally retained. A separate lifecycle policy culls those bytes asynchronously,
 * which (a) keeps an audit/restore path open during the grace period and (b) makes the
 * delete path tolerant of an unhealthy object store — the API request never has to wait
 * on a network hop to a third-party service.
 *
 * <p>Reference safety: a content file currently linked from one or more <i>active</i>
 * playlists (i.e. not themselves soft-deleted) is rejected with a 409 Conflict. We refuse
 * to cascade-delete the playlists out from under operators — the more conservative
 * outcome is to make the caller remove the references first. Once unlinked, retry
 * succeeds.
 */
@Service
public class ContentManagementService {

    private static final Logger log = LoggerFactory.getLogger(ContentManagementService.class);

    private final ContentFileRepository contentFileRepository;
    private final PlaylistItemRepository playlistItemRepository;
    private final ProjectRepository projectRepository;
    private final OperatorScopeResolver operatorScopeResolver;

    public ContentManagementService(ContentFileRepository contentFileRepository,
                                     PlaylistItemRepository playlistItemRepository,
                                     ProjectRepository projectRepository,
                                     OperatorScopeResolver operatorScopeResolver) {
        this.contentFileRepository = contentFileRepository;
        this.playlistItemRepository = playlistItemRepository;
        this.projectRepository = projectRepository;
        this.operatorScopeResolver = operatorScopeResolver;
    }

    /**
     * Soft-delete a content file. Idempotency is intentionally <b>NOT</b> provided —
     * deleting an already-soft-deleted file returns 404 so the admin UI is forced to
     * refresh and reflect that someone else already removed it. Silent 204s on a stale
     * row would mask the divergence.
     */
    @Transactional
    public void softDelete(Long contentFileId) {
        var content = contentFileRepository.findById(contentFileId)
                .filter(f -> f.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("ContentFile", contentFileId));

        long activePlaylistCount = playlistItemRepository
                .countDistinctActivePlaylistsByContentFileId(contentFileId);
        if (activePlaylistCount > 0) {
            // IllegalStateException maps to 409 Conflict via GlobalExceptionHandler — the
            // exact message format is part of the API contract (the admin UI parses N).
            throw new IllegalStateException(
                    "Content is in use by " + activePlaylistCount + " playlist(s); "
                            + "remove from playlists first");
        }

        content.softDelete();
        log.info("Soft-deleted content [id={} name={} project={}]",
                contentFileId, content.getName(),
                content.getProject() != null ? content.getProject().getId() : null);
    }

    /**
     * Attach (or re-attach) a project to a content file. Used to fill in the project
     * after an "orphan" upload — see {@code ContentUploadService} for the upload-time
     * tolerance that lets bytes land without a valid project.
     *
     * <p>{@code projectId == null} clears the binding, returning the file to orphan state.
     * That path is intentional: it lets an admin un-do a wrong assignment without
     * deleting and re-uploading. Linkage to playlists is unaffected — playlist_item
     * references the content_file directly, not via project.
     *
     * <p>Unknown projectId is a 404 (here we know the operator is filling in a real
     * association, so a wrong id is a mistake worth surfacing — in contrast to upload
     * where we accept and defer).
     *
     * <p>Operator scope (AUTHZ-01): a restricted operator may only move content between projects
     * in their own set. A file currently bound to a project outside that set is 403 — the
     * operator can already see the file, so there is nothing to hide — and an out-of-scope
     * target is the same 404 as an unknown one. Row ownership is checked by the controller.
     */
    @Transactional
    public void assignProject(Long contentFileId, Long projectId) {
        var content = contentFileRepository.findById(contentFileId)
                .filter(f -> f.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("ContentFile", contentFileId));

        var scope = operatorScopeResolver.resolve();
        if (content.getProject() != null && scope.excludes(content.getProject().getId())) {
            throw new AccessForbiddenException(
                    "Content " + contentFileId + " is bound to a project outside your scope");
        }

        if (projectId == null) {
            content.setProject(null);
            log.info("Cleared project binding on content [id={}]", contentFileId);
            return;
        }

        if (scope.excludes(projectId)) {
            throw new ResourceNotFoundException("Project", projectId);
        }
        var project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project", projectId));
        content.setProject(project);
        log.info("Assigned project to content [id={} project={}]", contentFileId, projectId);
    }
}
