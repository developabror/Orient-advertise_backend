package uz.orientadvertise.services.service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.orientadvertise.services.common.exception.AccessForbiddenException;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.common.util.ProjectIds;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.domain.repository.AdvertiserContentAccessRepository;
import uz.orientadvertise.services.domain.repository.AppUserRepository;
import uz.orientadvertise.services.domain.repository.ContentFileRepository;
import uz.orientadvertise.services.domain.repository.OperatorContentAccessRepository;

/**
 * Filtered, paginated content listing for {@code GET /api/content}.
 *
 * <p>Validation:
 * <ul>
 *   <li>Page size capped at {@value #MAX_PAGE_SIZE} — anything larger throws
 *       {@link IllegalArgumentException} (mapped to 400 by the global handler) so a caller
 *       cannot accidentally pull a huge payload.</li>
 *   <li>{@code name} is whitespace-trimmed; blank reduces to {@code null} (no filter).</li>
 *   <li>Soft-deleted rows are excluded at the repository layer, not here.</li>
 * </ul>
 *
 * <p>Advertiser scoping: when {@code advertiserUsername} is non-null, the listing is
 * restricted to content files linked via {@code advertiser_content_access}. An advertiser
 * with zero grants returns {@link Page#empty(Pageable)} without touching the content
 * table — and an unknown username does the same, so a stale token can never widen the
 * scope. Other roles pass {@code null} and see every non-deleted row matching the
 * filters.
 */
@Service
public class ContentListService {

    public static final int MAX_PAGE_SIZE = 100;

    /** Lower bound on stream-url expiry — anything shorter is impractical for a video player. */
    public static final int MIN_STREAM_EXPIRY_SECONDS = 60;
    /** Upper bound — caps how long a leaked URL stays usable. */
    public static final int MAX_STREAM_EXPIRY_SECONDS = 24 * 60 * 60;

    /**
     * TTL for inline thumbnail URLs returned on listing/detail responses. 15 min is long
     * enough to cover the operator's interactive session (the FE loads once and keeps the
     * URL in memory) but short enough that a leaked URL goes cold quickly.
     */
    public static final int THUMBNAIL_URL_EXPIRY_MINUTES = 15;

    private final ContentFileRepository contentFileRepository;
    private final AdvertiserContentAccessRepository accessRepository;
    private final OperatorContentAccessRepository operatorAccessRepository;
    private final AppUserRepository userRepository;
    private final FileStorageService fileStorageService;
    private final int defaultStreamExpirySeconds;

    public ContentListService(ContentFileRepository contentFileRepository,
                               AdvertiserContentAccessRepository accessRepository,
                               OperatorContentAccessRepository operatorAccessRepository,
                               AppUserRepository userRepository,
                               FileStorageService fileStorageService,
                               @Value("${app.minio.presigned-url-expiry-minutes:60}") int presignedUrlExpiryMinutes) {
        this.contentFileRepository = contentFileRepository;
        this.accessRepository = accessRepository;
        this.operatorAccessRepository = operatorAccessRepository;
        this.userRepository = userRepository;
        this.fileStorageService = fileStorageService;
        this.defaultStreamExpirySeconds = presignedUrlExpiryMinutes * 60;
    }

    @Transactional(readOnly = true)
    public Page<ContentFileView> list(Long projectId,
                                       ContentFile.Status status,
                                       String name,
                                       String advertiserUsername,
                                       String operatorUsername,
                                       Pageable pageable) {
        if (pageable.getPageSize() > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Page size cannot exceed " + MAX_PAGE_SIZE);
        }
        String normalizedName = (name == null || name.isBlank()) ? null : name.trim();
        // The FE sends projectId=-1 for a playlist bound to the seeded "Unassigned" project,
        // but unassigned content is stored with project_id=NULL (the write path normalizes the
        // sentinel away — see ContentController upload/rebind). Coerce <=0 to null here too so
        // the query stops filtering by project instead of matching the never-used -1 row.
        Long effectiveProjectId = ProjectIds.normalize(projectId);

        Page<ContentFile> page;
        if (advertiserUsername != null) {
            // ADVERTISER branch — unchanged: scoped to advertiser_content_access grants.
            var user = userRepository.findByUsername(advertiserUsername).orElse(null);
            if (user == null) {
                return Page.empty(pageable);
            }
            var ids = accessRepository.findContentIdsByUserId(user.getId());
            if (ids.isEmpty()) {
                return Page.empty(pageable);
            }
            page = contentFileRepository.findFilteredScoped(effectiveProjectId, status, normalizedName, ids, pageable);
        } else if (operatorUsername != null) {
            // OPERATOR branch — scoped to owned ∪ admin-granted content (NOT project-gated).
            var user = userRepository.findByUsername(operatorUsername).orElse(null);
            if (user == null) {
                return Page.empty(pageable);                            // fail-closed
            }
            Set<Long> ids = new HashSet<>(contentFileRepository.findIdsByUploadedBy(operatorUsername));
            ids.addAll(operatorAccessRepository.findContentIdsByUserId(user.getId()));
            if (ids.isEmpty()) {
                return Page.empty(pageable);                            // empty-union short-circuit
            }
            page = contentFileRepository.findFilteredScoped(effectiveProjectId, status, normalizedName, ids, pageable);
        } else {
            page = contentFileRepository.findFiltered(effectiveProjectId, status, normalizedName, pageable);
        }
        return page.map(this::decorateWithThumbnail);
    }

