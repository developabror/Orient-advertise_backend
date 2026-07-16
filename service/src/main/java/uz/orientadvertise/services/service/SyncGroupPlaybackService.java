package uz.orientadvertise.services.service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.content.SyncDispatcher;
import uz.orientadvertise.services.domain.model.ContentAssignment;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.SyncGroup;
import uz.orientadvertise.services.domain.model.SyncGroupPlaybackOverride;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.PlaylistItemRepository;
import uz.orientadvertise.services.domain.repository.SyncGroupPlaybackOverrideRepository;
import uz.orientadvertise.services.domain.repository.SyncGroupRepository;

/**
 * Operator-facing synchronized-playback control for a sync group: <b>view</b> the pickable item list
 * (for the FE jump picker) and <b>jump</b> every member device to a chosen playlist index in lockstep.
 *
 * <p>A jump is delivered as a <b>re-anchored schedule</b>, not a transport command: it upserts a
 * MUTABLE per-group {@link SyncGroupPlaybackOverride} (V42) with
 * {@code anchorEpochMs = activateAt − slotStart[index]}, so at {@code activateAt} every member's
 * {@code floorMod(now − anchor, loop)} resolves to {@code index} and they stay frame-aligned. The
 * override overrides the immutable base per-version anchor ({@link PlaybackSyncSchedule}) only while
 * the group stays on the exact {@code (assignment, version, contentVersion)} it was jumped on; a
 * content/version change makes it stale and {@code /sync} falls back to the base anchor
 * ({@link DeviceSyncService#computeSyncPlan}). Delivery reuses the existing {@code SYNC_CONTENT} push →
 * {@code /sync} path, so offline members converge on their next heartbeat.
 *
 * <p>The pickable timeline is built from the shared assignment's {@link DeviceSyncService#isDeliverable
 * deliverable} order via {@link PlaybackSlotTimeline}, the same slot math {@code /sync} uses. The two
 * timelines agree exactly whenever every deliverable file is URL-buildable (the common case); a
 * per-device storage inconsistency is the only edge where a member's live position could differ.
 *
 * <p>Scope + coherence: out-of-operator-scope collapses to 404 (mirrors
 * {@link SyncGroupManagementService#getDetail}); a jump requires the group to be non-empty (else 409)
 * and content-coherent — all members must resolve one shared {@code (assignmentId, versionNumber)}
 * (else 409). An out-of-range index is 400.
 */
@Service
public class SyncGroupPlaybackService {

    private static final Logger log = LoggerFactory.getLogger(SyncGroupPlaybackService.class);

    private final SyncGroupRepository groupRepository;
    private final DeviceRepository deviceRepository;
    private final ContentAssignmentService assignmentService;
    private final ContentVersionService contentVersionService;
    private final PlaylistItemRepository playlistItemRepository;
    private final SyncGroupPlaybackOverrideRepository overrideRepository;
    private final SyncDispatcher syncDispatcher;
    private final OperatorScopeResolver operatorScopeResolver;
    private final Duration jumpLead;

    public SyncGroupPlaybackService(SyncGroupRepository groupRepository,
                                    DeviceRepository deviceRepository,
                                    ContentAssignmentService assignmentService,
                                    ContentVersionService contentVersionService,
                                    PlaylistItemRepository playlistItemRepository,
                                    SyncGroupPlaybackOverrideRepository overrideRepository,
                                    SyncDispatcher syncDispatcher,
                                    OperatorScopeResolver operatorScopeResolver,
                                    @Value("${app.sync.jump-min-lead:PT5S}") Duration jumpLead) {
        this.groupRepository = groupRepository;
        this.deviceRepository = deviceRepository;
        this.assignmentService = assignmentService;
        this.contentVersionService = contentVersionService;
        this.playlistItemRepository = playlistItemRepository;
        this.overrideRepository = overrideRepository;
        this.syncDispatcher = syncDispatcher;
        this.operatorScopeResolver = operatorScopeResolver;
        this.jumpLead = jumpLead;
    }

    // ----- view records (mapped to api.dto by the controller) -----

