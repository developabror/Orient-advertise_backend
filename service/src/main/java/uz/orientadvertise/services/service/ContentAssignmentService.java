package uz.orientadvertise.services.service;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.model.ContentAssignment;
import uz.orientadvertise.services.domain.model.ContentAssignment.Status;
import uz.orientadvertise.services.domain.model.ContentAssignment.TargetType;
import uz.orientadvertise.services.domain.model.ContentAssignmentExclusion;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.DeviceStatusEvaluator;
import uz.orientadvertise.services.domain.model.Playlist;
import uz.orientadvertise.services.domain.repository.ContentAssignmentExclusionRepository;
import uz.orientadvertise.services.domain.repository.ContentAssignmentRepository;
import uz.orientadvertise.services.domain.repository.DeviceGroupRepository;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.FacilityRepository;
import uz.orientadvertise.services.domain.repository.PlaylistItemRepository;
import uz.orientadvertise.services.domain.repository.RegionRepository;
import uz.orientadvertise.services.service.exception.AssignmentTimeOverlapException;

@Service
public class ContentAssignmentService {

    private static final Logger log = LoggerFactory.getLogger(ContentAssignmentService.class);

    private final ContentAssignmentRepository assignmentRepository;
    private final ContentAssignmentExclusionRepository exclusionRepository;
    private final DeviceRepository deviceRepository;
    private final PlaylistItemRepository playlistItemRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final RegionRepository regionRepository;
    private final FacilityRepository facilityRepository;
    private final DeviceGroupRepository deviceGroupRepository;
    private final OperatorScopeResolver operatorScopeResolver;

    /**
     * Audit reason stamped on the {@link ContentAssignmentExclusion} rows a partial-device
     * supersede writes, so a narrowed predecessor's history is greppable. Formats to well under
     * the column's 500 chars.
     *
     * <p>It is also the <b>key</b> that {@link #softDelete} uses to undo a narrowing: cancelling
     * the superseding assignment deletes exactly the exclusions it caused. Always build it through
     * {@link #partialSupersedeReason} so the write and the undo cannot drift — no second quoted
     * copy of this string exists anywhere.
     */
    private static final String PARTIAL_SUPERSEDE_REASON = "superseded for these devices by assignment %d";

    /** The one formatter for {@link #PARTIAL_SUPERSEDE_REASON} — written by supersede, matched by cancel. */
    private static String partialSupersedeReason(Long supersedingAssignmentId) {
        return PARTIAL_SUPERSEDE_REASON.formatted(supersedingAssignmentId);
    }

    @Value("${app.sync.push-on-confirm-cap:5000}")
    private int pushOnConfirmCap;

    public ContentAssignmentService(ContentAssignmentRepository assignmentRepository,
                                     ContentAssignmentExclusionRepository exclusionRepository,
                                     DeviceRepository deviceRepository,
                                     PlaylistItemRepository playlistItemRepository,
                                     ApplicationEventPublisher eventPublisher,
                                     RegionRepository regionRepository,
                                     FacilityRepository facilityRepository,
                                     DeviceGroupRepository deviceGroupRepository,
                                     OperatorScopeResolver operatorScopeResolver) {
        this.assignmentRepository = assignmentRepository;
        this.exclusionRepository = exclusionRepository;
        this.deviceRepository = deviceRepository;
        this.playlistItemRepository = playlistItemRepository;
        this.eventPublisher = eventPublisher;
        this.regionRepository = regionRepository;
        this.facilityRepository = facilityRepository;
        this.deviceGroupRepository = deviceGroupRepository;
        this.operatorScopeResolver = operatorScopeResolver;
    }

    /**
     * Operator scope guard for assignment ops: both the playlist's project AND the target's
     * project (region / facility / device-group → project) must be in scope, else 404.
     */
    private void assertAssignmentInScope(Playlist playlist, TargetType targetType, Long targetId) {
        var scope = operatorScopeResolver.resolve();
        if (!scope.restricted()) {
            return;
        }
        if (scope.excludes(playlist.getProject().getId())) {
            throw new ResourceNotFoundException("ContentAssignment", playlist.getId());
        }
        Long targetProjectId = targetProjectId(targetType, targetId);
        if (targetProjectId != null && scope.excludes(targetProjectId)) {
            throw new ResourceNotFoundException("ContentAssignment", targetId);
        }
    }

    private void assertAssignmentInScope(ContentAssignment assignment) {
        assertAssignmentInScope(assignment.getPlaylist(), assignment.getTargetType(), assignment.getTargetId());
    }

    private Long targetProjectId(TargetType targetType, Long targetId) {
        return switch (targetType) {
            case REGION -> regionRepository.findById(targetId)
                    .map(r -> r.getProject().getId()).orElse(null);
            case FACILITY -> facilityRepository.findById(targetId)
                    .map(f -> f.getRegion().getProject().getId()).orElse(null);
            case DEVICE_GROUP -> deviceGroupRepository.findById(targetId)
                    .map(g -> g.getProject().getId()).orElse(null);
        };
    }

    @Transactional
    public ContentAssignment createAssignment(Playlist playlist, TargetType targetType, Long targetId,
                                               Instant startTime, Instant endTime) {
        rejectTimeOverlap(targetType, targetId, startTime, endTime, null);

        var assignment = new ContentAssignment(playlist, targetType, targetId, startTime, endTime);
        return assignmentRepository.save(assignment);
    }

