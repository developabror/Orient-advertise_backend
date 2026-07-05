package uz.orientadvertise.services.service;

import java.time.Instant;
import java.util.Comparator;
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
     * target is retired (soft-deleted, or truncated if already running) in this SAME transaction
     * before the new one goes CONFIRMED — so the target is never left empty. With it false, an
     * overlap throws {@link AssignmentTimeOverlapException} (the FE then offers "Replace").
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

        // Device-aware overlap gate. The new assignment's effective device set is the target's
        // devices MINUS the excludedDeviceIds param (NOT the DB — exclusions are persisted below,
        // after this check). A candidate only conflicts if its effective set actually intersects.
        resolveConfirmOverlap(assignment, assignmentId, excludedDeviceIds, replaceConflicting);

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

        // Publish a post-confirm event so currently-online in-scope devices get an
        // instant SYNC push. Subscribers fire on AFTER_COMMIT — if the surrounding
        // transaction rolls back (e.g. the overlap re-check above), nothing is pushed.
        // Offline devices in the same scope still pick up content on next heartbeat
        // poll via DeviceSyncService.computeSyncPlan, so the push is purely an
        // online-fast-path; we never depend on it for correctness.
        var targetDeviceIds = resolveTargetDeviceIds(
                assignment,
                excludedDeviceIds == null ? Set.of() : Set.copyOf(excludedDeviceIds),
                assignmentId);
        eventPublisher.publishEvent(new AssignmentConfirmedEvent(assignmentId, targetDeviceIds));

        return assignment;
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
        var filtered = allIds.stream()
                .filter(id -> !excludedIds.contains(id))
                .toList();
        int cap = pushOnConfirmCap > 0 ? pushOnConfirmCap : Integer.MAX_VALUE;
        if (filtered.size() > cap) {
            log.warn("Push-on-confirm cap reached for assignment {} ({}/{}); "
                    + "remaining devices will pick up on next heartbeat",
                    assignmentId, cap, filtered.size());
            return filtered.subList(0, cap);
        }
        return filtered;
    }

    /**
     * All device ids in the given target scope (region / facility / device-group),
     * ignoring soft-deleted devices. Used by both {@link #resolveTargetDeviceIds}
     * (post-confirm push) and {@link #confirmWithIncludedDevices} (inclusion-list
     * translation).
     */
    @Transactional(readOnly = true)
    public List<Long> listDeviceIdsForTarget(TargetType targetType, Long targetId) {
        var devices = switch (targetType) {
            case REGION -> deviceRepository.findByRegionIdAndDeletedAtIsNull(targetId);
            case FACILITY -> deviceRepository.findByFacilityIdAndDeletedAtIsNull(targetId);
            case DEVICE_GROUP -> deviceRepository.findByDeviceGroupIdAndDeletedAtIsNull(targetId);
        };
        return devices.stream().map(Device::getId).toList();
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
                    .max(Comparator.comparingInt(ContentAssignment::getPriority))
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

    private List<Device> listDevicesForTarget(TargetType targetType, Long targetId, int cap) {
        var all = switch (targetType) {
            case REGION -> deviceRepository.findByRegionIdAndDeletedAtIsNull(targetId);
            case FACILITY -> deviceRepository.findByFacilityIdAndDeletedAtIsNull(targetId);
            case DEVICE_GROUP -> deviceRepository.findByDeviceGroupIdAndDeletedAtIsNull(targetId);
        };
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
     * Priority rule: DEVICE_GROUP (3) > FACILITY (2) > REGION (1).
     * Exclusions are checked — if a device is excluded from an assignment, it's skipped.
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
                .max(Comparator.comparingInt(ContentAssignment::getPriority))
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
     * {@link #supersede superseded}; device-disjoint candidates are left untouched. (A
     * partially-intersecting predecessor is retired in full, including its non-conflicting devices
     * — there is no partial-device supersede; see README, flagged as a product decision.)
     */
    private void resolveConfirmOverlap(ContentAssignment newAssignment, Long assignmentId,
                                       java.util.Collection<Long> excludedDeviceIds,
                                       boolean replaceConflicting) {
        var candidates = assignmentRepository.findOverlappingExcluding(
                newAssignment.getTargetType(), newAssignment.getTargetId(),
                newAssignment.getStartTime(), newAssignment.getEndTime(), assignmentId);
        if (candidates.isEmpty()) {
            return;
        }

        var targetDeviceIds = listDeviceIdsForTarget(
                newAssignment.getTargetType(), newAssignment.getTargetId());
        // Tolerate a malformed null element in the exclusion list (e.g. JSON [null]) rather than
        // 500 on Set.copyOf — a null device id can never match a real target id anyway.
        var newExcluded = excludedDeviceIds == null
                ? Set.<Long>of()
                : excludedDeviceIds.stream().filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        // Deterministic order (target order) so conflictingDeviceIds is stable for the FE / tests.
        var newEffective = new java.util.LinkedHashSet<Long>();
        for (var id : targetDeviceIds) {
            if (!newExcluded.contains(id)) {
                newEffective.add(id);
            }
        }

        var conflicts = new java.util.ArrayList<AssignmentTimeOverlapException.Conflict>();
        var intersecting = new java.util.ArrayList<ContentAssignment>();
        for (var candidate : candidates) {
            var candidateExcluded = Set.copyOf(
                    exclusionRepository.findDeviceIdsByAssignmentId(candidate.getId()));
            var intersection = newEffective.stream()
                    .filter(id -> !candidateExcluded.contains(id))
                    .toList();
            if (!intersection.isEmpty()) {
                conflicts.add(AssignmentTimeOverlapException.Conflict.from(candidate, intersection));
                intersecting.add(candidate);
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
        for (var predecessor : intersecting) {
            supersede(predecessor, newAssignment.getStartTime());
        }
        log.info("Replaced {} device-intersecting assignment(s) on {}:{} when confirming assignment {}",
                intersecting.size(), newAssignment.getTargetType(), newAssignment.getTargetId(),
                assignmentId);
    }

    /**
     * Retire one overlapping predecessor as part of a REPLACE confirm. A predecessor already
     * running (its window covers "now") is <b>truncated</b> to end at {@code newStart} so history
     * shows it ran until the cutover; a future/forever predecessor is <b>soft-deleted</b>. Either
     * way it stops resolving (both overlap and active-time queries filter {@code deletedAt IS NULL}
     * and the window), and we emit the same after-commit cancel push the {@code DELETE} path uses
     * so every device the predecessor drove — including any the new assignment excludes —
     * re-resolves within ~1s. The predecessor came from {@code findOverlappingExcluding}, so it is
     * guaranteed CONFIRMED and not yet soft-deleted.
     */
    private void supersede(ContentAssignment predecessor, Instant newStart) {
        // Capture the predecessor's former audience BEFORE mutating it.
        var formerDeviceIds = resolveTargetDeviceIds(predecessor, Set.of(), predecessor.getId());

        var now = Instant.now();
        boolean runningNow = !predecessor.getStartTime().isAfter(now) && predecessor.getEndTime().isAfter(now);
        if (runningNow && predecessor.getStartTime().isBefore(newStart)) {
            predecessor.truncateEndTo(newStart);
            log.info("Truncated running assignment {} to end at {} (superseded by replace)",
                    predecessor.getId(), newStart);
        } else {
            predecessor.softDelete();
            log.info("Soft-deleted assignment {} (superseded by replace)", predecessor.getId());
        }

        if (!formerDeviceIds.isEmpty()) {
            eventPublisher.publishEvent(new AssignmentCancelledEvent(predecessor.getId(), formerDeviceIds));
        }
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