    /**
     * Single-content lookup for {@code GET /api/content/{id}}.
     *
     * <p>Ordering is deliberate: existence is checked first so an admin querying a truly
     * missing id sees 404, while an advertiser querying a real-but-inaccessible id sees
     * 403. Reversing the order would leak existence ("the content with this id is not
     * yours") to advertisers — the same disambiguation pattern as
     * {@link ContentStatsService}.
     *
     * <p>Soft-deleted rows are treated as gone (404).
     */
    @Transactional(readOnly = true)
    public ContentFileView getDetail(Long contentFileId, String callerUsername,
                                     boolean callerIsAdvertiser, boolean callerIsOperator) {
        return decorateWithThumbnail(
                loadAndCheckAccess(contentFileId, callerUsername, callerIsAdvertiser, callerIsOperator));
    }

    /**
     * Decorate a ContentFile with a presigned thumbnail URL when one applies. URL only
     * generated for {@code status=READY} rows that have a {@code thumbnailStorageKey} —
     * for every other row, both fields are null (the FE renders a placeholder).
     *
     * <p>Presigning is a pure crypto op against MinIO's signing key (no network call), so
     * the per-row cost is negligible at page sizes ≤ {@link #MAX_PAGE_SIZE} — calling this
     * in a loop over the page is fine. The {@code expiresAt} is computed from "now" so it
     * matches the URL's actual lifetime as closely as wall-clock skew allows.
     */
    private ContentFileView decorateWithThumbnail(ContentFile file) {
        if (file.getStatus() != ContentFile.Status.READY || file.getThumbnailStorageKey() == null) {
            return new ContentFileView(file, null, null);
        }
        String url = fileStorageService.presignedThumbnailUrl(
                file.getThumbnailStorageKey(), THUMBNAIL_URL_EXPIRY_MINUTES);
        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(THUMBNAIL_URL_EXPIRY_MINUTES));
        return new ContentFileView(file, url, expiresAt);
    }

    /**
     * Generate a short-lived presigned URL for the FE/dashboard to stream a content file
     * directly from MinIO via a {@code <video>} tag. Returns the same URL shape that's
     * already issued to player devices, just gated by the operator/advertiser ACLs
     * instead of device sync.
     *
     * <p>Status mapping mirrors {@link #getDetail}:
     * <ul>
     *   <li>{@code 404} — unknown id or soft-deleted</li>
     *   <li>{@code 404} — advertiser asking for an inaccessible row would surface as 403
     *       via {@link AccessForbiddenException}; matches the listing/detail conventions</li>
     *   <li>{@code 409} — file exists but isn't {@code READY} yet (still transcoding,
     *       failed, or invalid). The FE should poll until status flips, then retry.</li>
     * </ul>
     *
     * <p>{@code expirySeconds} is clamped to {@code [MIN_STREAM_EXPIRY_SECONDS,
     * MAX_STREAM_EXPIRY_SECONDS]}. A null value falls back to the configured
     * {@code app.minio.presigned-url-expiry-minutes}.
     */
    @Transactional(readOnly = true)
    public StreamUrl streamUrl(Long contentFileId, String callerUsername,
                                boolean callerIsAdvertiser, boolean callerIsOperator, Integer expirySeconds) {
        ContentFile content = loadAndCheckAccess(contentFileId, callerUsername, callerIsAdvertiser, callerIsOperator);

        if (content.getStatus() != ContentFile.Status.READY || content.getProcessedStorageKey() == null) {
            throw new IllegalStateException(
                    "Content not ready for streaming, current status: " + content.getStatus());
        }

        int seconds = clampExpiry(expirySeconds);
        int minutes = Math.max(1, (seconds + 59) / 60);
        String url = fileStorageService.presignedProcessedUrl(
                content.getProcessedStorageKey(), minutes);
        Instant expiresAt = Instant.now().plus(Duration.ofSeconds(seconds));
        return new StreamUrl(url, expiresAt, content.getContentType());
    }

    private ContentFile loadAndCheckAccess(Long contentFileId, String callerUsername,
                                             boolean callerIsAdvertiser, boolean callerIsOperator) {
        ContentFile content = contentFileRepository.findById(contentFileId)
                .filter(f -> f.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("ContentFile", contentFileId));

        if (callerIsAdvertiser) {
            // ADVERTISER — unchanged: 403-after-existence for a real-but-not-granted row.
            var user = userRepository.findByUsername(callerUsername)
                    .orElseThrow(() -> new AccessForbiddenException("Unknown advertiser"));
            boolean hasAccess = accessRepository.existsByUserIdAndContentFileId(
                    user.getId(), contentFileId);
            if (!hasAccess) {
                throw new AccessForbiddenException(
                        "Advertiser does not have access to content " + contentFileId);
            }
        } else if (callerIsOperator) {
            // OPERATOR (operator-only) — visible iff owned OR admin-granted. A real-but-
            // inaccessible row throws ResourceNotFoundException (404, NOT 403): operators get
            // no 403-vs-404 existence oracle. Owner compare is caller-side so a legacy
            // NULL-owner row is never matched and never NPEs.
            boolean owned = callerUsername != null && callerUsername.equals(content.getUploadedBy());
            boolean granted = false;
            if (!owned) {
                var user = userRepository.findByUsername(callerUsername).orElse(null);
                granted = user != null
                        && operatorAccessRepository.existsByUserIdAndContentFileId(user.getId(), contentFileId);
            }
            if (!owned && !granted) {
                throw new ResourceNotFoundException("ContentFile", contentFileId);
            }
        }
        return content;
    }

    /**
     * Per-row guard for {@code DELETE /api/content/{id}} by an operator-only caller. Existence
     * (incl. soft-delete) is checked first ⇒ 404. Then: owned ⇒ allowed (caller proceeds to
     * soft-delete); granted-but-not-owned ⇒ {@link AccessForbiddenException} (403, matches
     * {@code canManage=false}); neither owned nor granted ⇒ {@link ResourceNotFoundException}
     * (404, no existence oracle). No-op for non-operator callers (admin is unrestricted).
     */
    @Transactional(readOnly = true)
    public void assertOperatorCanDelete(Long contentFileId, String callerUsername, boolean callerIsOperatorOnly) {
        if (!callerIsOperatorOnly) {
            return;
        }
        ContentFile content = contentFileRepository.findById(contentFileId)
                .filter(f -> f.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("ContentFile", contentFileId));

        boolean owned = callerUsername != null && callerUsername.equals(content.getUploadedBy());
        if (owned) {
            return;
        }
        var user = userRepository.findByUsername(callerUsername).orElse(null);
        boolean granted = user != null
                && operatorAccessRepository.existsByUserIdAndContentFileId(user.getId(), contentFileId);
        if (granted) {
            throw new AccessForbiddenException(
                    "Operator may view but not delete granted content " + contentFileId);
        }
        throw new ResourceNotFoundException("ContentFile", contentFileId);
    }

    private int clampExpiry(Integer expirySeconds) {
        if (expirySeconds == null) {
            return defaultStreamExpirySeconds;
        }
        return Math.max(MIN_STREAM_EXPIRY_SECONDS,
                Math.min(MAX_STREAM_EXPIRY_SECONDS, expirySeconds));
    }

    public record StreamUrl(String url, Instant expiresAt, String contentType) {}

    /**
     * Listing/detail projection: bundles a {@link ContentFile} with a freshly-minted
     * presigned thumbnail URL (15-min TTL) when the row is READY and has a
     * {@code thumbnailStorageKey}. Both URL fields are null otherwise — for transcoding
     * rows, failed rows, or rows where the (best-effort) poster step did not produce a
     * thumbnail. The DTOs map this directly onto wire fields.
     */
    public record ContentFileView(ContentFile file, String thumbnailUrl, Instant thumbnailExpiresAt) {}
}
