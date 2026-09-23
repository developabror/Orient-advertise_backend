package uz.orientadvertise.services.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import uz.orientadvertise.services.api.dto.ContentFileDetail;
import uz.orientadvertise.services.api.dto.ContentFileSummary;
import uz.orientadvertise.services.api.openapi.SensitiveEndpoint;
import uz.orientadvertise.services.common.util.ProjectIds;
import uz.orientadvertise.services.common.util.VideoUploadValidator;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.service.ContentListService;
import uz.orientadvertise.services.service.ContentListService.ContentFileView;
import uz.orientadvertise.services.service.ContentListService.StreamUrl;
import uz.orientadvertise.services.service.ContentManagementService;
import uz.orientadvertise.services.service.ContentRetranscodeService;
import uz.orientadvertise.services.service.ContentRetranscodeService.RetranscodeResult;
import uz.orientadvertise.services.service.ContentUploadService;
import uz.orientadvertise.services.service.ContentUploadService.UploadResult;

@RestController
@RequestMapping("/api/content")
public class ContentController {

    private final ContentUploadService uploadService;
    private final ContentListService listService;
    private final ContentManagementService managementService;
    private final ContentRetranscodeService retranscodeService;

    public ContentController(ContentUploadService uploadService,
                              ContentListService listService,
                              ContentManagementService managementService,
                              ContentRetranscodeService retranscodeService) {
        this.uploadService = uploadService;
        this.listService = listService;
        this.managementService = managementService;
        this.retranscodeService = retranscodeService;
    }