    /** One pickable item on the shared timeline. */
    public record PlaybackItemView(int index, Long fileId, String title, Integer durationSeconds,
                                   long slotStartMs, long slotDurationMs) {}

    /** The currently-active jump for the group (the override row still matching the resolved version). */
    public record ActiveJumpView(int index, long activateAtEpochMs) {}

    /** Result of {@link #getPlaybackView}. When {@code coherent} is false, {@code items} is empty. */
    public record PlaybackView(Long syncGroupId, boolean coherent, String reason,
                               Long playlistId, String playlistName, long loopDurationMs,
                               int memberCount, List<PlaybackItemView> items, ActiveJumpView activeJump) {}

    /** Result of {@link #jumpToIndex}. */
    public record JumpResultView(Long syncGroupId, int index, long anchorEpochMs, long activateAtEpochMs,
                                 int memberCount, int sent, int skipped, int failed) {}

    @Transactional(readOnly = true)
    public PlaybackView getPlaybackView(Long syncGroupId) {
        loadAndGuard(syncGroupId);
        List<Device> members = deviceRepository.findBySyncGroupIdAndDeletedAtIsNull(syncGroupId);
        Coherence c = resolveCoherence(members, Instant.now());
        if (!c.coherent()) {
            return new PlaybackView(syncGroupId, false, c.reason(), null, null, 0L,
                    members.size(), List.of(), null);
        }
        ContentAssignment assignment = c.assignment();
        var playlist = assignment.getPlaylist();
        PlaybackSlotTimeline.Timeline timeline = buildTimeline(assignment);
        List<PlaybackItemView> items = timeline.slots().stream()
                .map(s -> new PlaybackItemView(s.index(), s.fileId(), s.title(),
                        s.effectiveSeconds(), s.slotStartMs(), s.slotDurationMs()))
                .toList();
        String contentVersion = contentVersionService.computeForAssignment(assignment);
        ActiveJumpView activeJump = overrideRepository.findBySyncGroupId(syncGroupId)
                .filter(o -> overrideMatches(o, assignment, contentVersion))
                .map(o -> new ActiveJumpView(o.getChosenIndex(), o.getActivateAtEpochMs()))
                .orElse(null);
        return new PlaybackView(syncGroupId, true, null, playlist.getId(), playlist.getName(),
                timeline.loopDurationMs(), members.size(), items, activeJump);
    }

    @Transactional
    public JumpResultView jumpToIndex(Long syncGroupId, int index, String issuedBy) {
        loadAndGuard(syncGroupId);
        List<Device> members = deviceRepository.findBySyncGroupIdAndDeletedAtIsNull(syncGroupId);
        if (members.isEmpty()) {
            throw new IllegalStateException("sync group has no member devices");
        }
        Instant now = Instant.now();
        Coherence c = resolveCoherence(members, now);
        if (!c.coherent()) {
            throw new IllegalStateException(
                    "sync group is not content-coherent; members must share one playlist/version to jump as a unit");
        }
        ContentAssignment assignment = c.assignment();
        String contentVersion = contentVersionService.computeForAssignment(assignment);
        PlaybackSlotTimeline.Timeline timeline = buildTimeline(assignment);
        int deliverableCount = timeline.slots().size();
        if (index < 0 || index >= deliverableCount) {
            throw new IllegalArgumentException(
                    "jump index " + index + " is out of range [0, " + deliverableCount + ")");
        }

        Instant activateAt = now.plus(jumpLead);
        long slotStartMs = timeline.slots().get(index).slotStartMs();
        long anchorEpochMs = activateAt.toEpochMilli() - slotStartMs;

        // Upsert the single per-group override: a re-jump overwrites it in place; the base V40 anchor
        // stays untouched. No duplicate-pending semantics — this is not a per-device RemoteAction.
        SyncGroupPlaybackOverride existing = overrideRepository.findBySyncGroupId(syncGroupId).orElse(null);
        if (existing == null) {
            SyncGroupPlaybackOverride fresh = new SyncGroupPlaybackOverride(syncGroupId, assignment.getId(),
                    assignment.getVersionNumber(), contentVersion, index, anchorEpochMs, activateAt);
            try {
                overrideRepository.save(fresh);
            } catch (DataIntegrityViolationException race) {
                // Lost the first-jump race on UNIQUE(sync_group_id) to a concurrent operator/double-
                // click. Surface a clean, retryable 409 instead of a 500 — the retry finds the now-
                // committed row and overwrites it via the update path above.
                throw new IllegalStateException(
                        "a concurrent jump is being applied to this sync group; please retry");
            }
        } else {
            existing.applyJump(assignment.getId(), assignment.getVersionNumber(), contentVersion,
                    index, anchorEpochMs, activateAt);
            overrideRepository.save(existing);
        }

        List<Long> memberIds = members.stream().map(Device::getId).toList();
        SyncDispatcher.DispatchResult dispatch = syncDispatcher.dispatchSyncToDevices(memberIds, "sync-group-jump");
        log.info("Sync group jump [group={} index={} members={} activateAt={} by={}]",
                syncGroupId, index, members.size(), activateAt, issuedBy);
        return new JumpResultView(syncGroupId, index, anchorEpochMs, activateAt.toEpochMilli(),
                members.size(), dispatch.sent(), dispatch.skipped(), dispatch.failed());
    }

