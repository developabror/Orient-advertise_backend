package uz.orientadvertise.services.service;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import uz.orientadvertise.services.domain.model.ContentAssignment;
import uz.orientadvertise.services.domain.model.ContentAssignment.TargetType;
import uz.orientadvertise.services.domain.model.ContentAssignmentExclusion;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.DeviceGroup;
import uz.orientadvertise.services.domain.model.Facility;
import uz.orientadvertise.services.domain.model.Playlist;
import uz.orientadvertise.services.domain.model.Project;
import uz.orientadvertise.services.domain.model.Region;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.service.exception.AssignmentTimeOverlapException;
import uz.orientadvertise.services.service.OperatorScopeResolver.ScopedProjects;
import uz.orientadvertise.services.domain.repository.ContentAssignmentExclusionRepository;
import uz.orientadvertise.services.domain.repository.ContentAssignmentRepository;
import uz.orientadvertise.services.domain.repository.DeviceGroupRepository;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.FacilityRepository;
import uz.orientadvertise.services.domain.repository.PlaylistItemRepository;
import uz.orientadvertise.services.domain.repository.RegionRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ContentAssignmentServiceTest {

    private ContentAssignmentRepository assignmentRepository;
    private ContentAssignmentExclusionRepository exclusionRepository;
    private DeviceRepository deviceRepository;
    private PlaylistItemRepository playlistItemRepository;
    private ApplicationEventPublisher eventPublisher;
    private RegionRepository regionRepository;
    private FacilityRepository facilityRepository;
    private DeviceGroupRepository deviceGroupRepository;
    private OperatorScopeResolver operatorScopeResolver;
    private ContentAssignmentService service;

    private final Instant now = Instant.now();
    private final Instant tomorrow = now.plus(1, ChronoUnit.DAYS);
    private final Instant nextWeek = now.plus(7, ChronoUnit.DAYS);

    @BeforeEach
    void setUp() throws Exception {
        assignmentRepository = mock(ContentAssignmentRepository.class);
        exclusionRepository = mock(ContentAssignmentExclusionRepository.class);
        deviceRepository = mock(DeviceRepository.class);
        playlistItemRepository = mock(PlaylistItemRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        regionRepository = mock(RegionRepository.class);
        facilityRepository = mock(FacilityRepository.class);
        deviceGroupRepository = mock(DeviceGroupRepository.class);
        operatorScopeResolver = mock(OperatorScopeResolver.class);
        service = new ContentAssignmentService(assignmentRepository, exclusionRepository,
                deviceRepository, playlistItemRepository, eventPublisher,
                regionRepository, facilityRepository, deviceGroupRepository, operatorScopeResolver);

        // Unrestricted scope: assertAssignmentInScope short-circuits (restricted=false) so no
        // project navigation is touched. Lenient because resolveForDevice/conflictFrom tests
        // never reach the scope guard.
        lenient().when(operatorScopeResolver.resolve())
                .thenReturn(new ScopedProjects(null, null, null, false));

        // Playlists are non-empty by default; empty-playlist tests override to 0.
        when(playlistItemRepository.countByPlaylistId(any())).thenReturn(1L);

        // pushOnConfirmCap is @Value-injected; reflect to seed the default.
        Field capField = ContentAssignmentService.class.getDeclaredField("pushOnConfirmCap");
        capField.setAccessible(true);
        capField.setInt(service, 5000);

        // Default empty target enumerations so the new event-publish path inside
        // confirmWithExclusions does not NPE for tests that don't care about the
        // event. Tests covering the event-publish contract override these stubs.
        when(deviceRepository.findByRegionIdAndDeletedAtIsNull(any())).thenReturn(List.of());
        when(deviceRepository.findByFacilityIdAndDeletedAtIsNull(any())).thenReturn(List.of());
        when(deviceRepository.findByDeviceGroupIdAndDeletedAtIsNull(any())).thenReturn(List.of());
    }

    @Test
    void createAssignment_noOverlap_succeeds() {
        when(assignmentRepository.findOverlapping(any(), anyLong(), any(), any()))
                .thenReturn(List.of());
        when(assignmentRepository.save(any(ContentAssignment.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var playlist = new Playlist(new Project("P", null), "PL", null);
        var result = service.createAssignment(playlist, TargetType.REGION, 1L, now, tomorrow);

        assertNotNull(result);
        assertEquals(TargetType.REGION, result.getTargetType());
        assertEquals(1, result.getPriority()); // REGION priority = 1
    }

    @Test
    void createAssignment_withOverlap_throwsIllegalState() {
        var existing = new ContentAssignment(
                new Playlist(new Project("P", null), "PL", null),
                TargetType.REGION, 1L, now, nextWeek);
        when(assignmentRepository.findOverlapping(eq(TargetType.REGION), eq(1L), any(), any()))
                .thenReturn(List.of(existing));

        var playlist = new Playlist(new Project("P", null), "PL2", null);
        assertThrows(IllegalStateException.class, () ->
                service.createAssignment(playlist, TargetType.REGION, 1L, tomorrow, nextWeek));
    }

    @Test
    void createDraft_overlap_noLongerThrows_persistsDraft() {
        // Drafts don't resolve and auto-expire (1h); overlap is now decided at confirm (device-aware),
        // not at draft creation — so a region-level time overlap must NOT block draft creation.
        when(assignmentRepository.save(any(ContentAssignment.class))).thenAnswer(inv -> inv.getArgument(0));

        var playlist = new Playlist(new Project("P", null), "PL", null);
        var result = service.createDraft(playlist, TargetType.REGION, 1L, now, nextWeek);

        assertEquals(ContentAssignment.Status.DRAFT, result.getStatus());
        // The overlap query must not even be consulted at draft time.
        verify(assignmentRepository, never()).findOverlapping(any(), anyLong(), any(), any());
    }

    @Test
    void createAssignment_prioritySetFromTargetType() {
        when(assignmentRepository.findOverlapping(any(), anyLong(), any(), any()))
                .thenReturn(List.of());
        when(assignmentRepository.save(any(ContentAssignment.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var playlist = new Playlist(new Project("P", null), "PL", null);

        var regionAssign = service.createAssignment(playlist, TargetType.REGION, 1L, now, tomorrow);
        assertEquals(1, regionAssign.getPriority());

        var facilityAssign = service.createAssignment(playlist, TargetType.FACILITY, 1L, now, tomorrow);
        assertEquals(2, facilityAssign.getPriority());

        var groupAssign = service.createAssignment(playlist, TargetType.DEVICE_GROUP, 1L, now, tomorrow);
        assertEquals(3, groupAssign.getPriority());
    }

    @Test
    void resolveForDevice_groupTakesPriorityOverRegion() {
        var device = mockDevice(10L, null, 20L);

        var regionAssignment = mockAssignment(1L, TargetType.REGION, 10L, 1);
        var groupAssignment = mockAssignment(2L, TargetType.DEVICE_GROUP, 20L, 3);

        when(assignmentRepository.findActiveAtTime(any()))
                .thenReturn(List.of(regionAssignment, groupAssignment));
        when(exclusionRepository.findByDeviceId(any())).thenReturn(List.of());

        var result = service.resolveForDevice(device, now);

        assertNotNull(result);
        assertEquals(TargetType.DEVICE_GROUP, result.getTargetType());
    }

    @Test
    void resolveForDevice_excludedAssignment_skipped() {
        var device = mockDevice(10L, null, null);

        var assignment = mockAssignment(1L, TargetType.REGION, 10L, 1);
        when(assignmentRepository.findActiveAtTime(any())).thenReturn(List.of(assignment));

        var exclusion = mock(ContentAssignmentExclusion.class);
        var excludedAssignment = mock(ContentAssignment.class);
        when(excludedAssignment.getId()).thenReturn(1L);
        when(exclusion.getAssignment()).thenReturn(excludedAssignment);
        when(exclusionRepository.findByDeviceId(any())).thenReturn(List.of(exclusion));

        var result = service.resolveForDevice(device, now);

        assertNull(result, "Excluded device should not receive the assignment");
    }

    @Test
    void resolveForDevice_facilityOverRegion() {
        var device = mockDevice(10L, 30L, null);

        var regionAssignment = mockAssignment(1L, TargetType.REGION, 10L, 1);
        var facilityAssignment = mockAssignment(2L, TargetType.FACILITY, 30L, 2);

        when(assignmentRepository.findActiveAtTime(any()))
                .thenReturn(List.of(regionAssignment, facilityAssignment));
        when(exclusionRepository.findByDeviceId(any())).thenReturn(List.of());

        var result = service.resolveForDevice(device, now);

        assertNotNull(result);
        assertEquals(TargetType.FACILITY, result.getTargetType());
    }

    private ContentAssignment mockAssignment(Long id, TargetType type, Long targetId, int priority) {
        var a = mock(ContentAssignment.class);
        when(a.getId()).thenReturn(id);
        when(a.getTargetType()).thenReturn(type);
        when(a.getTargetId()).thenReturn(targetId);
        when(a.getPriority()).thenReturn(priority);
        return a;
    }

    private Device mockDevice(Long regionId, Long facilityId, Long groupId) {
        var device = mock(Device.class);
        when(device.getId()).thenReturn(1L);

        var region = mock(Region.class);
        when(region.getId()).thenReturn(regionId);
        when(device.getRegion()).thenReturn(region);

        if (facilityId != null) {
            var facility = mock(Facility.class);
            when(facility.getId()).thenReturn(facilityId);
            when(device.getFacility()).thenReturn(facility);
        }

        if (groupId != null) {
            var group = mock(DeviceGroup.class);
            when(group.getId()).thenReturn(groupId);
            when(device.getDeviceGroup()).thenReturn(group);
        }

        return device;
    }

    // ----- Draft creation / confirmation -----

    @Test
    void createDraft_persistsWithDraftStatus() {
        when(assignmentRepository.save(any(ContentAssignment.class))).thenAnswer(inv -> inv.getArgument(0));

        var playlist = new Playlist(new Project("P", null), "PL", null);
        var result = service.createDraft(playlist, TargetType.REGION, 1L, now, tomorrow);

        assertEquals(ContentAssignment.Status.DRAFT, result.getStatus());
    }

    @Test
    void confirmWithExclusions_atomicallyFlipsStatusAndAttachesExclusions() {
        var playlist = new Playlist(new Project("P", null), "PL", null);
        var assignment = new ContentAssignment(playlist, TargetType.REGION, 1L, now, tomorrow,
                ContentAssignment.Status.DRAFT);
        when(assignmentRepository.findById(1L)).thenReturn(Optional.of(assignment));
        when(assignmentRepository.findOverlappingExcluding(any(), anyLong(), any(), any(), eq(1L)))
                .thenReturn(List.of());

        var device1 = mock(Device.class);
        var device2 = mock(Device.class);
        when(device1.getId()).thenReturn(10L);
        when(device2.getId()).thenReturn(20L);
        when(deviceRepository.findAllById(List.of(10L, 20L))).thenReturn(List.of(device1, device2));

        var result = service.confirmWithExclusions(1L, List.of(10L, 20L), "operator manual override");

        assertEquals(ContentAssignment.Status.CONFIRMED, result.getStatus());
        org.mockito.Mockito.verify(exclusionRepository, org.mockito.Mockito.times(2))
                .save(any(ContentAssignmentExclusion.class));
    }

    @Test
    void confirmWithExclusions_emptyList_stillFlipsStatus() {
        var playlist = new Playlist(new Project("P", null), "PL", null);
        var assignment = new ContentAssignment(playlist, TargetType.REGION, 1L, now, tomorrow,
                ContentAssignment.Status.DRAFT);
        when(assignmentRepository.findById(1L)).thenReturn(Optional.of(assignment));
        when(assignmentRepository.findOverlappingExcluding(any(), anyLong(), any(), any(), eq(1L)))
                .thenReturn(List.of());

        var result = service.confirmWithExclusions(1L, List.of(), null);

        assertEquals(ContentAssignment.Status.CONFIRMED, result.getStatus());
        org.mockito.Mockito.verify(exclusionRepository, org.mockito.Mockito.never())
                .save(any(ContentAssignmentExclusion.class));
    }

    @Test
    void confirmWithExclusions_alreadyConfirmed_throws() {
        var playlist = new Playlist(new Project("P", null), "PL", null);
        var assignment = new ContentAssignment(playlist, TargetType.REGION, 1L, now, tomorrow,
                ContentAssignment.Status.CONFIRMED);
        when(assignmentRepository.findById(1L)).thenReturn(Optional.of(assignment));

        assertThrows(IllegalStateException.class, () ->
                service.confirmWithExclusions(1L, List.of(), null));
    }

    @Test
    void confirmWithExclusions_overlapDetectedAtConfirm_throws() {
        var playlist = new Playlist(new Project("P", null), "PL", null);
        var assignment = new ContentAssignment(playlist, TargetType.REGION, 1L, now, tomorrow,
                ContentAssignment.Status.DRAFT);
        var conflicting = new ContentAssignment(playlist, TargetType.REGION, 1L, now, tomorrow,
                ContentAssignment.Status.CONFIRMED);
        when(assignmentRepository.findById(1L)).thenReturn(Optional.of(assignment));
        when(assignmentRepository.findOverlappingExcluding(any(), anyLong(), any(), any(), eq(1L)))
                .thenReturn(List.of(conflicting));
        // Device-aware: both cover device 10 in region 1 (neither excludes it) → effective sets intersect.
        when(deviceRepository.findByRegionIdAndDeletedAtIsNull(1L)).thenReturn(List.of(deviceWithId(10L)));

        assertThrows(IllegalStateException.class, () ->
                service.confirmWithExclusions(1L, List.of(), null));

        // Status must NOT have flipped — atomicity requires no partial state
        assertEquals(ContentAssignment.Status.DRAFT, assignment.getStatus());
    }

    // ----- Replace / supersede on confirm + enriched conflict -----

    @Test
    void confirmWithExclusions_replaceConflicting_softDeletesFuturePredecessor_andConfirmsNew() {
        var forever = Instant.parse("2100-01-01T00:00:00Z");
        var playlist = new Playlist(new Project("P", null), "PL", null);
        var newAssignment = new ContentAssignment(playlist, TargetType.REGION, 1L, now, forever,
                ContentAssignment.Status.DRAFT);
        var predecessor = new ContentAssignment(playlist, TargetType.REGION, 1L,
                now.plus(1, ChronoUnit.HOURS), forever, ContentAssignment.Status.CONFIRMED);
        when(assignmentRepository.findById(1L)).thenReturn(Optional.of(newAssignment));
        when(assignmentRepository.findOverlappingExcluding(eq(TargetType.REGION), eq(1L), any(), any(), eq(1L)))
                .thenReturn(List.of(predecessor));
        when(deviceRepository.findByRegionIdAndDeletedAtIsNull(1L)).thenReturn(List.of(deviceWithId(10L)));

        var result = service.confirmWithExclusions(1L, List.of(), null, true);

        assertTrue(predecessor.isDeleted(), "future/forever predecessor must be soft-deleted on replace");
        assertEquals(ContentAssignment.Status.CONFIRMED, result.getStatus(), "new assignment confirmed");
    }

    @Test
    void confirmWithExclusions_overlapWithoutReplaceFlag_throws_predecessorUntouched() {
        var forever = Instant.parse("2100-01-01T00:00:00Z");
        var playlist = new Playlist(new Project("P", null), "PL", null);
        var newAssignment = new ContentAssignment(playlist, TargetType.REGION, 1L, now, forever,
                ContentAssignment.Status.DRAFT);
        var predecessor = new ContentAssignment(playlist, TargetType.REGION, 1L,
                now.plus(1, ChronoUnit.HOURS), forever, ContentAssignment.Status.CONFIRMED);
        when(assignmentRepository.findById(1L)).thenReturn(Optional.of(newAssignment));
        when(assignmentRepository.findOverlappingExcluding(eq(TargetType.REGION), eq(1L), any(), any(), eq(1L)))
                .thenReturn(List.of(predecessor));
        when(deviceRepository.findByRegionIdAndDeletedAtIsNull(1L)).thenReturn(List.of(deviceWithId(10L)));

        assertThrows(AssignmentTimeOverlapException.class, () ->
                service.confirmWithExclusions(1L, List.of(), null, false));

        assertFalse(predecessor.isDeleted(), "predecessor untouched when not replacing");
        assertEquals(ContentAssignment.Status.DRAFT, newAssignment.getStatus(), "new assignment stays DRAFT");
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void confirmWithExclusions_replaceConflicting_runningPredecessor_truncatedNotDeleted() {
        var forever = Instant.parse("2100-01-01T00:00:00Z");
        var playlist = new Playlist(new Project("P", null), "PL", null);
        var newAssignment = new ContentAssignment(playlist, TargetType.REGION, 1L, now, forever,
                ContentAssignment.Status.DRAFT);
        // Predecessor started yesterday and runs forever → currently active ⇒ truncate, not delete.
        var predecessor = new ContentAssignment(playlist, TargetType.REGION, 1L,
                now.minus(1, ChronoUnit.DAYS), forever, ContentAssignment.Status.CONFIRMED);
        when(assignmentRepository.findById(1L)).thenReturn(Optional.of(newAssignment));
        when(assignmentRepository.findOverlappingExcluding(eq(TargetType.REGION), eq(1L), any(), any(), eq(1L)))
                .thenReturn(List.of(predecessor));
        when(deviceRepository.findByRegionIdAndDeletedAtIsNull(1L)).thenReturn(List.of(deviceWithId(10L)));

        service.confirmWithExclusions(1L, List.of(), null, true);

        assertFalse(predecessor.isDeleted(), "running predecessor is truncated, not soft-deleted");
        assertEquals(now, predecessor.getEndTime(), "truncated to end at the new assignment's start");
        assertEquals(ContentAssignment.Status.CONFIRMED, newAssignment.getStatus());
    }

    @Test
    void conflictFrom_carriesPlaylistIdNameAndStatus() {
        var playlist = mock(Playlist.class);
        when(playlist.getId()).thenReturn(3L);
        when(playlist.getName()).thenReturn("Korzinka promo");
        var a = mock(ContentAssignment.class);
        when(a.getId()).thenReturn(6L);
        when(a.getPlaylist()).thenReturn(playlist);
        when(a.getStatus()).thenReturn(ContentAssignment.Status.CONFIRMED);
        when(a.getStartTime()).thenReturn(now);
        when(a.getEndTime()).thenReturn(tomorrow);

        var c = AssignmentTimeOverlapException.Conflict.from(a);

        assertEquals(6L, c.id());
        assertEquals(3L, c.playlistId());
        assertEquals("Korzinka promo", c.playlistName());
        assertEquals("CONFIRMED", c.status());
        assertEquals(now, c.startTime());
        assertEquals(tomorrow, c.endTime());
        assertEquals(List.of(), c.conflictingDeviceIds(), "from(a) leaves device intersection empty");

        // The device-aware overload carries the intersecting ids (in given order).
        var withDevices = AssignmentTimeOverlapException.Conflict.from(a, List.of(10L, 30L));
        assertEquals(List.of(10L, 30L), withDevices.conflictingDeviceIds());
    }

    @Test
    void conflictFrom_nullPlaylist_isNullSafe() {
        var a = mock(ContentAssignment.class);
        when(a.getId()).thenReturn(6L);
        when(a.getPlaylist()).thenReturn(null);
        when(a.getStatus()).thenReturn(ContentAssignment.Status.CONFIRMED);
        when(a.getStartTime()).thenReturn(now);
        when(a.getEndTime()).thenReturn(tomorrow);

        var c = AssignmentTimeOverlapException.Conflict.from(a);

        assertNull(c.playlistId(), "null playlist must not NPE — id is null");
        assertNull(c.playlistName());
        assertEquals("CONFIRMED", c.status());
        assertEquals(6L, c.id());
        assertEquals(List.of(), c.conflictingDeviceIds(), "device-id list is never null");
    }

    // ----- Device-aware overlap (same target, intersecting effective device sets) -----

    /** A REAL Device with only its id set (via reflection) — listDeviceIdsForTarget only reads
     *  getId(). Built without Mockito so it is safe to call inside a {@code when(...).thenReturn(...)}
     *  argument (a nested {@code when(d.getId())} would otherwise throw UnfinishedStubbingException). */
    private Device deviceWithId(Long id) {
        try {
            var ctor = Device.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            var d = ctor.newInstance();
            Field f = Device.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(d, id);
            return d;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    /** Reflectively set the generated id on a real ContentAssignment (no setter) so candidates
     *  can be distinguished by id for per-assignment exclusion lookups. */
    private static void setId(ContentAssignment a, Long id) {
        try {
            Field f = ContentAssignment.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(a, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void confirm_disjointDeviceSubsets_sameRegion_noConflict() {
        // THE BUG REPRO: content A on [10,20,30], content B on [40,50] in REGION:1, overlapping time.
        // Region 1 has devices [10,20,30,40,50]. Candidate (A) excludes [40,50] → covers [10,20,30].
        // New (B) excludes [10,20,30] → covers [40,50]. Disjoint → MUST NOT conflict.
        var playlist = new Playlist(new Project("P", null), "PL", null);
        var newDraft = new ContentAssignment(playlist, TargetType.REGION, 1L, now, nextWeek,
                ContentAssignment.Status.DRAFT);
        var candidateA = new ContentAssignment(playlist, TargetType.REGION, 1L, now, nextWeek,
                ContentAssignment.Status.CONFIRMED);
        when(assignmentRepository.findById(1L)).thenReturn(Optional.of(newDraft));
        when(assignmentRepository.findOverlappingExcluding(eq(TargetType.REGION), eq(1L), any(), any(), eq(1L)))
                .thenReturn(List.of(candidateA));
        when(deviceRepository.findByRegionIdAndDeletedAtIsNull(1L)).thenReturn(List.of(
                deviceWithId(10L), deviceWithId(20L), deviceWithId(30L), deviceWithId(40L), deviceWithId(50L)));
        when(deviceRepository.findAllById(java.util.List.of(10L, 20L, 30L))).thenReturn(List.of(
                deviceWithId(10L), deviceWithId(20L), deviceWithId(30L)));
        // candidateA (id null) excludes [40,50]; new excludes [10,20,30].
        when(exclusionRepository.findDeviceIdsByAssignmentId(null)).thenReturn(List.of(40L, 50L));

        var result = service.confirmWithExclusions(1L, List.of(10L, 20L, 30L), null, false);

        assertEquals(ContentAssignment.Status.CONFIRMED, result.getStatus(),
                "disjoint device subsets on the same region+time must NOT 409");
    }

    @Test
    void confirm_overlappingDeviceSubset_throws_withConflictingDeviceIds() {
        // Region 1 = [10,20,30]. Candidate excludes [30] → covers [10,20]. New excludes [20] → covers [10,30].
        // Intersection = [10] → conflict, and the exception must name exactly device 10.
        var playlist = new Playlist(new Project("P", null), "PL", null);
        var newDraft = new ContentAssignment(playlist, TargetType.REGION, 1L, now, nextWeek,
                ContentAssignment.Status.DRAFT);
        var candidate = new ContentAssignment(playlist, TargetType.REGION, 1L, now, nextWeek,
                ContentAssignment.Status.CONFIRMED);
        when(assignmentRepository.findById(1L)).thenReturn(Optional.of(newDraft));
        when(assignmentRepository.findOverlappingExcluding(eq(TargetType.REGION), eq(1L), any(), any(), eq(1L)))
                .thenReturn(List.of(candidate));
        when(deviceRepository.findByRegionIdAndDeletedAtIsNull(1L)).thenReturn(List.of(
                deviceWithId(10L), deviceWithId(20L), deviceWithId(30L)));
        when(deviceRepository.findAllById(java.util.List.of(20L))).thenReturn(List.of(deviceWithId(20L)));
        when(exclusionRepository.findDeviceIdsByAssignmentId(null)).thenReturn(List.of(30L));

        var ex = assertThrows(AssignmentTimeOverlapException.class, () ->
                service.confirmWithExclusions(1L, List.of(20L), null, false));

        assertEquals(1, ex.getConflicts().size());
        assertEquals(List.of(10L), ex.getConflicts().get(0).conflictingDeviceIds(),
                "only the intersecting device id is reported");
        assertEquals(ContentAssignment.Status.DRAFT, newDraft.getStatus());
    }

    @Test
    void confirm_wholeRegionVsSubset_conflicts_onSubset() {
        // Candidate covers the whole region (no exclusions); new covers only [30]. Intersection=[30].
        var playlist = new Playlist(new Project("P", null), "PL", null);
        var newDraft = new ContentAssignment(playlist, TargetType.REGION, 1L, now, nextWeek,
                ContentAssignment.Status.DRAFT);
        var whole = new ContentAssignment(playlist, TargetType.REGION, 1L, now, nextWeek,
                ContentAssignment.Status.CONFIRMED);
        when(assignmentRepository.findById(1L)).thenReturn(Optional.of(newDraft));
        when(assignmentRepository.findOverlappingExcluding(eq(TargetType.REGION), eq(1L), any(), any(), eq(1L)))
                .thenReturn(List.of(whole));
        when(deviceRepository.findByRegionIdAndDeletedAtIsNull(1L)).thenReturn(List.of(
                deviceWithId(10L), deviceWithId(20L), deviceWithId(30L)));
        when(deviceRepository.findAllById(java.util.List.of(10L, 20L))).thenReturn(List.of(
                deviceWithId(10L), deviceWithId(20L)));
        when(exclusionRepository.findDeviceIdsByAssignmentId(null)).thenReturn(List.of()); // candidate excludes nothing

        var ex = assertThrows(AssignmentTimeOverlapException.class, () ->
                service.confirmWithExclusions(1L, List.of(10L, 20L), null, false));

        assertEquals(List.of(30L), ex.getConflicts().get(0).conflictingDeviceIds());
    }

    @Test
    void confirm_usesExcludedParamNotDb_atCheckTime() {
        // Pin the param-vs-DB trap: the new assignment's exclusions are NOT yet persisted at check
        // time, so newEffective must come from the excludedDeviceIds PARAM. Region [10]; candidate
        // covers [10]; new excludes [10] via the param → newEffective empty → NO conflict.
        var playlist = new Playlist(new Project("P", null), "PL", null);
        var newDraft = new ContentAssignment(playlist, TargetType.REGION, 1L, now, nextWeek,
                ContentAssignment.Status.DRAFT);
        var candidate = new ContentAssignment(playlist, TargetType.REGION, 1L, now, nextWeek,
                ContentAssignment.Status.CONFIRMED);
        when(assignmentRepository.findById(1L)).thenReturn(Optional.of(newDraft));
        when(assignmentRepository.findOverlappingExcluding(eq(TargetType.REGION), eq(1L), any(), any(), eq(1L)))
                .thenReturn(List.of(candidate));
        when(deviceRepository.findByRegionIdAndDeletedAtIsNull(1L)).thenReturn(List.of(deviceWithId(10L)));
        when(deviceRepository.findAllById(java.util.List.of(10L))).thenReturn(List.of(deviceWithId(10L)));
        when(exclusionRepository.findDeviceIdsByAssignmentId(null)).thenReturn(List.of()); // candidate covers [10]

        var result = service.confirmWithExclusions(1L, List.of(10L), null, false);

        assertEquals(ContentAssignment.Status.CONFIRMED, result.getStatus(),
                "new assignment excludes its only device via the param → drives nothing → no conflict");
    }

    @Test
    void confirm_replaceConflicting_supersedesOnlyIntersecting() {
        // Region [10,20,30,40]. candidateA excludes [30,40] → covers [10,20] (intersects new's [10]).
        // candidateB excludes [10,20] → covers [30,40] (disjoint from new). New covers [10].
        // replace must retire ONLY candidateA; candidateB untouched.
        var playlist = new Playlist(new Project("P", null), "PL", null);
        var newDraft = new ContentAssignment(playlist, TargetType.REGION, 1L, now, nextWeek,
                ContentAssignment.Status.DRAFT);
        var candidateA = new ContentAssignment(playlist, TargetType.REGION, 1L, now, nextWeek,
                ContentAssignment.Status.CONFIRMED);
        var candidateB = new ContentAssignment(playlist, TargetType.REGION, 1L, now, nextWeek,
                ContentAssignment.Status.CONFIRMED);
        when(assignmentRepository.findById(1L)).thenReturn(Optional.of(newDraft));
        when(assignmentRepository.findOverlappingExcluding(eq(TargetType.REGION), eq(1L), any(), any(), eq(1L)))
                .thenReturn(List.of(candidateA, candidateB));
        when(deviceRepository.findByRegionIdAndDeletedAtIsNull(1L)).thenReturn(List.of(
                deviceWithId(10L), deviceWithId(20L), deviceWithId(30L), deviceWithId(40L)));
        when(deviceRepository.findAllById(java.util.List.of(20L, 30L, 40L))).thenReturn(List.of(
                deviceWithId(20L), deviceWithId(30L), deviceWithId(40L)));
        // Distinguish the two candidates by id so their exclusion sets differ.
        setId(candidateA, 100L);
        setId(candidateB, 200L);
        when(exclusionRepository.findDeviceIdsByAssignmentId(100L)).thenReturn(List.of(30L, 40L));
        when(exclusionRepository.findDeviceIdsByAssignmentId(200L)).thenReturn(List.of(10L, 20L));

        service.confirmWithExclusions(1L, List.of(20L, 30L, 40L), null, true);

        assertTrue(candidateA.isDeleted(), "intersecting candidate A is superseded");
        assertFalse(candidateB.isDeleted(), "device-disjoint candidate B is left untouched");
        assertEquals(ContentAssignment.Status.CONFIRMED, newDraft.getStatus());

        // Supersede must emit an AssignmentCancelledEvent for ONLY the superseded predecessor (A),
        // so its former audience re-resolves (~1s). (The confirm also publishes a Confirmed event.)
        var captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeast(1)).publishEvent(captor.capture());
        var cancelled = captor.getAllValues().stream()
                .filter(e -> e instanceof AssignmentCancelledEvent)
                .map(e -> (AssignmentCancelledEvent) e)
                .toList();
        assertEquals(1, cancelled.size(), "exactly one predecessor superseded → one cancel event");
        assertEquals(100L, cancelled.get(0).assignmentId(), "cancel event names the superseded candidate A");
    }

    @Test
    void confirm_multiDeviceIntersection_reportedInTargetOrder() {
        // Region 1 = [10,20,30,40] (target order). New excludes [20,40] → covers [10,30].
        // Candidate (real id) excludes nothing → covers all. Intersection must be [10,30] IN ORDER.
        var playlist = new Playlist(new Project("P", null), "PL", null);
        var newDraft = new ContentAssignment(playlist, TargetType.REGION, 1L, now, nextWeek,
                ContentAssignment.Status.DRAFT);
        var candidate = new ContentAssignment(playlist, TargetType.REGION, 1L, now, nextWeek,
                ContentAssignment.Status.CONFIRMED);
        setId(candidate, 500L);
        when(assignmentRepository.findById(1L)).thenReturn(Optional.of(newDraft));
        when(assignmentRepository.findOverlappingExcluding(eq(TargetType.REGION), eq(1L), any(), any(), eq(1L)))
                .thenReturn(List.of(candidate));
        when(deviceRepository.findByRegionIdAndDeletedAtIsNull(1L)).thenReturn(List.of(
                deviceWithId(10L), deviceWithId(20L), deviceWithId(30L), deviceWithId(40L)));
        when(exclusionRepository.findDeviceIdsByAssignmentId(500L)).thenReturn(List.of());

        var ex = assertThrows(AssignmentTimeOverlapException.class, () ->
                service.confirmWithExclusions(1L, List.of(20L, 40L), null, false));

        assertEquals(List.of(10L, 30L), ex.getConflicts().get(0).conflictingDeviceIds(),
                "intersection is reported in target order (LinkedHashSet), not arbitrary");
    }

    @Test
    void confirm_newExcludesEntireTarget_neverConflicts() {
        var playlist = new Playlist(new Project("P", null), "PL", null);
        var newDraft = new ContentAssignment(playlist, TargetType.REGION, 1L, now, nextWeek,
                ContentAssignment.Status.DRAFT);
        var whole = new ContentAssignment(playlist, TargetType.REGION, 1L, now, nextWeek,
                ContentAssignment.Status.CONFIRMED);
        when(assignmentRepository.findById(1L)).thenReturn(Optional.of(newDraft));
        when(assignmentRepository.findOverlappingExcluding(eq(TargetType.REGION), eq(1L), any(), any(), eq(1L)))
                .thenReturn(List.of(whole));
        when(deviceRepository.findByRegionIdAndDeletedAtIsNull(1L)).thenReturn(List.of(
                deviceWithId(10L), deviceWithId(20L)));
        when(deviceRepository.findAllById(java.util.List.of(10L, 20L))).thenReturn(List.of(
                deviceWithId(10L), deviceWithId(20L)));

        var result = service.confirmWithExclusions(1L, List.of(10L, 20L), null, false);

        assertEquals(ContentAssignment.Status.CONFIRMED, result.getStatus(),
                "new assignment that drives zero devices cannot conflict");
    }

    @Test
    void confirm_emptyTarget_noDevices_noConflict() {
        var playlist = new Playlist(new Project("P", null), "PL", null);
        var newDraft = new ContentAssignment(playlist, TargetType.REGION, 1L, now, nextWeek,
                ContentAssignment.Status.DRAFT);
        var candidate = new ContentAssignment(playlist, TargetType.REGION, 1L, now, nextWeek,
                ContentAssignment.Status.CONFIRMED);
        when(assignmentRepository.findById(1L)).thenReturn(Optional.of(newDraft));
        when(assignmentRepository.findOverlappingExcluding(eq(TargetType.REGION), eq(1L), any(), any(), eq(1L)))
                .thenReturn(List.of(candidate));
        // setUp default: region 1 has no devices → no intersection possible.

        var result = service.confirmWithExclusions(1L, List.of(), null, false);

        assertEquals(ContentAssignment.Status.CONFIRMED, result.getStatus(),
                "an unprovisioned (device-less) target cannot double-book any device");
    }

    @Test
    void confirm_deviceGroupTarget_deviceAware() {
        // Exercise the non-REGION branch of listDeviceIdsForTarget in the device-aware path.
        var playlist = new Playlist(new Project("P", null), "PL", null);
        var newDraft = new ContentAssignment(playlist, TargetType.DEVICE_GROUP, 5L, now, nextWeek,
                ContentAssignment.Status.DRAFT);
        var candidate = new ContentAssignment(playlist, TargetType.DEVICE_GROUP, 5L, now, nextWeek,
                ContentAssignment.Status.CONFIRMED);
        when(assignmentRepository.findById(1L)).thenReturn(Optional.of(newDraft));
        when(assignmentRepository.findOverlappingExcluding(eq(TargetType.DEVICE_GROUP), eq(5L), any(), any(), eq(1L)))
                .thenReturn(List.of(candidate));
        when(deviceRepository.findByDeviceGroupIdAndDeletedAtIsNull(5L)).thenReturn(List.of(deviceWithId(70L)));

        var ex = assertThrows(AssignmentTimeOverlapException.class, () ->
                service.confirmWithExclusions(1L, List.of(), null, false));

        assertEquals(List.of(70L), ex.getConflicts().get(0).conflictingDeviceIds());
    }

    @Test
    void confirmWithIncludedDevices_disjointInclusion_noConflict() {
        // Inclusion mode delegates to confirmWithExclusions(derivedExclusions); the device-aware
        // check must see the DERIVED effective set. Region [10,20,30,40]; include [30] → new covers
        // [30]; existing candidate covers [10,20] (excludes [30,40]) → disjoint → no 409.
        var playlist = new Playlist(new Project("P", null), "PL", null);
        var newDraft = new ContentAssignment(playlist, TargetType.REGION, 1L, now, nextWeek,
                ContentAssignment.Status.DRAFT);
        var candidate = new ContentAssignment(playlist, TargetType.REGION, 1L, now, nextWeek,
                ContentAssignment.Status.CONFIRMED);
        when(assignmentRepository.findById(1L)).thenReturn(Optional.of(newDraft));
        when(assignmentRepository.findOverlappingExcluding(eq(TargetType.REGION), eq(1L), any(), any(), eq(1L)))
                .thenReturn(List.of(candidate));
        when(deviceRepository.findByRegionIdAndDeletedAtIsNull(1L)).thenReturn(List.of(
                deviceWithId(10L), deviceWithId(20L), deviceWithId(30L), deviceWithId(40L)));
        when(deviceRepository.findAllById(java.util.List.of(10L, 20L, 40L))).thenReturn(List.of(
                deviceWithId(10L), deviceWithId(20L), deviceWithId(40L)));
        when(exclusionRepository.findDeviceIdsByAssignmentId(null)).thenReturn(List.of(30L, 40L));

        var result = service.confirmWithIncludedDevices(1L, List.of(30L), null, false);

        assertEquals(ContentAssignment.Status.CONFIRMED, result.getStatus(),
                "including a disjoint subset must not 409 against an existing disjoint assignment");
    }

    // ----- Preview -----

    @Test
    void previewForTarget_zeroDevices_returnsEmptyResult() {
        when(deviceRepository.countByRegionIdAndDeletedAtIsNull(1L)).thenReturn(0L);
        when(deviceRepository.findByRegionIdAndDeletedAtIsNull(1L)).thenReturn(List.of());
        when(assignmentRepository.findActiveAtTime(any())).thenReturn(List.of());
        when(exclusionRepository.findAll()).thenReturn(List.of());

        var result = service.previewForTarget(TargetType.REGION, 1L, now);

        assertEquals(0, result.totalDevices());
        assertEquals(0, result.returnedCount());
        assertEquals(false, result.truncated());
        assertEquals(true, result.devices().isEmpty());
    }

    @Test
    void previewForTarget_under200Devices_returnsAll() {
        when(deviceRepository.countByRegionIdAndDeletedAtIsNull(1L)).thenReturn(3L);
        var d1 = mockDeviceWithStatus(1L, "SN-1", Device.Status.ONLINE);
        var d2 = mockDeviceWithStatus(2L, "SN-2", Device.Status.OFFLINE);
        var d3 = mockDeviceWithStatus(3L, "SN-3", Device.Status.NO_CONTENT);
        when(deviceRepository.findByRegionIdAndDeletedAtIsNull(1L)).thenReturn(List.of(d1, d2, d3));
        when(assignmentRepository.findActiveAtTime(any())).thenReturn(List.of());
        when(exclusionRepository.findAll()).thenReturn(List.of());

        var result = service.previewForTarget(TargetType.REGION, 1L, now);

        assertEquals(3, result.totalDevices());
        assertEquals(3, result.returnedCount());
        assertEquals(false, result.truncated());
    }

    @Test
    void previewForTarget_offlineDevices_flagged() {
        when(deviceRepository.countByRegionIdAndDeletedAtIsNull(1L)).thenReturn(2L);
        // The offline flag derives from the heartbeat (DeviceStatusEvaluator), NOT the raw
        // status column: a recent heartbeat ⇒ not offline; a null heartbeat ⇒ offline.
        var online = mockDeviceWithStatus(1L, "SN-ON", Device.Status.ONLINE);
        when(online.getLastHeartbeatAt()).thenReturn(now);
        var offline = mockDeviceWithStatus(2L, "SN-OFF", Device.Status.OFFLINE);
        when(offline.getLastHeartbeatAt()).thenReturn(null); // never heartbeated
        when(deviceRepository.findByRegionIdAndDeletedAtIsNull(1L)).thenReturn(List.of(online, offline));
        when(assignmentRepository.findActiveAtTime(any())).thenReturn(List.of());
        when(exclusionRepository.findAll()).thenReturn(List.of());

        var result = service.previewForTarget(TargetType.REGION, 1L, now);

        var offlineItem = result.devices().stream()
                .filter(d -> d.deviceId() == 2L).findFirst().orElseThrow();
        assertEquals(true, offlineItem.offline());
        assertEquals("OFFLINE", offlineItem.status());
        var onlineItem = result.devices().stream()
                .filter(d -> d.deviceId() == 1L).findFirst().orElseThrow();
        assertEquals(false, onlineItem.offline());
        // recent heartbeat but no active assignment in scope ⇒ NO_CONTENT (still not offline).
        assertEquals("NO_CONTENT", onlineItem.status());
    }

    @Test
    void previewForTarget_recentHeartbeatWithActiveAssignment_isOnline() {
        var device = mockDeviceWithStatus(10L, "SN", Device.Status.OFFLINE); // raw column is stale
        when(device.getLastHeartbeatAt()).thenReturn(now);
        var region = mock(Region.class);
        when(region.getId()).thenReturn(1L);
        when(device.getRegion()).thenReturn(region);

        var playlist = mock(Playlist.class);
        when(playlist.getId()).thenReturn(99L);
        var existing = new ContentAssignment(playlist, TargetType.REGION, 1L, now, tomorrow);

        when(deviceRepository.countByRegionIdAndDeletedAtIsNull(1L)).thenReturn(1L);
        when(deviceRepository.findByRegionIdAndDeletedAtIsNull(1L)).thenReturn(List.of(device));
        when(assignmentRepository.findActiveAtTime(any())).thenReturn(List.of(existing));
        when(exclusionRepository.findAll()).thenReturn(List.of());

        var item = service.previewForTarget(TargetType.REGION, 1L, now).devices().getFirst();

        assertEquals("ONLINE", item.status(), "recent heartbeat + active assignment ⇒ ONLINE");
        assertEquals(false, item.offline());
    }

    @Test
    void previewForTarget_over200Devices_capsAndFlagsTruncated() {
        long total = 547L;
        var devices = new java.util.ArrayList<Device>();
        for (long i = 1; i <= total; i++) {
            devices.add(mockDeviceWithStatus(i, "SN-" + i, Device.Status.ONLINE));
        }
        when(deviceRepository.countByRegionIdAndDeletedAtIsNull(1L)).thenReturn(total);
        when(deviceRepository.findByRegionIdAndDeletedAtIsNull(1L)).thenReturn(devices);
        when(assignmentRepository.findActiveAtTime(any())).thenReturn(List.of());
        when(exclusionRepository.findAll()).thenReturn(List.of());

        var result = service.previewForTarget(TargetType.REGION, 1L, now);

        assertEquals(547L, result.totalDevices());
        assertEquals(200, result.returnedCount());
        assertEquals(true, result.truncated());
        assertEquals(200, result.devices().size());
    }

    @Test
    void previewForTarget_facility_usesFacilityRepoMethod() {
        var device = mockDeviceWithStatus(1L, "SN-F", Device.Status.ONLINE);
        when(deviceRepository.countByFacilityIdAndDeletedAtIsNull(7L)).thenReturn(1L);
        when(deviceRepository.findByFacilityIdAndDeletedAtIsNull(7L)).thenReturn(List.of(device));
        when(assignmentRepository.findActiveAtTime(any())).thenReturn(List.of());
        when(exclusionRepository.findAll()).thenReturn(List.of());

        var result = service.previewForTarget(TargetType.FACILITY, 7L, now);

        assertEquals(1, result.totalDevices());
    }

    @Test
    void previewForTarget_currentAssignment_includedInItem() {
        var device = mockDeviceWithStatus(10L, "SN", Device.Status.ONLINE);
        var region = mock(Region.class);
        when(region.getId()).thenReturn(1L);
        when(device.getRegion()).thenReturn(region);

        var playlist = mock(Playlist.class);
        when(playlist.getId()).thenReturn(99L);
        var existing = new ContentAssignment(playlist, TargetType.REGION, 1L, now, tomorrow);

        when(deviceRepository.countByRegionIdAndDeletedAtIsNull(1L)).thenReturn(1L);
        when(deviceRepository.findByRegionIdAndDeletedAtIsNull(1L)).thenReturn(List.of(device));
        when(assignmentRepository.findActiveAtTime(any())).thenReturn(List.of(existing));
        when(exclusionRepository.findAll()).thenReturn(List.of());

        var result = service.previewForTarget(TargetType.REGION, 1L, now);

        assertEquals(99L, result.devices().getFirst().currentPlaylistId());
    }

    private Device mockDeviceWithStatus(Long id, String serial, Device.Status status) {
        var d = mock(Device.class);
        when(d.getId()).thenReturn(id);
        when(d.getSerialNumber()).thenReturn(serial);
        when(d.getName()).thenReturn("Device-" + id);
        when(d.getStatus()).thenReturn(status);
        return d;
    }

    @Test
    void cleanExpiredDrafts_softDeletesEachExpiredDraft() {
        var d1 = new ContentAssignment(new Playlist(new Project("P", null), "PL", null),
                TargetType.REGION, 1L, now, tomorrow, ContentAssignment.Status.DRAFT);
        var d2 = new ContentAssignment(new Playlist(new Project("P", null), "PL", null),
                TargetType.REGION, 2L, now, tomorrow, ContentAssignment.Status.DRAFT);
        var threshold = Instant.now();
        when(assignmentRepository.findExpiredDrafts(threshold)).thenReturn(List.of(d1, d2));

        int count = service.cleanExpiredDrafts(threshold);

        assertEquals(2, count);
        assertEquals(true, d1.isDeleted());
        assertEquals(true, d2.isDeleted());
    }

    // ===== GUARDRAIL: status-free resolution (item 1) =====

    /**
     * resolveForDevice intentionally never reads Device.Status. Offline TV-Boxes
     * must still receive their assigned content the moment they reconnect — the
     * heartbeat / sync poll path resolves the same way it would for an online
     * device. A future refactor that adds an online-only filter (e.g. "skip
     * devices whose status is OFFLINE" or "skip if lastHeartbeatAt is stale")
     * would silently break the offline-rollout contract. This test pins the
     * behaviour: explicitly OFFLINE + stale heartbeat still resolves.
     */
    @Test
    void resolveForDevice_offlineDeviceWithStaleHeartbeat_stillResolvesAssignment() {
        var device = mockDevice(10L, null, null);
        when(device.getStatus()).thenReturn(Device.Status.OFFLINE);
        when(device.getLastHeartbeatAt()).thenReturn(now.minus(24, ChronoUnit.HOURS));

        var assignment = mockAssignment(99L, TargetType.REGION, 10L, 1);
        when(assignmentRepository.findActiveAtTime(any())).thenReturn(List.of(assignment));
        when(exclusionRepository.findByDeviceId(any())).thenReturn(List.of());

        var result = service.resolveForDevice(device, now);

        assertNotNull(result, "OFFLINE device with stale heartbeat must still resolve a CONFIRMED in-window assignment");
        assertEquals(99L, result.getId());
    }

    // ===== Item 2: instant-push event publication =====

    @Test
    void confirmWithExclusions_publishesEvent_withInScopeMinusExcludedIds() {
        var playlist = new Playlist(new Project("P", null), "PL", null);
        var assignment = new ContentAssignment(playlist, TargetType.REGION, 7L, now, tomorrow,
                ContentAssignment.Status.DRAFT);
        when(assignmentRepository.findById(7L)).thenReturn(Optional.of(assignment));
        when(assignmentRepository.findOverlappingExcluding(any(), anyLong(), any(), any(), eq(7L)))
                .thenReturn(List.of());

        // Target region 7L has devices [10, 20, 30]; we exclude 20 — event must carry [10, 30].
        var d10 = mock(Device.class); when(d10.getId()).thenReturn(10L);
        var d20 = mock(Device.class); when(d20.getId()).thenReturn(20L);
        var d30 = mock(Device.class); when(d30.getId()).thenReturn(30L);
        when(deviceRepository.findByRegionIdAndDeletedAtIsNull(7L)).thenReturn(List.of(d10, d20, d30));
        when(deviceRepository.findAllById(List.of(20L))).thenReturn(List.of(d20));

        service.confirmWithExclusions(7L, List.of(20L), "ops");

        var captor = ArgumentCaptor.forClass(AssignmentConfirmedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        var event = captor.getValue();
        assertEquals(7L, event.assignmentId());
        assertEquals(List.of(10L, 30L), event.deviceIds());
    }

    /**
     * Negative for the rollback path: if the overlap re-check throws inside
     * the transactional method, the publish point is never reached so no event
     * fires. Pairs with Spring's {@code @TransactionalEventListener(AFTER_COMMIT)}
     * contract on the listener side, which would suppress the push even if a
     * publish slipped through.
     */
    @Test
    void confirmWithExclusions_rollbackOnOverlap_publishesNoEvent() {
        var playlist = new Playlist(new Project("P", null), "PL", null);
        var draft = new ContentAssignment(playlist, TargetType.REGION, 8L, now, tomorrow,
                ContentAssignment.Status.DRAFT);
        var conflicting = new ContentAssignment(playlist, TargetType.REGION, 8L, now, tomorrow,
                ContentAssignment.Status.CONFIRMED);
        when(assignmentRepository.findById(8L)).thenReturn(Optional.of(draft));
        when(assignmentRepository.findOverlappingExcluding(any(), anyLong(), any(), any(), eq(8L)))
                .thenReturn(List.of(conflicting));
        when(deviceRepository.findByRegionIdAndDeletedAtIsNull(8L)).thenReturn(List.of(deviceWithId(80L)));

        assertThrows(IllegalStateException.class, () ->
                service.confirmWithExclusions(8L, List.of(), "ops"));

        verifyNoInteractions(eventPublisher);
    }

    /**
     * Cap negative path: a target larger than the push cap is truncated. The
     * remainder is unharmed — those devices pick up content via their next
     * heartbeat poll (same fallback that covers offline + WS-disconnected).
     */
    @Test
    void confirmWithExclusions_targetExceedsPushCap_truncatesEventPayload() throws Exception {
        // Set the cap explicitly so the test does not depend on the constant.
        Field cap = ContentAssignmentService.class.getDeclaredField("pushOnConfirmCap");
        cap.setAccessible(true);
        cap.setInt(service, 100);

        var playlist = new Playlist(new Project("P", null), "PL", null);
        var assignment = new ContentAssignment(playlist, TargetType.REGION, 9L, now, tomorrow,
                ContentAssignment.Status.DRAFT);
        when(assignmentRepository.findById(9L)).thenReturn(Optional.of(assignment));
        when(assignmentRepository.findOverlappingExcluding(any(), anyLong(), any(), any(), eq(9L)))
                .thenReturn(List.of());

        // 150 devices in the target scope; cap is 100 → event carries first 100.
        var devices = IntStream.rangeClosed(1, 150).mapToObj(i -> {
            var d = mock(Device.class);
            when(d.getId()).thenReturn((long) i);
            return d;
        }).toList();
        when(deviceRepository.findByRegionIdAndDeletedAtIsNull(9L)).thenReturn(devices);

        service.confirmWithExclusions(9L, List.of(), "ops");

        var captor = ArgumentCaptor.forClass(AssignmentConfirmedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertEquals(100, captor.getValue().deviceIds().size());
        assertEquals(1L, captor.getValue().deviceIds().get(0));
        assertEquals(100L, captor.getValue().deviceIds().get(99));
    }

    // ===== Item 3: includedDeviceIds =====

    @Test
    void confirmWithIncludedDevices_translatesToCorrectExclusions() {
        var playlist = new Playlist(new Project("P", null), "PL", null);
        var assignment = new ContentAssignment(playlist, TargetType.REGION, 11L, now, tomorrow,
                ContentAssignment.Status.DRAFT);
        when(assignmentRepository.findById(11L)).thenReturn(Optional.of(assignment));
        when(assignmentRepository.findOverlappingExcluding(any(), anyLong(), any(), any(), eq(11L)))
                .thenReturn(List.of());

        // 5 devices in scope; include just [10, 30] → expect exclusions [20, 40, 50].
        var ids = List.of(10L, 20L, 30L, 40L, 50L);
        var devices = ids.stream().map(id -> {
            var d = mock(Device.class);
            when(d.getId()).thenReturn(id);
            return d;
        }).toList();
        when(deviceRepository.findByRegionIdAndDeletedAtIsNull(11L)).thenReturn(devices);
        // findAllById is called for the derived exclusion set during inclusion translation.
        when(deviceRepository.findAllById(any())).thenAnswer(inv -> {
            var requested = (Iterable<Long>) inv.getArgument(0);
            return devices.stream().filter(d -> {
                for (Long r : requested) if (r.equals(d.getId())) return true;
                return false;
            }).toList();
        });

        service.confirmWithIncludedDevices(11L, List.of(10L, 30L), "subset");

        // Event payload carries [10, 30] (the inclusion set) — the rest are excluded.
        var captor = ArgumentCaptor.forClass(AssignmentConfirmedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertEquals(List.of(10L, 30L), captor.getValue().deviceIds());

        // The exclusion rows persisted are the COMPLEMENT [20, 40, 50].
        var exclCaptor = ArgumentCaptor.forClass(ContentAssignmentExclusion.class);
        verify(exclusionRepository, times(3)).save(exclCaptor.capture());
        var excludedIds = exclCaptor.getAllValues().stream()
                .map(e -> e.getDevice().getId())
                .toList();
        assertTrue(excludedIds.containsAll(List.of(20L, 40L, 50L)));
        assertFalse(excludedIds.contains(10L));
        assertFalse(excludedIds.contains(30L));
    }

    // ===== Item 1 helper: resolveDeviceIdsForActivePlaylist =====

    @Test
    void resolveDeviceIdsForActivePlaylist_unionsAcrossTargetTypes_andDeduplicates() {
        // Two active assignments on playlist 77L: one REGION (devices [10,20]), one
        // DEVICE_GROUP (devices [20,30]). Union must be [10,20,30] with 20 deduped.
        var regionAssignment = mockAssignment(101L, TargetType.REGION, 7L, 1);
        var groupAssignment = mockAssignment(102L, TargetType.DEVICE_GROUP, 8L, 3);
        when(assignmentRepository.findActiveByPlaylistId(eq(77L), any()))
                .thenReturn(List.of(regionAssignment, groupAssignment));

        var d10 = mock(Device.class); when(d10.getId()).thenReturn(10L);
        var d20 = mock(Device.class); when(d20.getId()).thenReturn(20L);
        var d30 = mock(Device.class); when(d30.getId()).thenReturn(30L);
        when(deviceRepository.findByRegionIdAndDeletedAtIsNull(7L)).thenReturn(List.of(d10, d20));
        when(deviceRepository.findByDeviceGroupIdAndDeletedAtIsNull(8L)).thenReturn(List.of(d20, d30));

        var ids = service.resolveDeviceIdsForActivePlaylist(77L, now);

        assertEquals(3, ids.size());
        assertTrue(ids.contains(10L));
        assertTrue(ids.contains(20L));
        assertTrue(ids.contains(30L));
    }

    @Test
    void resolveDeviceIdsForActivePlaylist_noActiveAssignments_returnsEmpty() {
        when(assignmentRepository.findActiveByPlaylistId(eq(78L), any())).thenReturn(List.of());

        var ids = service.resolveDeviceIdsForActivePlaylist(78L, now);

        assertEquals(0, ids.size());
    }

    @Test
    void resolveDeviceIdsForActivePlaylist_unionExceedsCap_truncatesAndWarns() throws Exception {
        Field cap = ContentAssignmentService.class.getDeclaredField("pushOnConfirmCap");
        cap.setAccessible(true);
        cap.setInt(service, 50);

        var assignment = mockAssignment(103L, TargetType.REGION, 9L, 1);
        when(assignmentRepository.findActiveByPlaylistId(eq(79L), any()))
                .thenReturn(List.of(assignment));
        var devices = IntStream.rangeClosed(1, 75).mapToObj(i -> {
            var d = mock(Device.class);
            when(d.getId()).thenReturn((long) i);
            return d;
        }).toList();
        when(deviceRepository.findByRegionIdAndDeletedAtIsNull(9L)).thenReturn(devices);

        var ids = service.resolveDeviceIdsForActivePlaylist(79L, now);

        assertEquals(50, ids.size(), "Cap = 50 must truncate the 75-device union");
        assertEquals(1L, ids.get(0));
        assertEquals(50L, ids.get(49));
    }

    @Test
    void confirmWithIncludedDevices_outOfScopeId_throwsIllegalArgument() {
        var playlist = new Playlist(new Project("P", null), "PL", null);
        var assignment = new ContentAssignment(playlist, TargetType.REGION, 12L, now, tomorrow,
                ContentAssignment.Status.DRAFT);
        when(assignmentRepository.findById(12L)).thenReturn(Optional.of(assignment));

        var d10 = mock(Device.class); when(d10.getId()).thenReturn(10L);
        when(deviceRepository.findByRegionIdAndDeletedAtIsNull(12L)).thenReturn(List.of(d10));

        var ex = assertThrows(IllegalArgumentException.class, () ->
                service.confirmWithIncludedDevices(12L, List.of(999L), "subset"));
        assertTrue(ex.getMessage().contains("outside the assignment target"),
                "message should name the out-of-scope condition: " + ex.getMessage());
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ===== §1: empty-playlist guard (cannot push "nothing to play") =====

    @Test
    void confirmWithExclusions_emptyPlaylist_throwsConflict_andDoesNotConfirm() {
        var playlist = mock(Playlist.class);
        when(playlist.getId()).thenReturn(500L);
        var draft = new ContentAssignment(playlist, TargetType.REGION, 1L, now, tomorrow,
                ContentAssignment.Status.DRAFT);
        when(assignmentRepository.findById(1L)).thenReturn(Optional.of(draft));
        when(assignmentRepository.findOverlappingExcluding(any(), anyLong(), any(), any(), eq(1L)))
                .thenReturn(List.of());
        when(playlistItemRepository.countByPlaylistId(500L)).thenReturn(0L);

        var ex = assertThrows(IllegalStateException.class, () ->
                service.confirmWithExclusions(1L, List.of(), null));
        assertTrue(ex.getMessage().contains("has no items"), ex.getMessage());
        assertEquals(ContentAssignment.Status.DRAFT, draft.getStatus(), "must NOT flip to CONFIRMED");
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void createDraft_emptyPlaylist_throwsConflict_andDoesNotSave() {
        var playlist = mock(Playlist.class);
        when(playlist.getId()).thenReturn(600L);
        when(playlistItemRepository.countByPlaylistId(600L)).thenReturn(0L);

        var ex = assertThrows(IllegalStateException.class, () ->
                service.createDraft(playlist, TargetType.REGION, 1L, now, tomorrow));
        assertTrue(ex.getMessage().contains("has no items"), ex.getMessage());
        verify(assignmentRepository, never()).save(any());
    }

    // ===== §5: a soft-deleted DRAFT must not be confirmable =====

    @Test
    void confirmWithExclusions_softDeletedDraft_throwsNotFound_andDoesNotConfirm() {
        var playlist = new Playlist(new Project("P", null), "PL", null);
        var draft = new ContentAssignment(playlist, TargetType.REGION, 1L, now, tomorrow,
                ContentAssignment.Status.DRAFT);
        draft.softDelete(); // e.g. auto-cleaned by DraftAssignmentCleaner after its 1h TTL
        when(assignmentRepository.findById(1L)).thenReturn(Optional.of(draft));

        assertThrows(ResourceNotFoundException.class, () ->
                service.confirmWithExclusions(1L, List.of(), null));

        assertEquals(ContentAssignment.Status.DRAFT, draft.getStatus(), "must NOT flip to CONFIRMED");
        assertTrue(draft.isDeleted(), "remains soft-deleted — no CONFIRMED-but-deleted row");
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ===== §3: cancel (softDelete) + instant re-resolve push =====

    @Test
    void softDelete_confirmedAssignment_publishesCancelledEvent_withTargetDeviceIds() {
        var playlist = new Playlist(new Project("P", null), "PL", null);
        var assignment = new ContentAssignment(playlist, TargetType.REGION, 7L, now, tomorrow,
                ContentAssignment.Status.CONFIRMED);
        when(assignmentRepository.findById(7L)).thenReturn(Optional.of(assignment));
        var d10 = mock(Device.class); when(d10.getId()).thenReturn(10L);
        var d20 = mock(Device.class); when(d20.getId()).thenReturn(20L);
        when(deviceRepository.findByRegionIdAndDeletedAtIsNull(7L)).thenReturn(List.of(d10, d20));

        service.softDelete(7L);

        assertTrue(assignment.isDeleted());
        var captor = ArgumentCaptor.forClass(AssignmentCancelledEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertEquals(7L, captor.getValue().assignmentId());
        assertEquals(List.of(10L, 20L), captor.getValue().deviceIds());
    }

    @Test
    void softDelete_draftAssignment_softDeletes_withoutPush() {
        var playlist = new Playlist(new Project("P", null), "PL", null);
        var draft = new ContentAssignment(playlist, TargetType.REGION, 7L, now, tomorrow,
                ContentAssignment.Status.DRAFT);
        when(assignmentRepository.findById(7L)).thenReturn(Optional.of(draft));

        service.softDelete(7L);

        assertTrue(draft.isDeleted());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void softDelete_unknownId_throwsNotFound_noEvent() {
        when(assignmentRepository.findById(404L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.softDelete(404L));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void softDelete_alreadyCancelled_throwsNotFound_noDuplicateEvent() {
        var playlist = new Playlist(new Project("P", null), "PL", null);
        var assignment = new ContentAssignment(playlist, TargetType.REGION, 7L, now, tomorrow,
                ContentAssignment.Status.CONFIRMED);
        assignment.softDelete();
        when(assignmentRepository.findById(7L)).thenReturn(Optional.of(assignment));

        assertThrows(ResourceNotFoundException.class, () -> service.softDelete(7L));
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ===== Restricted-scope DEVICE_GROUP target (the g.getProject() scope hop) =====
    // assertAssignmentInScope / previewForTarget resolve a DEVICE_GROUP target's project via
    // deviceGroupRepository.findById(targetId).getProject().getId() (NOT getRegion().getProject()
    // anymore — groups are now a direct child of Project). These pin that scope path: the playlist's
    // project is IN scope so the guard reaches the target-project check, and the group's project is
    // the discriminator.

    /** A DeviceGroup mock whose getProject().getId() is {@code projectId}. */
    private DeviceGroup groupInProject(Long projectId) {
        var group = mock(DeviceGroup.class);
        var project = mock(Project.class);
        when(project.getId()).thenReturn(projectId);
        when(group.getProject()).thenReturn(project);
        return group;
    }

    /** A Playlist mock whose getProject().getId() is {@code projectId}. */
    private Playlist playlistInProject(Long projectId) {
        var playlist = mock(Playlist.class);
        var project = mock(Project.class);
        when(project.getId()).thenReturn(projectId);
        when(playlist.getProject()).thenReturn(project);
        return playlist;
    }

    @Test
    void createDraft_deviceGroupTarget_outOfScopeProject_throwsNotFound() {
        // Restricted operator scoped to project 100. Playlist lives in 100 (in scope), but the target
        // group's project is 200 (out of scope) → assertAssignmentInScope must 404 on the group hop.
        when(operatorScopeResolver.resolve())
                .thenReturn(new ScopedProjects("op", null, List.of(100L), true));
        var group = groupInProject(200L);
        when(deviceGroupRepository.findById(5L)).thenReturn(Optional.of(group));

        var playlist = playlistInProject(100L);

        assertThrows(ResourceNotFoundException.class, () ->
                service.createDraft(playlist, TargetType.DEVICE_GROUP, 5L, now, tomorrow));
        verify(assignmentRepository, never()).save(any());
    }

    @Test
    void previewForTarget_deviceGroupTarget_outOfScopeProject_throwsNotFound() {
        when(operatorScopeResolver.resolve())
                .thenReturn(new ScopedProjects("op", null, List.of(100L), true));
        var group = groupInProject(200L);
        when(deviceGroupRepository.findById(5L)).thenReturn(Optional.of(group));

        assertThrows(ResourceNotFoundException.class, () ->
                service.previewForTarget(TargetType.DEVICE_GROUP, 5L, now));
    }

    @Test
    void createDraft_deviceGroupTarget_inScopeProject_succeeds() {
        // Positive twin: group's project (100) IS in the restricted operator's scope → no 404.
        when(operatorScopeResolver.resolve())
                .thenReturn(new ScopedProjects("op", null, List.of(100L), true));
        var group = groupInProject(100L);
        when(deviceGroupRepository.findById(5L)).thenReturn(Optional.of(group));
        when(assignmentRepository.save(any(ContentAssignment.class))).thenAnswer(inv -> inv.getArgument(0));

        var playlist = playlistInProject(100L);
        var result = service.createDraft(playlist, TargetType.DEVICE_GROUP, 5L, now, tomorrow);

        assertEquals(ContentAssignment.Status.DRAFT, result.getStatus());
    }

    @Test
    void previewForTarget_deviceGroupTarget_inScopeProject_succeeds() {
        when(operatorScopeResolver.resolve())
                .thenReturn(new ScopedProjects("op", null, List.of(100L), true));
        var group = groupInProject(100L);
        when(deviceGroupRepository.findById(5L)).thenReturn(Optional.of(group));
        // setUp default: group 5 has no devices → empty preview, no NPE.
        when(assignmentRepository.findActiveAtTime(any())).thenReturn(List.of());
        when(exclusionRepository.findAll()).thenReturn(List.of());

        var result = service.previewForTarget(TargetType.DEVICE_GROUP, 5L, now);

        assertEquals(0, result.totalDevices());
        assertTrue(result.devices().isEmpty());
    }
}