    /**
     * Filtered, paginated content listing.
     *
     * <p>Validation, page-size capping, and advertiser scoping live in
     * {@link ContentListService}. The controller's only role-aware logic is deciding
     * whether to pass {@code advertiserUsername} as a scoping signal: only callers whose
     * <i>sole</i> authority is {@code ROLE_ADVERTISER} are scoped — a user that also
     * carries {@code ROLE_ADMIN}/{@code ROLE_OPERATOR}/{@code ROLE_VIEWER} sees the full
     * listing on the strength of that broader privilege.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER','ADVERTISER')")
    public ResponseEntity<Page<ContentFileSummary>> list(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) ContentFile.Status status,
            @RequestParam(required = false) String name,
            Pageable pageable) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        String advertiserUsername = isAdvertiserOnly(auth) ? auth.getName() : null;
        boolean operatorOnly = CallerRoles.isOperatorOnly(auth);
        String operatorUsername = operatorOnly ? auth.getName() : null;
        String callerUsername = CallerRoles.usernameOf(auth);
        boolean callerCanManageAll = CallerRoles.isAdmin(auth);
        Page<ContentFileView> page =
                listService.list(projectId, status, name, advertiserUsername, operatorUsername, pageable);
        return ResponseEntity.ok(page.map(
                v -> ContentFileSummary.from(v, callerUsername, callerCanManageAll, operatorOnly)));
    }

    /**
     * Single-content detail. Status disambiguation matches {@code /api/stats/content/{id}}:
     * unknown id and soft-deleted both surface as 404 (treated as gone), while an
     * advertiser asking for a row that exists but isn't linked to them gets 403 — only
     * after the existence check, so admin/operator/viewer never see 403 here. The 403
     * vs 404 split is enforced inside {@link ContentListService#getDetail}.
     *
     * <p>Note: any caller carrying {@code ROLE_ADVERTISER} is treated as an advertiser
     * here, even if they also carry a broader role. The list endpoint uses a stricter
     * "advertiser-only" check; for the detail endpoint we follow the StatsController
     * convention so per-row access checks are consistent across the API.
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER','ADVERTISER')")
    public ResponseEntity<ContentFileDetail> detail(@PathVariable Long id) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        boolean isAdvertiser = auth != null && auth.getAuthorities() != null
                && auth.getAuthorities().stream()
                        .anyMatch(a -> "ROLE_ADVERTISER".equals(a.getAuthority()));
        boolean operatorOnly = CallerRoles.isOperatorOnly(auth);
        String username = CallerRoles.usernameOf(auth);
        boolean callerCanManageAll = CallerRoles.isAdmin(auth);

        ContentFileView view = listService.getDetail(id, username, isAdvertiser, operatorOnly);
        return ResponseEntity.ok(ContentFileDetail.from(view, username, callerCanManageAll, operatorOnly));
    }

    /**
     * Soft-delete a content file. Sets {@code deletedAt} on the row; the underlying
     * objects in {@code content-raw} / {@code content-processed} are intentionally
     * retained for audit/restore — a separate lifecycle policy culls them later.
     *
     * <p>Reference safety: a file in use by N <i>active</i> playlists returns 409 with a
     * message naming N — see {@link ContentManagementService#softDelete}. Idempotency is
     * deliberately not provided: deleting an already-deleted row returns 404 so admin
     * UIs notice and refresh, instead of silently 204-ing on a stale row.
     */
    @Operation(summary = "[SENSITIVE] Soft-delete a content file")
    @SensitiveEndpoint
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Soft-deleted; storage retained"),
            @ApiResponse(responseCode = "403", description = "Caller lacks ADMIN/OPERATOR"),
            @ApiResponse(responseCode = "404", description = "Unknown id, or already soft-deleted"),
            @ApiResponse(responseCode = "409", description = "Content is referenced by active playlists; remove first")
    })
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        // Operator-only callers may delete ONLY content they own; granted-not-owned ⇒ 403,
        // neither owned nor granted ⇒ 404 (no existence oracle). Admins are unrestricted.
        listService.assertOperatorCanManage(id, CallerRoles.usernameOf(auth), CallerRoles.isOperatorOnly(auth));
        managementService.softDelete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Upload a raw content file.
     * <p>
     * {@code urgent=true} jumps the transcode queue (separate executor pool) and
     * triggers an immediate WebSocket fan-out to all connected devices in the
     * file's project. WebSocket push is best-effort — devices not currently
     * connected pick up the same content on their next heartbeat poll.
     *
     * <p><b>Project is optional.</b> Omitting {@code projectId} (or passing one that
     * doesn't resolve to an existing project) is not an error — the file is saved as
     * orphan content with {@code projectId=null} on the response. The operator can
     * attach a project later via {@code PATCH /api/content/{id}/project}. Orphan
     * content is intentionally not pushed to devices via the urgent fan-out below
     * (no project = no audience to fan out to).
     */
    @Operation(summary = "Upload a raw content file")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Upload accepted; transcoding queued",
                    content = @Content(schema = @Schema(implementation = UploadResponse.class),
                            examples = {
                                    @ExampleObject(name = "Bound to project (priority)", value = """
                                            {
                                              "fileId": 1042,
                                              "status": "UPLOADED",
                                              "storageKey": "raw/2026/01/abc123.mp4",
                                              "urgent": true,
                                              "projectId": 7,
                                              "message": "Priority upload accepted; queued at the front of the transcode pool"
                                            }
                                            """),
                                    @ExampleObject(name = "Orphan upload (no project)", value = """
                                            {
                                              "fileId": 1043,
                                              "status": "UPLOADED",
                                              "storageKey": "raw/2026/01/def456.mp4",
                                              "urgent": false,
                                              "projectId": null,
                                              "message": "Upload accepted as orphan content (no project bound); attach a project via PATCH /api/content/{id}/project. Transcoding in progress"
                                            }
                                            """)
                            })),
            @ApiResponse(responseCode = "400", description = "Validation failed (file type, size, name)"),
            @ApiResponse(responseCode = "403", description = "Caller lacks ADMIN/OPERATOR")
    })
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public ResponseEntity<UploadResponse> upload(
            @RequestParam(value = "projectId", required = false) Long projectId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "urgent", required = false, defaultValue = "false") boolean urgent) throws Exception {

        VideoUploadValidator.validate(file.getOriginalFilename(), file.getContentType(), file.getSize());

        var auth = SecurityContextHolder.getContext().getAuthentication();
        String uploader = auth != null ? auth.getName() : null;
        UploadResult result = uploadService.upload(
                ProjectIds.normalize(projectId),
                file.getOriginalFilename(),
                file.getContentType(),
                file.getSize(),
                file.getInputStream(),
                urgent,
                uploader);

        // VG-19: this used to broadcast an URGENT_CONTENT frame. It is gone, and nothing is lost:
        // the frame went to EVERY connected device in the fleet (carrying a contentFileId and
        // projectId to devices of other projects), the device spec tells clients to ignore it
        // because it carries nothing actionable, and a just-uploaded file cannot be played anyway —
        // it is not transcoded, not in a playlist and not assigned. `urgent` means exactly one real
        // thing, which it has always done: this file goes to the FRONT of the transcode queue.
        String message;
        if (result.projectId() == null) {
            message = "Upload accepted as orphan content (no project bound); attach a project via PATCH /api/content/{id}/project. Transcoding in progress";
        } else if (urgent) {
            message = "Priority upload accepted; queued at the front of the transcode pool";
        } else {
            message = "Upload accepted; transcoding in progress";
        }
        return ResponseEntity.accepted().body(new UploadResponse(
                result.fileId(),
                result.status(),
                result.storageKey(),
                result.urgent(),
                result.projectId(),
                message));
    }

    /**
     * Bind (or re-bind) a project to a content file. Used to fill in the project
     * after an orphan upload, or to re-route content between projects.
     *
     * <p>Body: {@code {"projectId": 5}} — pass {@code null} to clear the binding
     * (returns the file to orphan state).
     *
     * <p>Operator-only callers may move ONLY content they own (granted ⇒ 403, neither ⇒ 404 —
     * the same rule as delete), and only between projects in their scope (see
     * {@link ContentManagementService#assignProject}).
     */
    @Operation(summary = "Assign or clear the project on a content file")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Project bound (or cleared if null)"),
            @ApiResponse(responseCode = "403", description = "Caller lacks ADMIN/OPERATOR; or an operator "
                    + "acting on granted-not-owned content, or on content bound to a project outside their scope"),
            @ApiResponse(responseCode = "404", description = "Unknown content file (or already soft-deleted, "
                    + "or not visible to the operator), or unknown / out-of-scope project")
    })
    @PatchMapping("/{id}/project")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ResponseEntity<Void> assignProject(@PathVariable Long id,
                                               @RequestBody AssignProjectRequest body) {
        Long projectId = body == null ? null : ProjectIds.normalize(body.projectId());
        var auth = SecurityContextHolder.getContext().getAuthentication();
        listService.assertOperatorCanManage(id, CallerRoles.usernameOf(auth), CallerRoles.isOperatorOnly(auth));
        managementService.assignProject(id, projectId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Short-lived presigned URL for streaming a processed content file directly from MinIO.
     *
     * <p>Returns a URL the FE can plug into {@code <video src>} — MinIO handles HTTP Range
     * so seeking works without backend involvement. ADMIN/OPERATOR/VIEWER can stream any
     * file; ADVERTISER is scoped to files linked via {@code advertiser_content_access}
     * (same access model as {@code GET /api/content/{id}}).
     *
     * <p>{@code expirySeconds} is optional. When omitted, falls back to the configured
     * {@code app.minio.presigned-url-expiry-minutes}. Values are clamped server-side to
     * {@value ContentListService#MIN_STREAM_EXPIRY_SECONDS}..{@value ContentListService#MAX_STREAM_EXPIRY_SECONDS}.
     */
    @Operation(summary = "Get a presigned URL to stream a content file")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Presigned URL issued"),
            @ApiResponse(responseCode = "403", description = "Advertiser caller is not linked to this content"),
            @ApiResponse(responseCode = "404", description = "Unknown content file (or soft-deleted)"),
            @ApiResponse(responseCode = "409", description = "Content exists but is not READY yet — poll status, retry when READY")
    })
    @GetMapping("/{id}/stream-url")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER','ADVERTISER')")
    public ResponseEntity<StreamUrl> streamUrl(@PathVariable Long id,
                                                 @RequestParam(value = "expirySeconds", required = false) Integer expirySeconds) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        boolean isAdvertiser = auth != null && auth.getAuthorities() != null
                && auth.getAuthorities().stream()
                        .anyMatch(a -> "ROLE_ADVERTISER".equals(a.getAuthority()));
        boolean operatorOnly = CallerRoles.isOperatorOnly(auth);
        String username = CallerRoles.usernameOf(auth);
        return ResponseEntity.ok(listService.streamUrl(id, username, isAdvertiser, operatorOnly, expirySeconds));
    }

    /**
     * Re-queue the transcode for a stuck or failed content file — the operator's escape hatch.
     *
     * <p>Before v1.0.132 there was none: a file whose async dispatch was lost sat in
     * {@code UPLOADED} forever, no endpoint could retry it, and restarting the application recovered
     * nothing. {@code TranscodeSweeper} now re-drives such rows automatically within minutes; this
     * endpoint is for the operator who is already looking at the row and does not want to wait.
     *
     * <p>The attempt counter is reset on an explicit retry — a human asking again is not the same
     * as an automatic retry, and must not be refused because earlier automatic attempts used up the
     * budget. The claim is the same atomic compare-and-set the sweeper uses, so a double-click (or a
     * click that races the sweeper) starts exactly one encode.
     *
     * <p>Operator-only callers may retry only content they can see (owned or granted); anything
     * else is 404, so the sequential ids can't be walked to keep ffmpeg busy.
     */
    @Operation(summary = "Re-queue transcoding for a stuck or failed content file")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Claimed and queued; returns the new status"),
            @ApiResponse(responseCode = "403", description = "Caller lacks ADMIN/OPERATOR"),
            @ApiResponse(responseCode = "404", description = "Unknown content file (or soft-deleted, or not "
                    + "visible to the operator)"),
            @ApiResponse(responseCode = "409", description = "Status is not UPLOADED/FAILED, the raw object "
                    + "is missing, or another worker just claimed it — see `message`")
    })
    @PostMapping("/{id}/retranscode")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ResponseEntity<RetranscodeResult> retranscode(@PathVariable Long id) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        listService.assertOperatorCanAccess(id, CallerRoles.usernameOf(auth), CallerRoles.isOperatorOnly(auth));
        return ResponseEntity.ok(retranscodeService.retranscode(id, CallerRoles.usernameOf(auth)));
    }

    private static boolean isAdvertiserOnly(Authentication auth) {
        if (auth == null || auth.getAuthorities() == null) {
            return false;
        }
        boolean hasAdvertiser = false;
        boolean hasOther = false;
        for (var ga : auth.getAuthorities()) {
            String role = ga.getAuthority();
            if ("ROLE_ADVERTISER".equals(role)) {
                hasAdvertiser = true;
            } else if ("ROLE_ADMIN".equals(role) || "ROLE_OPERATOR".equals(role) || "ROLE_VIEWER".equals(role)) {
                hasOther = true;
            }
        }
        return hasAdvertiser && !hasOther;
    }

    @Schema(name = "UploadResponse", description = "Result envelope for POST /api/content/upload")
    public record UploadResponse(
            @Schema(description = "Server-assigned content file id", example = "1042")
            Long fileId,
            // The value is ContentFile.Status.UPLOADED.name() — a status the enum can actually
            // produce. The example used to read "PROCESSING", which it never returns.
            @Schema(description = "Lifecycle status of the file at the moment the upload was "
                    + "accepted; always UPLOADED (transcoding starts after the commit)",
                    example = "UPLOADED")
            String status,
            @Schema(description = "MinIO object key under the raw bucket", example = "raw/2026/01/abc123.mp4")
            String storageKey,
            @Schema(description = "Whether the upload was queued at the FRONT of the transcode pool. That is all "
                    + "`urgent` has ever meant: it does not put the file on any screen. Transcoding status is "
                    + "broadcast to operators over /ws/dashboard as CONTENT_STATUS_CHANGE frames.",
                    example = "true")
            boolean urgent,
            @Schema(description = "Bound project id, or null for orphan uploads", example = "7", nullable = true)
            Long projectId,
            @Schema(description = "Human-readable summary of the outcome",
                    example = "Priority upload accepted; queued at the front of the transcode pool")
            String message
    ) {}

    public record AssignProjectRequest(Long projectId) {}
}
