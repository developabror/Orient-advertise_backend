package uz.orientadvertise.services.service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.common.exception.StorageUnavailableException;
import uz.orientadvertise.services.domain.model.ContentAssignment;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.PlaylistItem;
import uz.orientadvertise.services.domain.model.SyncGroupPlaybackOverride;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.PlaylistItemRepository;
import uz.orientadvertise.services.domain.repository.SyncGroupPlaybackOverrideRepository;

/**
 * Computes the diff a device must apply to reach the server-side expected content state.
 *
 * <p>Each call regenerates presigned URLs (default 60-minute TTL configured by
 * {@code app.minio.presigned-url-expiry-minutes}). Re-syncing returns fresh URLs —
 * devices that were mid-download and lost a URL just call again.
 *
 * <p>Edge cases:
 * <ul>
 *   <li>{@code currentVersion == null} → device is fresh; treat its held set as empty,
 *       return the full expected list as files-to-add (full sync).
 *   <li>No active assignment → device should drop everything it currently has.
 *   <li>Files that are not yet READY (still UPLOADED/TRANSCODING) are skipped — the
 *       device will pick them up on the next sync once the pipeline finishes.
 *   <li>A file whose object is missing from MinIO is skipped (WARN). MinIO being
 *       <em>unreachable</em> is the opposite case: the whole call fails with
 *       {@link StorageUnavailableException} → HTTP 503 "retry later", because a partial plan
 *       returned as 200 is indistinguishable to the device from a correct one (v1.0.144).
 * </ul>
 */
@Service
public class DeviceSyncService {

    private static final Logger log = LoggerFactory.getLogger(DeviceSyncService.class);

    private final DeviceRepository deviceRepository;
    private final ContentAssignmentService assignmentService;
    private final ContentVersionService contentVersionService;
    private final PlaylistItemRepository playlistItemRepository;
    private final FileStorageService fileStorageService;
    private final PlaybackScheduleService playbackScheduleService;
    private final SyncGroupPlaybackOverrideRepository overrideRepository;

    /**
     * Sync URLs are signed for longer than ad-hoc download URLs because devices over
     * mobile/cellular links may take a long time to pull large files. Defaults to 2 hours.
     */
    @Value("${app.device.sync-url-expiry-minutes:120}")
    private int presignedUrlExpiryMinutes;

    public DeviceSyncService(DeviceRepository deviceRepository,
                              ContentAssignmentService assignmentService,
                              ContentVersionService contentVersionService,
                              PlaylistItemRepository playlistItemRepository,
                              FileStorageService fileStorageService,
                              PlaybackScheduleService playbackScheduleService,
                              SyncGroupPlaybackOverrideRepository overrideRepository) {
        this.deviceRepository = deviceRepository;
        this.assignmentService = assignmentService;
        this.contentVersionService = contentVersionService;
        this.playlistItemRepository = playlistItemRepository;
        this.fileStorageService = fileStorageService;
        this.playbackScheduleService = playbackScheduleService;
        this.overrideRepository = overrideRepository;
    }