    // ----- helpers -----

    private SyncGroup loadAndGuard(Long syncGroupId) {
        // findByIdWithProject fetches the project so the scope guard (and the DTO after the tx) can
        // read project.getId()/getName() under open-in-view:false. Out-of-scope collapses to the
        // SAME 404 as a missing id — no existence oracle (mirrors SyncGroupManagementService.getDetail).
        SyncGroup group = groupRepository.findByIdWithProject(syncGroupId)
                .orElseThrow(() -> new ResourceNotFoundException("SyncGroup", syncGroupId));
        if (operatorScopeResolver.resolve().excludes(group.getProject().getId())) {
            throw new ResourceNotFoundException("SyncGroup", syncGroupId);
        }
        return group;
    }

    /**
     * Resolve the group's content coherence: every member must resolve one shared
     * {@code (assignmentId, versionNumber)} with a non-null playlist. An empty group, any member with
     * no active playlist, or members split across content are all incoherent (with a reason).
     */
    private Coherence resolveCoherence(List<Device> members, Instant now) {
        if (members.isEmpty()) {
            return Coherence.incoherent("sync group has no member devices");
        }
        int noPlaylist = 0;
        ContentAssignment shared = null;
        Set<String> keys = new LinkedHashSet<>();
        for (Device d : members) {
            ContentAssignment a = assignmentService.resolveForDevice(d, now);
            if (a == null || a.getPlaylist() == null) {
                noPlaylist++;
                continue;
            }
            keys.add(a.getId() + ":" + a.getVersionNumber());
            if (shared == null) {
                shared = a;
            }
        }
        if (noPlaylist > 0) {
            return Coherence.incoherent(noPlaylist + " member(s) have no active playlist");
        }
        if (keys.size() != 1) {
            return Coherence.incoherent("members resolve different content");
        }
        return Coherence.coherent(shared);
    }

    private PlaybackSlotTimeline.Timeline buildTimeline(ContentAssignment assignment) {
        var items = playlistItemRepository.findByPlaylistIdOrderByPositionAsc(assignment.getPlaylist().getId());
        var deliverable = items.stream().filter(DeviceSyncService::isDeliverable).toList();
        return PlaybackSlotTimeline.of(deliverable);
    }

    private static boolean overrideMatches(SyncGroupPlaybackOverride o, ContentAssignment assignment,
                                           String contentVersion) {
        return o.getAssignmentId().equals(assignment.getId())
                && o.getVersionNumber() == assignment.getVersionNumber()
                && o.getContentVersion().equals(contentVersion);
    }

    private record Coherence(boolean coherent, String reason, ContentAssignment assignment) {
        static Coherence coherent(ContentAssignment a) {
            return new Coherence(true, null, a);
        }

        static Coherence incoherent(String reason) {
            return new Coherence(false, reason, null);
        }
    }
}