    /**
     * Create a DRAFT assignment. Drafts are not active for resolution (both resolution and the
     * overlap check filter {@code status='CONFIRMED'}) and auto-expire after 1h via
     * {@code DraftAssignmentCleaner}, so overlapping drafts are harmless.
     *
     * <p>No overlap check runs here on purpose: the draft request carries no device list (devices
     * are chosen at confirm), so a region-level time overlap cannot yet be disambiguated by device
     * scope. The authoritative, device-aware overlap gate lives at {@link #confirmWithExclusions}.
     */
    @Transactional
    public ContentAssignment createDraft(Playlist playlist, TargetType targetType, Long targetId,
                                          Instant startTime, Instant endTime) {
        assertAssignmentInScope(playlist, targetType, targetId);
        requireNonEmptyPlaylist(playlist.getId());

        var assignment = new ContentAssignment(playlist, targetType, targetId, startTime, endTime,
                Status.DRAFT);
        var saved = assignmentRepository.save(assignment);
        log.info("Created DRAFT assignment [id={}, target={}:{}]", saved.getId(), targetType, targetId);
        return saved;
    }

    /** Back-compat overload — confirm without replacing any overlapping assignment. */
    @Transactional
    public ContentAssignment confirmWithExclusions(Long assignmentId, java.util.Collection<Long> excludedDeviceIds,
                                                    String exclusionReason) {
        return confirmWithExclusions(assignmentId, excludedDeviceIds, exclusionReason, false);
    }

    /**
     * Atomically confirm a draft assignment AND attach the device exclusions.
     * Either everything commits, or nothing does — this is the all-or-nothing edge case.
     *
     * <p>When {@code replaceConflicting} is true, any overlapping CONFIRMED assignment on the same
     * target is {@link #supersede superseded} in this SAME transaction before the new one goes
     * CONFIRMED — so the target is never left empty. A predecessor the new assignment covers
     * <b>entirely</b> (all its devices AND the rest of its window) is retired in full; one that only
     * partly overlaps in <b>devices</b> is narrowed, keeping its remaining devices playing; one the
     * new window does not outlast is left untouched and simply outranked for the new window. With
     * the flag false, an overlap throws {@link AssignmentTimeOverlapException} (the FE then offers
     * "Replace").
     *
     * <p>Devices whose resolved playlist actually changes here also lose their {@code syncGroup}
     * membership — see {@link #detachReassignedSyncGroupMembers}.
     */
    @Transactional
    public ContentAssignment confirmWithExclusions(Long assignmentId, java.util.Collection<Long> excludedDeviceIds,
                                                    String exclusionReason, boolean replaceConflicting) {
        var assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("ContentAssignment", assignmentId));

        // A soft-deleted draft is logically gone (e.g. auto-cleaned by DraftAssignmentCleaner
        // after its 1h TTL). findById does NOT filter deletedAt, so confirming it here would
        // produce a CONFIRMED-but-deletedAt row that findActiveAtTime/resolveForDevice never
        // see — a silent delivery drop. Treat it as not-found.
        if (assignment.isDeleted()) {
            throw new ResourceNotFoundException("ContentAssignment", assignmentId);
        }
        assertAssignmentInScope(assignment);

        if (!assignment.isDraft()) {
            throw new IllegalStateException(
                    "Cannot confirm assignment %d — current status is %s, expected DRAFT".formatted(
                            assignmentId, assignment.getStatus()));
        }

        // Never confirm an assignment whose playlist has no items — the device would resolve
        // a non-null playlist with an empty playlistOrder and play nothing. Checked BEFORE the
        // overlap gate so a doomed confirm fails fast without the (replace-destructive) supersede.
        requireNonEmptyPlaylist(assignment.getPlaylist().getId());

        // The target's devices, loaded ONCE and threaded through everything below (overlap gate,
        // partial supersede, sync-group reset, push payload) — the entities, not just ids, so the
        // narrowing and the detach can mutate them without a second round trip. UNCAPPED on
        // purpose: pushOnConfirmCap is a push budget, not a correctness bound, and it is applied
        // only where it belongs — the event payload at the bottom of this method.
        var targetDevices = listDevicesForTarget(assignment.getTargetType(), assignment.getTargetId());
        var excludedIds = nonNullIdSet(excludedDeviceIds);
        var effectiveDevices = targetDevices.stream()
                .filter(device -> !excludedIds.contains(device.getId()))
                .toList();

        // Snapshot the sales-point members' currently-resolved assignment BEFORE the overlap gate
        // mutates anything — detachReassignedSyncGroupMembers compares against it after the flip.
        // Filtering on getSyncGroup() != null first keeps this to a handful of devices in practice.
        var now = Instant.now();
        var syncGroupMembers = effectiveDevices.stream()
                .filter(device -> device.getSyncGroup() != null)
                .toList();
        var resolvedBefore = new java.util.HashMap<Long, ContentAssignment>();
        for (var device : syncGroupMembers) {
            resolvedBefore.put(device.getId(), resolveForDevice(device, now));
        }

        // Device-aware overlap gate. The new assignment's effective device set is the target's
        // devices MINUS the excludedDeviceIds param (NOT the DB — exclusions are persisted below,
        // after this check). A candidate only conflicts if its effective set actually intersects.
        resolveConfirmOverlap(assignment, assignmentId, excludedIds, replaceConflicting, targetDevices);

        // Resolve and attach exclusions
        if (excludedDeviceIds != null && !excludedDeviceIds.isEmpty()) {
            var devices = deviceRepository.findAllById(excludedDeviceIds);
            if (devices.size() != excludedDeviceIds.size()) {
                var foundIds = devices.stream().map(Device::getId).collect(Collectors.toSet());
                var missing = excludedDeviceIds.stream().filter(id -> !foundIds.contains(id)).toList();
                throw new ResourceNotFoundException("Device", missing);
            }
            for (var device : devices) {
                exclusionRepository.save(new ContentAssignmentExclusion(assignment, device, exclusionReason));
            }
        }