    @Transactional
    public SyncPlan computeSyncPlan(Long deviceId, String currentVersion, Set<Long> currentFileIds) {
        var device = deviceRepository.findByIdAndDeletedAtIsNull(deviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Device", deviceId));

        // Null version = fresh device; ignore any stale currentFileIds the caller sent.
        boolean fullSync = currentVersion == null;
        Set<Long> deviceHeld = fullSync || currentFileIds == null
                ? Set.of()
                : Set.copyOf(currentFileIds);

        Instant now = Instant.now();
        Instant expiresAt = now.plus(Duration.ofMinutes(presignedUrlExpiryMinutes));

        ContentAssignment assignment = assignmentService.resolveForDevice(device, now);
        if (assignment == null || assignment.getPlaylist() == null) {
            // No active assignment (window expired, all matching assignments excluded, or
            // none exist) — purge everything the device holds (blank screen).
            //
            // PRODUCT DECISION (purge, not retain): a between-windows gap is intentionally
            // indistinguishable from "cancelled" in this model — assignments carry a single
            // start/end window, not a recurring schedule, so there is no reliable signal for
            // "temporary lapse". A campaign that must survive a device going offline past its
            // window therefore uses a far-future endTime (year-2100 sentinel); the window never
            // lapses, so this branch is never hit for a live campaign. Accidental purges are
            // prevented FE-side by that sentinel, not by retaining stale content here.
            log.debug("Sync [device={}] no active assignment; deleting {} held files",
                    deviceId, deviceHeld.size());
            // No expected version ⇒ nothing a confirm could ever match. A marker armed for an
            // assignment that has since lapsed would otherwise only trip SYNC_TIMEOUT.
            clearStalePending(device);
            // No content ⇒ no loop: still echo syncGroupId, but no anchor/schedule (device stays idle).
            return new SyncPlan(deviceId, null, fullSync,
                    List.of(),
                    deviceHeld.stream().sorted().toList(),
                    List.of(),
                    presignedUrlExpiryMinutes,
                    expiresAt,
                    device.getSyncGroupId(), null, 0L, null);
        }

        var items = playlistItemRepository.findByPlaylistIdOrderByPositionAsc(assignment.getPlaylist().getId());

        // Deliverable base set = items whose content is READY with a processed object key.
        // The SAME predicate ({@link #isDeliverable}) defines the order here, the /playlist view,
        // and the JUMP index range in PlaylistControlService — so the three can never drift.
        var orderedReadyFiles = items.stream()
                .filter(DeviceSyncService::isDeliverable)
                .map(PlaylistItem::getContentFile)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Set<Long> expectedIds = orderedReadyFiles.stream()
                .map(ContentFile::getId)
                .collect(Collectors.toUnmodifiableSet());

        // Per-file URL generation is isolated: a missing object or signing failure on ONE
        // file must not block the others, so those files are simply omitted from filesToAdd
        // (storage inconsistency is logged for ops). A storage OUTAGE is the opposite case and
        // propagates out of tryBuildFileToAdd as StorageUnavailableException → 503: it is not a
        // fact about one file, and answering 200 with a short list would have the device apply an
        // incomplete plan as if it were correct. Note this runs BEFORE filesToDelete is computed,
        // so an outage can never produce a delete instruction either.
        List<SyncFileToAdd> filesToAdd = orderedReadyFiles.stream()
                .filter(f -> !deviceHeld.contains(f.getId()))
                .map(this::tryBuildFileToAdd)
                .filter(Objects::nonNull)
                .toList();

        List<Long> filesToDelete = deviceHeld.stream()
                .filter(id -> !expectedIds.contains(id))
                .sorted()
                .toList();

        // playlistOrder must be a subset of the DELIVERABLE set, not a re-filter of raw items
        // by READY status. Deliverable = files we successfully built a URL for this round
        // (filesToAdd) ∪ files the device already holds that are still expected. A READY file
        // whose object is missing or whose presign failed was dropped from filesToAdd above, so
        // it must NOT appear in the order — otherwise the device would be told to play, at some
        // position, a file it has no download URL for. Held files stay regardless of current
        // storage state: the device already has the bytes.
        Set<Long> addedFileIds = filesToAdd.stream()
                .map(SyncFileToAdd::fileId)
                .collect(Collectors.toUnmodifiableSet());
        Set<Long> deliverableFileIds = new HashSet<>(addedFileIds);
        deviceHeld.stream().filter(expectedIds::contains).forEach(deliverableFileIds::add);

        // Emit a CONTIGUOUS 0-based `index` over the delivered list (raw `position` may be sparse
        // once undeliverable items are filtered out) and the EFFECTIVE per-item duration
        // (override ?? the file's natural duration) so a held file with no override still carries
        // a usable duration — /sync filesToAdd excludes held files, so this is its only source.
        // Also the synchronized-playback slot timeline (§1.3): a running prefix sum `slotStartMs`
        // and per-item `slotDurationMs`. The slot set is BY CONSTRUCTION identical to
        // `playlistOrder` (same deliverable-filtered iteration, same `index`), so a device never
        // holds a slot for a file it won't play, or vice versa. The slot MATH is shared with the
        // group-jump service via {@link PlaybackSlotTimeline} so a jump's slotStart[index] matches.
        List<PlaylistItem> deliverableOrdered = items.stream()
                .filter(it -> {
                    ContentFile f = it.getContentFile();
                    return f != null && deliverableFileIds.contains(f.getId());
                })
                .toList();
        PlaybackSlotTimeline.Timeline timeline = PlaybackSlotTimeline.of(deliverableOrdered);
        List<PlaylistEntry> playlistOrder = timeline.slots().stream()
                .map(s -> new PlaylistEntry(s.index(), s.position(), s.fileId(), s.effectiveSeconds(),
                        s.slotStartMs(), s.slotDurationMs()))
                .toList();
        long loopDurationMs = timeline.loopDurationMs(); // Σ slotDurationMs (0 when nothing is deliverable)

        String expectedVersion = contentVersionService.computeForAssignment(assignment);

        // Anchor the group's shared cut-over/loop T0 for this version (lazy, immutable per version).
        // An operator "group jump" re-anchors it via a MUTABLE per-sync-group override (V42) that
        // takes precedence over the base immutable per-version anchor (V40) while the group stays on
        // the same (assignment, version, contentVersion); a version change makes the override stale
        // and it is cleaned up here, then the base anchor is used.
        // Best-effort: if the anchor can't be obtained, the device simply free-runs this cycle and
        // re-anchors on its next /sync — the schedule block is additive and must never fail the sync.
        String syncGroupId = device.getSyncGroupId();
        Long anchorEpochMs = null;
        Long activateAtEpochMs = null;
        if (expectedVersion != null && !playlistOrder.isEmpty()) {
            try {
                // The NUMERIC sync-group id (nullable) — NOT device.getSyncGroupId() (the sg-/fac-
                // wire string). An override applies only to devices in an EXPLICIT sync group;
                // facility/region-grouped devices have no numeric key and use the base anchor.
                Long groupId = device.getSyncGroup() != null ? device.getSyncGroup().getId() : null;
                Optional<SyncGroupPlaybackOverride> override = groupId == null
                        ? Optional.empty()
                        : overrideRepository.findBySyncGroupId(groupId);
                if (override.isPresent() && overrideMatches(override.get(), assignment, expectedVersion)) {
                    // Operator group-jump re-anchor wins over the base per-version anchor.
                    anchorEpochMs = override.get().getAnchorEpochMs();
                    activateAtEpochMs = override.get().getActivateAtEpochMs();
                } else {
                    // Clean up a genuinely stale override ONLY when THIS device is still on the
                    // assignment the jump was written for but its content moved on (a playlist/dwell
                    // edit — contentVersion is a pure function of the assignment, so EVERY member on
                    // that assignment agrees, making the mismatch a group-wide signal). A device that
                    // has drifted to a DIFFERENT assignment (e.g. a higher-priority subset assignment)
                    // must NOT delete the shared override — that would cancel the jump for members
                    // still on the original assignment; it just uses the base anchor for itself.
                    if (override.isPresent() && override.get().getAssignmentId().equals(assignment.getId())) {
                        overrideRepository.deleteBySyncGroupId(groupId);
                    }
                    var schedule = playbackScheduleService.getOrCreate(
                            assignment.getId(), assignment.getVersionNumber(), expectedVersion);
                    if (schedule != null) {
                        anchorEpochMs = schedule.getAnchorEpochMs();
                        activateAtEpochMs = schedule.getActivateAtEpochMs();
                    }
                }
            } catch (Exception e) {
                log.warn("Playback anchor/override unavailable [device={}, assignment={}]: {} — "
                        + "device free-runs this cycle", deviceId, assignment.getId(), e.getMessage());
            }
        }

        // Mark a sync in-flight when there's work: files to add/delete OR a pure REORDER (the
        // version changed with no file delta). Arming on a reorder lets SyncTimeoutMonitor
        // escalate a dropped reorder push, and makes confirmSync validate against a STORED
        // expected version (killing a back-to-back reorder race). markSyncPending preserves
        // syncPendingSince across re-syncs so the 30-minute window anchors to the FIRST plan.
        boolean versionChanged = expectedVersion != null && !expectedVersion.equals(currentVersion);
        boolean hasWork = !filesToAdd.isEmpty() || !filesToDelete.isEmpty() || versionChanged;
        if (hasWork && expectedVersion != null) {
            device.markSyncPending(expectedVersion);
        } else if (!hasWork) {
            // The device already holds the expected state (e.g. its confirm was lost), so an
            // in-flight marker left from an earlier plan is stale — clear it before
            // SyncTimeoutMonitor escalates a sync that has in fact completed.
            clearStalePending(device);
        }

        log.debug("Sync [device={}] full={} expectedVersion={} +{} -{} order={}",
                deviceId, fullSync, expectedVersion,
                filesToAdd.size(), filesToDelete.size(), playlistOrder.size());

        return new SyncPlan(deviceId, expectedVersion, fullSync,
                filesToAdd, filesToDelete, playlistOrder,
                presignedUrlExpiryMinutes, expiresAt,
                syncGroupId, anchorEpochMs, loopDurationMs, activateAtEpochMs);
    }

    /**
     * Clear the in-flight marker only when one is set: {@code clearSyncPending()} also bumps
     * {@code updatedAt}, so calling it unconditionally would write the device row on every
     * no-op sync.
     */
    private static void clearStalePending(Device device) {
        if (device.getSyncPendingSince() != null) {
            device.clearSyncPending();
        }
    }

    /**
     * Device confirms it has finished downloading and reports its new version.
     *
     * <p>Edge case: unexpected version confirmed → log mismatch, persist what the device
     * actually has (so heartbeat/sync logic stays accurate), keep the pending state in
     * place so the 30-minute timer continues running, and tell the device to re-sync.
     *
     * <p>If the reported version matches what we expected, currentContentVersion is updated
     * and the pending state is cleared.
     */
    @Transactional
    public ConfirmResult confirmSync(Long deviceId, String reportedVersion) {
        var device = deviceRepository.findByIdAndDeletedAtIsNull(deviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Device", deviceId));

        String expected = device.getSyncPendingVersion();
        // No pending state but a confirm arrived — recompute expected so we can validate it.
        if (expected == null) {
            var assignment = assignmentService.resolveForDevice(device, Instant.now());
            expected = assignment == null ? null : contentVersionService.computeForAssignment(assignment);
        }

        device.setCurrentContentVersion(reportedVersion);

        if (expected == null) {
            log.info("Sync confirm [device={}] version={} accepted (no pending sync, no expected version)",
                    deviceId, reportedVersion);
            device.clearSyncPending();
            return new ConfirmResult(deviceId, ConfirmStatus.CONFIRMED, expected, reportedVersion, false);
        }

        if (!expected.equals(reportedVersion)) {
            // Edge case: device confirmed a version we did NOT expect. Don't clear the
            // pending marker — the 30-minute timer must keep running. Tell the device to
            // re-sync so it picks up fresh presigned URLs and the correct file set.
            log.warn("Sync confirm MISMATCH [device={}] expected={} reported={} — re-sync required",
                    deviceId, expected, reportedVersion);
            return new ConfirmResult(deviceId, ConfirmStatus.MISMATCH, expected, reportedVersion, true);
        }

        log.info("Sync confirm [device={}] version={} matches expected — sync complete",
                deviceId, reportedVersion);
        device.clearSyncPending();
        return new ConfirmResult(deviceId, ConfirmStatus.CONFIRMED, expected, reportedVersion, false);
    }

    /**
     * Shared deliverability predicate: a playlist item ships to devices iff its content file is
     * READY with a processed object key. This single definition is reused by {@link #computeSyncPlan}
     * (the order base set), {@link #getPlaylistView}, and {@code PlaylistControlService} (the JUMP
     * index range) so the three can never disagree on "which items are deliverable".
     */
    public static boolean isDeliverable(PlaylistItem it) {
        ContentFile f = it == null ? null : it.getContentFile();
        return f != null && f.getStatus() == ContentFile.Status.READY && f.getProcessedStorageKey() != null;
    }

    /**
     * True iff a group-jump override still applies to the device's current content: same assignment,
     * same version number, and same content-version hash. Any mismatch means the group has moved on
     * (a content or dwell edit yields a new {@code contentVersion}), so the override is stale.
     */
    private static boolean overrideMatches(SyncGroupPlaybackOverride o, ContentAssignment assignment,
                                           String expectedVersion) {
        return o.getAssignmentId().equals(assignment.getId())
                && o.getVersionNumber() == assignment.getVersionNumber()
                && o.getContentVersion().equals(expectedVersion);
    }

    /**
     * Two failure modes that look identical to a mock and are opposites in production:
     *
     * <ul>
     *   <li><b>This object is gone</b> ({@code exists == false}, {@code NoSuchKey},
     *       {@link ResourceNotFoundException}) — a per-file storage inconsistency. MinIO answered;
     *       it just doesn't have this key. Skipping the file is right: the other files in the
     *       playlist are fine and the device should get them.</li>
     *   <li><b>Storage is unreachable</b> ({@link StorageUnavailableException}) — every file is
     *       equally unverifiable. Skipping them all produced an HTTP <b>200</b> carrying an
     *       <em>incomplete</em> plan, which the device applies: it deletes nothing (nothing is
     *       scheduled for deletion), downloads nothing, and plays a shortened loop that looks
     *       exactly like a correct answer. A 503 is the truthful answer — the device's documented
     *       behaviour for it is "retry later", which is precisely what should happen.</li>
     * </ul>
     *
     * So {@code StorageUnavailableException} is re-thrown and nothing else is. It propagates out of
     * {@link #computeSyncPlan} <em>before</em> {@code filesToDelete} is computed, so an outage can
     * never produce a delete instruction either. {@link #getPlaylistView} shares this method and so
     * 503s during an outage for the same reason — a truncated playlist is not a playlist.
     *
     * <p>The presign call cannot fail for outage reasons any more (signing is local crypto — see
     * {@code MinioStorageClient.generatePresignedUrl}); the re-throw is there so the rule reads the
     * same at both call sites and cannot rot if that ever changes.
     */
    private SyncFileToAdd tryBuildFileToAdd(ContentFile f) {
        String key = f.getProcessedStorageKey();
        try {
            if (!fileStorageService.processedObjectExists(key)) {
                log.warn("Storage inconsistency: content_file id={} (key={}) is READY in DB but missing from MinIO — excluding from sync",
                        f.getId(), key);
                return null;
            }
        } catch (StorageUnavailableException e) {
            throw e;
        } catch (ResourceNotFoundException e) {
            log.warn("Storage inconsistency: content_file id={} (key={}) is READY in DB but missing from MinIO — excluding from sync",
                    f.getId(), key);
            return null;
        } catch (Exception e) {
            log.warn("Storage existence check failed for content_file id={} (key={}): {} — excluding from sync",
                    f.getId(), key, e.getMessage());
            return null;
        }

        try {
            String url = fileStorageService.presignedProcessedUrl(key, presignedUrlExpiryMinutes);
            return new SyncFileToAdd(f.getId(), f.getName(), f.getContentType(), f.getSizeBytes(),
                    f.getDurationSeconds(), f.getChecksum(), url);
        } catch (StorageUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Presigned URL generation failed for content_file id={} (key={}): {} — excluding from sync",
                    f.getId(), key, e.getMessage());
            return null;
        }
    }

    /**
     * Read-only view of the device's currently-assigned playlist with file URLs and durations.
     *
     * <p>Edge case: device has no resolved assignment → returns a {@link PlaylistView} with
     * a null {@code playlistId} and an empty {@code items} array. Never throws 404 for "no
     * playlist" — only for an unknown device.
     *
     * <p>Edge case: playlist updates mid-playback → the response carries
     * {@code contentVersion}; devices compare against their currently-playing version and
     * apply the new playlist at the next item boundary. The server takes no special action;
     * because this endpoint always serves the latest server-side state, devices that
     * re-poll naturally pick up changes without disrupting the in-progress item.
     */
    @Transactional(readOnly = true)
    public PlaylistView getPlaylistView(Long deviceId) {
        var device = deviceRepository.findByIdAndDeletedAtIsNull(deviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Device", deviceId));

        ContentAssignment assignment = assignmentService.resolveForDevice(device, Instant.now());
        if (assignment == null || assignment.getPlaylist() == null) {
            return new PlaylistView(deviceId, null, null, null, 0, List.of());
        }

        var playlist = assignment.getPlaylist();
        var items = playlistItemRepository.findByPlaylistIdOrderByPositionAsc(playlist.getId());

        var entries = new java.util.ArrayList<PlaylistViewItem>();
        int totalDurationSeconds = 0;
        int index = 0;
        for (PlaylistItem it : items) {
            // Same deliverable base set as /sync (shared predicate). /playlist is a stateless
            // freshness snapshot: it re-presigns every item, so a transient signing/storage
            // failure drops the item here. (Unlike /sync it has no notion of "held" bytes, so a
            // held file the server can't currently serve is shown only by /sync — the documented
            // policy divergence.) Order + contiguous index derive from the shared base set, so
            // they never drift from /sync for the common (non-held) case.
            if (!isDeliverable(it)) {
                continue;
            }
            ContentFile f = it.getContentFile();
            SyncFileToAdd addable = tryBuildFileToAdd(f);
            if (addable == null) continue;

            Integer duration = it.getDurationSeconds() != null ? it.getDurationSeconds() : f.getDurationSeconds();
            entries.add(new PlaylistViewItem(
                    index++,
                    it.getPosition(),
                    f.getId(),
                    f.getName(),
                    f.getContentType(),
                    addable.presignedUrl(),
                    duration,
                    f.getChecksum(),
                    f.getSizeBytes()));
            if (duration != null) {
                totalDurationSeconds += duration;
            }
        }

        String version = contentVersionService.computeForAssignment(assignment);
        return new PlaylistView(deviceId, playlist.getId(), playlist.getName(), version,
                totalDurationSeconds, entries);
    }

    /** {@code index} = contiguous 0-based ordinal over the delivered list; {@code position} =
     *  raw playlist slot (may be sparse). See {@link PlaylistEntry}. */
    public record PlaylistViewItem(int index, int position, Long fileId, String name, String contentType,
                                    String presignedUrl, Integer durationSeconds,
                                    String checksum, long sizeBytes) {}

    public record PlaylistView(Long deviceId, Long playlistId, String playlistName,
                                String contentVersion, int totalDurationSeconds,
                                List<PlaylistViewItem> items) {}

    public enum ConfirmStatus { CONFIRMED, MISMATCH }

    public record ConfirmResult(Long deviceId, ConfirmStatus status, String expectedVersion,
                                 String reportedVersion, boolean syncRequired) {}

    public record SyncFileToAdd(Long fileId, String name, String contentType, long sizeBytes,
                                 Integer durationSeconds, String checksum, String presignedUrl) {}

    /**
     * One entry of the device's canonical play order. {@code index} is a contiguous 0-based
     * ordinal over the DELIVERED list (the device addresses positionally by this — JUMP/PREV/NEXT).
     * {@code position} is the raw playlist slot (kept for reference) and MAY be sparse once
     * undeliverable items are filtered out. {@code durationSeconds} is the EFFECTIVE duration
     * (per-item override else the file's natural duration).
     *
     * <p>{@code slotStartMs}/{@code slotDurationMs} are the synchronized-playback slot timeline
     * (§1.3): the loop-relative start offset (prefix sum) and length in ms. {@code slotDurationMs} is
     * always positive — a null/zero effective duration falls back to a default dwell (see
     * {@link PlaybackSlotTimeline}) so the loop can never collapse.
     */
    public record PlaylistEntry(int index, int position, Long fileId, Integer durationSeconds,
                                long slotStartMs, long slotDurationMs) {}

    public record SyncPlan(Long deviceId,
                            String expectedContentVersion,
                            boolean fullSync,
                            List<SyncFileToAdd> filesToAdd,
                            List<Long> filesToDelete,
                            List<PlaylistEntry> playlistOrder,
                            int presignedUrlExpiryMinutes,
                            Instant presignedUrlsExpireAt,
                            // --- synchronized-playback time layer (§1.3); all nullable/0 for solo ---
                            String syncGroupId,   // facility ?? group ?? region; null ⇒ free-run solo
                            Long anchorEpochMs,   // loop T0 (== activateAt); null until anchored
                            long loopDurationMs,  // Σ slotDurationMs; 0 when nothing is deliverable
                            Long activateAt) {}   // coordinated cut-over epoch ms; null until anchored
}