        // Status flip happens last so the whole batch (status + exclusions) commits together
        assignment.confirm();
        log.info("Confirmed assignment [id={}], excluded devices: {}",
                assignmentId, excludedDeviceIds == null ? 0 : excludedDeviceIds.size());

        // A reassigned sales-point member has left its group's content — drop its membership.
        detachReassignedSyncGroupMembers(assignment, syncGroupMembers, resolvedBefore, now);

        // Publish a post-confirm event so currently-online in-scope devices get an
        // instant SYNC push. Subscribers fire on AFTER_COMMIT — if the surrounding
        // transaction rolls back (e.g. the overlap re-check above), nothing is pushed.
        // Offline devices in the same scope still pick up content on next heartbeat
        // poll via DeviceSyncService.computeSyncPlan, so the push is purely an
        // online-fast-path; we never depend on it for correctness.
        var targetDeviceIds = capPushAudience(
                effectiveDevices.stream().map(Device::getId).toList(), assignmentId);
        eventPublisher.publishEvent(new AssignmentConfirmedEvent(assignmentId, targetDeviceIds));

        return assignment;
    }

    /**
     * Clear {@code device.syncGroup} for every sales-point member whose resolved playlist actually
     * CHANGES because of this confirm. A sync group is a sales point: its members are meant to play
     * the same content, frame-aligned, and {@code SyncGroupPlaybackService.resolveCoherence} refuses
     * to drive a group whose members resolve different content — so one silently-reassigned member
     * disables group control (and {@code /sync-groups/{id}/jump}) for the whole sales point.
     *
     * <p>The rule is deliberately NOT "clear the whole effective set". A device shadowed by a
     * MORE SPECIFIC booking (a DEVICE_GROUP assignment while this one targets the REGION) is inside
     * the effective set, yet {@link #resolveForDevice} keeps returning the shadowing assignment —
     * its playlist does not change, and clearing it would break a working sales point for nothing.
     *
     * <p>Otherwise {@code before.priority <= new.priority}. Equal priority means the same target
     * TYPE, and a device belongs to exactly one region / facility / device-group, so it also means
     * the same target ID — a same-target predecessor, which the overlap gate has just rejected or
     * superseded. Either way the new assignment wins from here: it is the most recently CONFIRMED
     * row, which is the second term of {@link ContentAssignment#PRECEDENCE}, so it outranks the
     * predecessor even in the v1.0.142 case where the predecessor's row survives untouched. So
     * comparing playlists decides it: a different playlist (or no previous content at all) is a
     * real reassignment.
     *
     * <p>Mutates through the entities (dirty-checked) rather than
     * {@code DeviceRepository.bulkClearSyncGroup}: that query carries
     * {@code clearAutomatically = true}, which would detach the just-confirmed assignment and the
     * exclusion rows written moments earlier from the persistence context.
     *
     * <p><b>Not cleared, deliberately:</b> devices excluded from the new assignment (never touched),
     * the {@code remainder} devices of a narrowed predecessor (their playlist did not change), and
     * cancelling an assignment ({@link #softDelete} — a cancel is often followed by re-assigning the
     * same playlist, and keeping membership lets the group heal itself).
     *
     * <p><b>A future-dated assignment detaches nothing.</b> {@code resolvedBefore} is sampled at
     * {@code now}, so for a campaign that opens next week the comparison answers a question about a
     * change that has not happened: the members keep playing the group's content until the window
     * opens, and would revert to it when the window closes. Detaching is irreversible (the
     * membership is not restored on cancel), so a confirm that changes nothing today must not break
     * a working sales point today. Devices flip to the campaign at its start edge under
     * {@link ContentAssignment#PRECEDENCE}; if that leaves the group incoherent,
     * {@code SyncGroupPlaybackService.resolveCoherence} refuses to drive it and says why — a
     * recoverable state, unlike a silently cleared membership.
     */
    private void detachReassignedSyncGroupMembers(ContentAssignment newAssignment, List<Device> members,
                                                   java.util.Map<Long, ContentAssignment> resolvedBefore,
                                                   Instant now) {
        if (newAssignment.getStartTime().isAfter(now)) {
            log.debug("Assignment {} starts at {} — no sync-group detach: no member's resolved "
                    + "playlist changes today", newAssignment.getId(), newAssignment.getStartTime());
            return;
        }
        int detached = 0;
        for (var device : members) {
            var before = resolvedBefore.get(device.getId());
            boolean shadowed = before != null && before.getPriority() > newAssignment.getPriority();
            boolean unchanged = before != null && resolvesSamePlaylist(before, newAssignment);
            if (shadowed || unchanged) {
                continue;
            }
            device.setSyncGroup(null);
            detached++;
        }
        if (detached > 0) {
            log.info("Cleared sync-group membership for {} reassigned device(s) on assignment {} "
                    + "(their resolved playlist changed)", detached, newAssignment.getId());
        }
    }

    /** True when both assignments carry the same (persisted) playlist. */
    private static boolean resolvesSamePlaylist(ContentAssignment a, ContentAssignment b) {
        var pa = a.getPlaylist();
        var pb = b.getPlaylist();
        return pa != null && pb != null && pa.getId() != null && pa.getId().equals(pb.getId());
    }

    /**
     * Null-tolerant id set. A malformed element in a client-supplied exclusion list (e.g. JSON
     * {@code [null]}) is dropped rather than blowing up on {@code Set.copyOf} — a null device id
     * can never match a real target id anyway.
     */
    private static Set<Long> nonNullIdSet(java.util.Collection<Long> ids) {
        return ids == null
                ? Set.of()
                : ids.stream().filter(java.util.Objects::nonNull).collect(Collectors.toSet());
    }

    /**
     * Confirm a draft, deriving exclusions from an inclusion list. Translates
     * {@code includedDeviceIds} to the complement set within the assignment's
     * target scope and delegates to {@link #confirmWithExclusions}.
     *
     * <p>Useful when the operator wants to apply an assignment to a small subset of
     * a large target (e.g. 10 specific devices in a 500-device region) — enumerating
     * the 490 exclusions client-side is impractical, and the preview cap of 200 means
     * the FE cannot even fetch the full device list to enumerate.
     */
    /** Back-compat overload — confirm-by-inclusion without replacing any overlapping assignment. */
    @Transactional
    public ContentAssignment confirmWithIncludedDevices(Long assignmentId,
                                                         java.util.Collection<Long> includedDeviceIds,
                                                         String reason) {
        return confirmWithIncludedDevices(assignmentId, includedDeviceIds, reason, false);
    }

    @Transactional
    public ContentAssignment confirmWithIncludedDevices(Long assignmentId,
                                                         java.util.Collection<Long> includedDeviceIds,
                                                         String reason, boolean replaceConflicting) {
        var assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("ContentAssignment", assignmentId));
        assertAssignmentInScope(assignment);

        var allTargetIds = new java.util.LinkedHashSet<>(listDeviceIdsForTarget(
                assignment.getTargetType(), assignment.getTargetId()));
        var included = includedDeviceIds == null
                ? java.util.Set.<Long>of()
                : Set.copyOf(includedDeviceIds);

        var outOfScope = included.stream()
                .filter(id -> !allTargetIds.contains(id))
                .toList();
        if (!outOfScope.isEmpty()) {
            throw new IllegalArgumentException(
                    "includedDeviceIds contains ids outside the assignment target %s:%d: %s".formatted(
                            assignment.getTargetType(), assignment.getTargetId(), outOfScope));
        }

        var derivedExclusions = allTargetIds.stream()
                .filter(id -> !included.contains(id))
                .toList();
        return confirmWithExclusions(assignmentId, derivedExclusions, reason, replaceConflicting);
    }

    /**
     * Enumerate all device ids in the assignment's target scope and subtract the
     * excluded set, applying a soft cap so very large targets do not load
     * hundreds of thousands of ids into memory.
     *
     * <p>When the cap kicks in, the un-pushed remainder is unharmed — those devices
     * will pick up the assignment on their next heartbeat poll, the same fallback
     * that covers offline / WS-disconnected devices.
     */
    private List<Long> resolveTargetDeviceIds(ContentAssignment assignment,
                                               Set<Long> excludedIds,
                                               Long assignmentId) {
        var allIds = listDeviceIdsForTarget(assignment.getTargetType(), assignment.getTargetId());
        return capPushAudience(allIds.stream().filter(id -> !excludedIds.contains(id)).toList(),
                assignmentId);
    }

    /**
     * Apply the push-on-confirm soft cap to an already-resolved effective device list. This is a
     * PUSH budget only — never a correctness bound: callers that must act on every device (the
     * sync-group reset, the partial supersede) work off the uncapped list.
     */
    private List<Long> capPushAudience(List<Long> effectiveIds, Long assignmentId) {
        int cap = pushOnConfirmCap > 0 ? pushOnConfirmCap : Integer.MAX_VALUE;
        if (effectiveIds.size() > cap) {
            log.warn("Push-on-confirm cap reached for assignment {} ({}/{}); "
                    + "remaining devices will pick up on next heartbeat",
                    assignmentId, cap, effectiveIds.size());
            return effectiveIds.subList(0, cap);
        }
        return effectiveIds;
    }

    /**
     * All device ids in the given target scope (region / facility / device-group),
     * ignoring soft-deleted devices. Used by both {@link #resolveTargetDeviceIds}
     * (post-confirm push) and {@link #confirmWithIncludedDevices} (inclusion-list
     * translation).
     */
    @Transactional(readOnly = true)
    public List<Long> listDeviceIdsForTarget(TargetType targetType, Long targetId) {
        return listDevicesForTarget(targetType, targetId).stream().map(Device::getId).toList();
    }

    /**
     * Resolve the union of device ids currently driven by any active assignment that
     * references the given playlist. Used by {@code PlaylistReorderedSyncPushListener}
     * so a playlist edit pushes SYNC to every device bound to that playlist via a
     * CONFIRMED, in-window assignment — regardless of target type (region, facility,
     * device-group). Same soft-cap policy as {@link #resolveTargetDeviceIds}; the
     * un-pushed remainder reconciles on next heartbeat.
     */
    @Transactional(readOnly = true)
    public List<Long> resolveDeviceIdsForActivePlaylist(Long playlistId, Instant atTime) {
        var assignments = assignmentRepository.findActiveByPlaylistId(playlistId, atTime);
        var unioned = new java.util.LinkedHashSet<Long>();
        for (var a : assignments) {
            unioned.addAll(listDeviceIdsForTarget(a.getTargetType(), a.getTargetId()));
        }
        int cap = pushOnConfirmCap > 0 ? pushOnConfirmCap : Integer.MAX_VALUE;
        if (unioned.size() > cap) {
            log.warn("Playlist-mutation push cap reached for playlist {} ({}/{}); "
                    + "remaining devices will pick up on next heartbeat",
                    playlistId, cap, unioned.size());
            return unioned.stream().limit(cap).toList();
        }
        return List.copyOf(unioned);
    }

    /**
     * Preview the devices that would be affected by an assignment to a given target.
     *
     * Returns each device's current status and currently-resolved content (if any),
     * capped at {@link #PREVIEW_CAP} entries. The total count is always reported
     * so the UI can show "showing 200 of 547".
     *
     * Offline devices are included (with the {@code offline} flag set) — operators
     * need to know the full footprint of the assignment, not just live devices.
     */
    @Transactional(readOnly = true)
    public PreviewResult previewForTarget(TargetType targetType, Long targetId, Instant atTime) {
        var scope = operatorScopeResolver.resolve();
        Long targetProjectId = targetProjectId(targetType, targetId);
        if (targetProjectId != null && scope.excludes(targetProjectId)) {
            // Preview a target in a project the operator can't see ⇒ 404 (treat as unknown target).
            throw new ResourceNotFoundException("Target", targetId);
        }
        long total = countDevicesForTarget(targetType, targetId);
        var devices = listDevicesForTarget(targetType, targetId, PREVIEW_CAP);

        // One query for all active assignments — then in-memory match per device.
        var activeAssignments = assignmentRepository.findActiveAtTime(atTime);
        var excludedAssignmentsByDevice = exclusionRepository.findAll().stream()
                .collect(Collectors.groupingBy(
                        e -> e.getDevice().getId(),
                        Collectors.mapping(e -> e.getAssignment().getId(), Collectors.toSet())));

        var items = new java.util.ArrayList<PreviewItem>();
        for (var device : devices) {
            var excluded = excludedAssignmentsByDevice.getOrDefault(device.getId(), Set.of());
            var current = activeAssignments.stream()
                    .filter(a -> a.getId() == null || !excluded.contains(a.getId()))
                    .filter(a -> matchesDevice(a, device))
                    // Same total order as resolveForDevice — the preview must show exactly what the
                    // device will play, including which of two overlapping campaigns wins now.
                    .max(ContentAssignment.PRECEDENCE)
                    .orElse(null);

            // Heartbeat-derived status, not the raw column. hasContent = the device resolves an
            // active (non-excluded) assignment here — so the preview's status is exclusion-aware.
            var computed = DeviceStatusEvaluator.evaluate(device.getLastHeartbeatAt(), current != null, atTime);

            items.add(new PreviewItem(
                    device.getId(),
                    device.getSerialNumber(),
                    device.getName(),
                    computed.name(),
                    computed == Device.Status.OFFLINE,
                    current != null ? current.getId() : null,
                    current != null && current.getPlaylist() != null ? current.getPlaylist().getId() : null
            ));
        }

        return new PreviewResult(items, total, items.size(), total > items.size());
    }

    private long countDevicesForTarget(TargetType targetType, Long targetId) {
        return switch (targetType) {
            case REGION -> deviceRepository.countByRegionIdAndDeletedAtIsNull(targetId);
            case FACILITY -> deviceRepository.countByFacilityIdAndDeletedAtIsNull(targetId);
            case DEVICE_GROUP -> deviceRepository.countByDeviceGroupIdAndDeletedAtIsNull(targetId);
        };
    }

    /** Every non-deleted device in the target scope — uncapped; the single load point. */
    private List<Device> listDevicesForTarget(TargetType targetType, Long targetId) {
        return switch (targetType) {
            case REGION -> deviceRepository.findByRegionIdAndDeletedAtIsNull(targetId);
            case FACILITY -> deviceRepository.findByFacilityIdAndDeletedAtIsNull(targetId);
            case DEVICE_GROUP -> deviceRepository.findByDeviceGroupIdAndDeletedAtIsNull(targetId);
        };
    }

    private List<Device> listDevicesForTarget(TargetType targetType, Long targetId, int cap) {
        var all = listDevicesForTarget(targetType, targetId);
        return all.size() > cap ? all.subList(0, cap) : all;
    }

    public static final int PREVIEW_CAP = 200;

    public record PreviewItem(
            Long deviceId,
            String serialNumber,
            String name,
            String status,
            boolean offline,
            Long currentAssignmentId,
            Long currentPlaylistId
    ) {}

    public record PreviewResult(
            List<PreviewItem> devices,
            long totalDevices,
            int returnedCount,
            boolean truncated
    ) {}

    /**
     * Sweep DRAFT assignments older than the threshold (1 hour) — soft-deletes them.
     * Designed to be called by a scheduled task.
     */
    @Transactional
    public int cleanExpiredDrafts(Instant threshold) {
        var expired = assignmentRepository.findExpiredDrafts(threshold);
        for (var assignment : expired) {
            assignment.softDelete();
            log.info("Auto-cleaned expired DRAFT assignment [id={}, age={}m]",
                    assignment.getId(),
                    java.time.Duration.between(assignment.getCreatedAt(), Instant.now()).toMinutes());
        }
        return expired.size();
    }

    @Transactional
    public ContentAssignmentExclusion excludeDevice(Long assignmentId, Device device, String reason) {
        var assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("ContentAssignment", assignmentId));

        if (exclusionRepository.existsByAssignmentIdAndDeviceId(assignmentId, device.getId())) {
            throw new IllegalStateException(
                    "Device %d is already excluded from assignment %d".formatted(device.getId(), assignmentId));
        }

        return exclusionRepository.save(new ContentAssignmentExclusion(assignment, device, reason));
    }

    /**
     * Resolve which playlist a device should play at a given time.
     *
     * <p>Winner = {@link ContentAssignment#PRECEDENCE}: most specific target
     * (DEVICE_GROUP 3 &gt; FACILITY 2 &gt; REGION 1), then most recently CONFIRMED, then highest
     * id. The recency term is the whole mechanism behind "Replace overrides only its own window"
     * (v1.0.142): a short campaign and the long assignment it replaced are both CONFIRMED and
     * overlapping, the campaign wins while it runs, and the predecessor resolves again the instant
     * the campaign's window closes — no row surgery, so proof-of-play and the playback anchors
     * stay intact.
     *
     * <p>Exclusions are checked — if a device is excluded from an assignment, it's skipped.
     */
    @Transactional(readOnly = true)
    public ContentAssignment resolveForDevice(Device device, Instant atTime) {
        var activeAssignments = assignmentRepository.findActiveAtTime(atTime);

        var excludedAssignmentIds = exclusionRepository.findByDeviceId(device.getId()).stream()
                .map(e -> e.getAssignment().getId())
                .collect(Collectors.toSet());

        return activeAssignments.stream()
                .filter(a -> !excludedAssignmentIds.contains(a.getId()))
                .filter(a -> matchesDevice(a, device))
                .max(ContentAssignment.PRECEDENCE)
                .orElse(null);
    }

    /**
     * Cancel (soft-delete) an assignment. Frees the target's overlap window and stops
     * resolution immediately — both {@code findOverlapping} and {@code findActiveAtTime}
     * filter {@code deletedAt IS NULL} — so a far-future "forever" assignment can be
     * retargeted without DB access.
     *
     * <p>Idempotency is deliberately not provided: cancelling an already-cancelled row
     * returns 404 (mirrors {@code ContentController.softDelete}) so admin UIs notice and
     * refresh rather than silently 204-ing on a stale row.
     *
     * <p>When the row was CONFIRMED (actually driving devices), publishes an
     * {@link AssignmentCancelledEvent} so the formerly-targeted, currently-online devices
     * re-resolve within ~1s instead of waiting a heartbeat. Cancelling a DRAFT pushes
     * nothing — it never drove any device. The push is an online-fast-path only; offline
     * devices reconcile on their next heartbeat via {@code DeviceSyncService.computeSyncPlan}.
     *
     * <p><b>Undoes its own narrowings.</b> If this assignment took devices off a predecessor via a
     * partial-device REPLACE ({@link #supersede}), those {@link ContentAssignmentExclusion} rows are
     * permanent and would outlive it — the devices would sit excluded from a still-CONFIRMED,
     * still-running predecessor and go dark. So the same transaction deletes exactly the exclusions
     * stamped with {@link #partialSupersedeReason} for THIS id. Operator-written exclusions (a
     * different reason) and narrowings caused by other assignments are untouched.
     */
    @Transactional
    public void softDelete(Long assignmentId) {
        var assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("ContentAssignment", assignmentId));
        if (assignment.isDeleted()) {
            throw new ResourceNotFoundException("ContentAssignment", assignmentId);
        }
        assertAssignmentInScope(assignment);

        boolean wasActive = assignment.isConfirmed();
        List<Long> targetDeviceIds = wasActive
                ? resolveTargetDeviceIds(assignment, Set.of(), assignmentId)
                : List.of();

        assignment.softDelete();
        int releasedNarrowings = exclusionRepository.deleteByReason(partialSupersedeReason(assignmentId));
        if (releasedNarrowings > 0) {
            log.info("Released {} narrowing exclusion(s) written when assignment {} superseded a "
                    + "predecessor — those devices resolve the predecessor again", releasedNarrowings,
                    assignmentId);
        }
        log.info("Cancelled assignment [id={}, wasConfirmed={}], notifying {} device(s)",
                assignmentId, wasActive, targetDeviceIds.size());

        if (wasActive && !targetDeviceIds.isEmpty()) {
            eventPublisher.publishEvent(new AssignmentCancelledEvent(assignmentId, targetDeviceIds));
        }
    }

    /**
     * Guard against assigning a playlist that has no items. Used at both draft creation
     * and confirmation so the operator finds out at step 1 of the wizard, with the same
     * message at confirm. {@code IllegalStateException} maps to 409.
     */
    private void requireNonEmptyPlaylist(Long playlistId) {
        if (playlistItemRepository.countByPlaylistId(playlistId) == 0) {
            throw new IllegalStateException(
                    "Cannot assign playlist %d — it has no items".formatted(playlistId));
        }
    }

    private void rejectTimeOverlap(TargetType targetType, Long targetId,
                                    Instant startTime, Instant endTime, Long excludeId) {
        var overlapping = excludeId == null
                ? assignmentRepository.findOverlapping(targetType, targetId, startTime, endTime)
                : assignmentRepository.findOverlappingExcluding(targetType, targetId, startTime, endTime, excludeId);

        if (!overlapping.isEmpty()) {
            var conflicts = overlapping.stream()
                    .map(AssignmentTimeOverlapException.Conflict::from)
                    .toList();
            throw new AssignmentTimeOverlapException(targetType, targetId, conflicts);
        }
    }

    /**
     * Device-aware confirm-time overlap gate. Two assignments on the same target only truly
     * conflict if their <b>effective device sets intersect</b> AND their time windows overlap —
     * merely sharing {@code (targetType, targetId)} + time is NOT a conflict when their device
     * subsets are disjoint (e.g. content A on devices [1,2,3] and content B on [4,5] in the same
     * region). Effective set = {@link #listDeviceIdsForTarget} MINUS that assignment's exclusions.
     *
     * <p>The new assignment's exclusions are the {@code excludedDeviceIds} param (still in memory —
     * they are persisted later in this transaction); each candidate's exclusions come from the DB.
     * The target's full device set is identical for every candidate (the candidate query is scoped
     * to the same {@code (targetType, targetId)}, and a device belongs to exactly one region /
     * facility / group), so it is fetched once and reused.
     *
     * <p>Scope note: this compares only SAME-target assignments. Cross-target overlap (a REGION
     * default + a DEVICE_GROUP/FACILITY override on a shared device) is NOT a conflict — those have
     * distinct target-type priorities and {@link #resolveForDevice} deterministically picks the
     * most specific one (intentional priority layering, pinned by the resolveForDevice_* tests).
     *
     * <p>When {@code replaceConflicting} is true, ONLY the device-intersecting candidates are
     * handed to {@link #supersede}; device-disjoint candidates are left untouched. What supersede
     * then does is itself bounded: a partially-intersecting predecessor is <b>narrowed to the
     * devices it keeps</b>, and a predecessor that outlasts the new window is <b>not modified at
     * all</b> — it is merely outranked for that window and resumes afterwards. "Replace" therefore
     * never implies the predecessor was rejected, cancelled or deleted; check the row.
     */
    private void resolveConfirmOverlap(ContentAssignment newAssignment, Long assignmentId,
                                       Set<Long> newExcluded, boolean replaceConflicting,
                                       List<Device> targetDevices) {
        var candidates = assignmentRepository.findOverlappingExcluding(
                newAssignment.getTargetType(), newAssignment.getTargetId(),
                newAssignment.getStartTime(), newAssignment.getEndTime(), assignmentId);
        if (candidates.isEmpty()) {
            return;
        }

        // Target order throughout (LinkedHashMap / LinkedHashSet) so conflictingDeviceIds and the
        // handover/remainder split are stable for the FE and the tests.
        var devicesById = new java.util.LinkedHashMap<Long, Device>();
        var newEffective = new java.util.LinkedHashSet<Long>();
        for (var device : targetDevices) {
            devicesById.put(device.getId(), device);
            if (!newExcluded.contains(device.getId())) {
                newEffective.add(device.getId());
            }
        }

        var conflicts = new java.util.ArrayList<AssignmentTimeOverlapException.Conflict>();
        var intersecting = new java.util.ArrayList<OverlapSplit>();
        for (var candidate : candidates) {
            var candidateExcluded = Set.copyOf(
                    exclusionRepository.findDeviceIdsByAssignmentId(candidate.getId()));
            // Split the predecessor's effective set (target MINUS its own exclusions) into the
            // devices the new assignment takes over and the ones it leaves behind.
            var handover = new java.util.ArrayList<Long>();
            var remainder = new java.util.ArrayList<Long>();
            for (var deviceId : devicesById.keySet()) {
                if (candidateExcluded.contains(deviceId)) {
                    continue;
                }
                (newEffective.contains(deviceId) ? handover : remainder).add(deviceId);
            }
            if (!handover.isEmpty()) {
                conflicts.add(AssignmentTimeOverlapException.Conflict.from(
                        candidate, handover, remainder.size()));
                intersecting.add(new OverlapSplit(candidate, handover, remainder));
            }
        }

        if (conflicts.isEmpty()) {
            // Time-overlapping but device-disjoint — not a real conflict.
            return;
        }
        if (!replaceConflicting) {
            throw new AssignmentTimeOverlapException(
                    newAssignment.getTargetType(), newAssignment.getTargetId(), conflicts);
        }
        for (var split : intersecting) {
            supersede(split, newAssignment, devicesById);
        }
        log.info("Replaced {} device-intersecting assignment(s) on {}:{} when confirming assignment {}",
                intersecting.size(), newAssignment.getTargetType(), newAssignment.getTargetId(),
                assignmentId);
    }

    /**
     * One device-intersecting predecessor and the per-device split a REPLACE has to honour:
     * {@code handover} = the devices the new assignment takes over (never empty — that is what
     * makes the predecessor a conflict at all), {@code remainder} = the devices the predecessor
     * keeps driving. Both in target order.
     */
    private record OverlapSplit(ContentAssignment predecessor, List<Long> handover, List<Long> remainder) {}

    /**
     * Retire one overlapping predecessor as part of a REPLACE confirm — <b>only as far as the new
     * assignment actually reaches</b>, in TIME as well as in devices.
     *
     * <p><b>The time gate (v1.0.142).</b> A predecessor is only ever touched when the new
     * assignment reaches at least as far as its end ({@code newEnd >= predEnd} — {@code >=}, not
     * {@code >}, so a "forever" campaign over a "forever" booking still retires the old row: both
     * carry the same year-2100 sentinel). When the new window ends FIRST, the predecessor is left
     * <b>completely untouched</b> — not truncated, not soft-deleted, and with NO exclusion rows,
     * which are permanent and would stop it resuming. The overlap is decided by
     * {@link ContentAssignment#PRECEDENCE} instead: the newly confirmed assignment wins while it
     * runs, and the predecessor resolves again the moment it ends. That is what makes a one-week
     * campaign over an open-ended booking a one-week campaign rather than a permanent takeover.
     *
     * <p><b>{@code remainder} empty</b> and the new assignment reaches the predecessor's end: it is
     * retired in full. {@code predStart < newStart} ⇒ <b>truncated</b> to end at the new start, so
     * the head it already ran (or is scheduled to run before the new window opens) survives in
     * history; otherwise ⇒ <b>soft-deleted</b>, because nothing of it would ever play.
     *
     * <p><b>{@code remainder} non-empty</b> and the new window covers the predecessor's whole
     * remaining life: retiring it would take content away from devices this reassignment never
     * selected (the operator-reported bug: "reassigning to some devices sets the playlist of the
     * unchecked devices to null"). Instead the predecessor is <b>narrowed</b> — one
     * {@link ContentAssignmentExclusion} per handed-over device — and stays CONFIRMED with its
     * original window, still driving {@code remainder}.
     *
     * <p>A narrowing needs the <b>from-now</b> test as well as the end test, because an exclusion
     * takes effect the instant it is written: a device handed over to a window that only opens next
     * week would otherwise resolve nothing in the meantime. When the new window opens later, nothing
     * is written — precedence hands that device over at the start edge, and the devices the operator
     * did NOT select keep the predecessor through the new assignment's own exclusions.
     *
     * <p>Narrowings are released again by {@link #softDelete}: an exclusion outliving the assignment
     * that caused it would strand its devices on a predecessor they are still excluded from.
     *
     * <p>Exclusions, not truncation or a version bump, because:
     * <ul>
     *   <li>{@code ContentVersionService.computeForAssignment} hashes
     *       {@code (assignmentId, versionNumber, playlistId, files+durations)} and does NOT read
     *       exclusions — so every {@code remainder} device's expected version stays byte-identical:
     *       no re-download, no interruption, {@code DeviceSyncService} sees {@code hasWork=false}.
     *       Truncating or bumping would churn devices that are not part of this reassignment at all.</li>
     *   <li>{@code PlaybackSyncSchedule} is keyed {@code (assignment_id, version_number)} and
     *       immutable, so {@code remainder} devices keep their frame-alignment anchor.</li>
     *   <li>The exclusion row's {@code createdAt} records WHEN each device left — per-device history
     *       truncation cannot express.</li>
     * </ul>
     *
     * <p>No duplicate-exclusion guard is needed: {@code handover} is a subset of the new
     * assignment's effective set, which is itself the target minus the predecessor's existing
     * exclusions, so the {@code UNIQUE(assignment_id, device_id)} constraint cannot be hit.
     *
     * <p>Either way the after-commit cancel push names exactly the handed-over devices — not the
     * predecessor's whole target — so only devices that really changed hands re-resolve. It is
     * published even in the untouched case: those devices must switch to the new content NOW, and
     * the predecessor row surviving does not change that.
     */
    private void supersede(OverlapSplit split, ContentAssignment newAssignment,
                           java.util.Map<Long, Device> targetDevicesById) {
        var predecessor = split.predecessor();
        var newStart = newAssignment.getStartTime();
        var now = Instant.now();
        boolean narrowing = !split.remainder().isEmpty();

        // Two independent "does the new assignment actually reach this far" tests:
        //   reachesPredecessorEnd — it runs at least as long as the predecessor.
        //     >= : the year-2100 "forever" sentinel is equal, not greater, on both rows.
        //   coversFromNow — it opens no later than the predecessor's REMAINING life begins (the
        //     predecessor's own start, or now if it is already running).
        boolean reachesPredecessorEnd =
                !newAssignment.getEndTime().isBefore(predecessor.getEndTime());
        var predecessorFrom = predecessor.getStartTime().isAfter(now)
                ? predecessor.getStartTime()
                : now;
        boolean coversFromNow = !newStart.isAfter(predecessorFrom);

        // A narrowing needs BOTH: an exclusion row is permanent and takes effect the instant it is
        // written, so writing one for a window that only opens next week blanks the handed-over
        // device from now until then — the exact failure this change exists to remove. A retire
        // needs only the end test: a head the new window does not cover is truncated, not lost.
        if (!reachesPredecessorEnd || (narrowing && !coversFromNow)) {
            log.info("Assignment {} overrides {} for its window only [{} .. {}); the predecessor "
                    + "stays CONFIRMED until {}, keeps every device until {}, and resumes afterwards",
                    newAssignment.getId(), predecessor.getId(), newStart,
                    newAssignment.getEndTime(), predecessor.getEndTime(), newStart);
        } else if (narrowing) {
            var reason = partialSupersedeReason(newAssignment.getId());
            for (var deviceId : split.handover()) {
                exclusionRepository.save(new ContentAssignmentExclusion(
                        predecessor, targetDevicesById.get(deviceId), reason));
            }
            log.info("Narrowed assignment {} on replace: {} device(s) handed over to assignment {}, "
                    + "{} device(s) keep it (unchanged content version)",
                    predecessor.getId(), split.handover().size(), newAssignment.getId(),
                    split.remainder().size());
        } else if (coversFromNow) {
            // Unchanged v1.0.135 behaviour.
            boolean runningNow = !predecessor.getStartTime().isAfter(now)
                    && predecessor.getEndTime().isAfter(now);
            if (runningNow && predecessor.getStartTime().isBefore(newStart)) {
                predecessor.truncateEndTo(newStart);
                log.info("Truncated running assignment {} to end at {} (superseded by replace)",
                        predecessor.getId(), newStart);
            } else {
                predecessor.softDelete();
                log.info("Soft-deleted assignment {} (superseded by replace)", predecessor.getId());
            }
        } else {
            // Not covering, but the new window runs to the predecessor's end: the predecessor
            // still gets to play its HEAD, from its own start until the new window opens. The
            // pre-v1.0.142 code soft-deleted this case whenever the predecessor was not running
            // yet, silently deleting a scheduled booking that had not even started.
            predecessor.truncateEndTo(newStart);
            log.info("Truncated assignment {} to end at {} (superseded by replace; its head "
                    + "before that instant is kept)", predecessor.getId(), newStart);
        }

        eventPublisher.publishEvent(
                new AssignmentCancelledEvent(predecessor.getId(), split.handover()));
    }

    private boolean matchesDevice(ContentAssignment assignment, Device device) {
        return switch (assignment.getTargetType()) {
            case REGION -> device.getRegion().getId().equals(assignment.getTargetId());
            case FACILITY -> device.getFacility() != null
                    && device.getFacility().getId().equals(assignment.getTargetId());
            case DEVICE_GROUP -> device.getDeviceGroup() != null
                    && device.getDeviceGroup().getId().equals(assignment.getTargetId());
        };
    }
}
