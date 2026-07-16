package uz.orientadvertise.services.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.DeviceGroup;
import uz.orientadvertise.services.domain.model.DeviceStatusView;
import uz.orientadvertise.services.domain.model.DeviceVolumeResolver;
import uz.orientadvertise.services.domain.model.Event;
import uz.orientadvertise.services.domain.model.Facility;
import uz.orientadvertise.services.domain.model.Incident;
import uz.orientadvertise.services.domain.model.Project;
import uz.orientadvertise.services.domain.model.Region;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.DeviceStatusViewRepository;
import uz.orientadvertise.services.domain.repository.FacilityRepository;
import uz.orientadvertise.services.domain.repository.IncidentRepository;
import uz.orientadvertise.services.domain.repository.RegionRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DeviceManagementServiceTest {

    private DeviceRepository deviceRepository;
    private DeviceStatusViewRepository statusViewRepository;
    private RegionRepository regionRepository;
    private FacilityRepository facilityRepository;
    private IncidentRepository incidentRepository;
    private OperatorScopeResolver operatorScopeResolver;
    private DeviceManagementService service;

    @BeforeEach
    void setUp() {
        deviceRepository = mock(DeviceRepository.class);
        statusViewRepository = mock(DeviceStatusViewRepository.class);
        regionRepository = mock(RegionRepository.class);
        facilityRepository = mock(FacilityRepository.class);
        incidentRepository = mock(IncidentRepository.class);
        operatorScopeResolver = mock(OperatorScopeResolver.class);
        when(operatorScopeResolver.resolve())
                .thenReturn(new OperatorScopeResolver.ScopedProjects(null, null, null, false));
        service = new DeviceManagementService(deviceRepository, statusViewRepository,
                regionRepository, facilityRepository, incidentRepository, operatorScopeResolver);
    }

    @Test
    void list_unassignedTrueWithDeviceGroupId_throws400() {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> service.list(null, 1L, null, null, 5L, Boolean.TRUE,
                        null, null, null, null, null, org.springframework.data.domain.PageRequest.of(0, 20)));
        assertTrue(ex.getMessage().contains("mutually exclusive"),
                "Expected mutual-exclusivity error message; got: " + ex.getMessage());
    }

    @Test
    void list_unassignedTrueAlone_passesThroughToRepository() {
        var statusViewRepository = mock(DeviceStatusViewRepository.class);
        var s = new DeviceManagementService(deviceRepository, statusViewRepository,
                regionRepository, facilityRepository, incidentRepository, operatorScopeResolver);
        var pageable = org.springframework.data.domain.PageRequest.of(0, 20);
        when(statusViewRepository.findFiltered(any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        s.list(null, 1L, null, null, null, Boolean.TRUE, null, null, null, null, null, pageable);

        org.mockito.Mockito.verify(statusViewRepository).findFiltered(
                org.mockito.ArgumentMatchers.eq(null),
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(null),
                org.mockito.ArgumentMatchers.eq(null),
                org.mockito.ArgumentMatchers.eq(null),
                org.mockito.ArgumentMatchers.eq(Boolean.TRUE),
                org.mockito.ArgumentMatchers.eq(null),
                org.mockito.ArgumentMatchers.eq(null),
                org.mockito.ArgumentMatchers.eq(null),
                org.mockito.ArgumentMatchers.eq(null),   // hasActivePlaylist
                org.mockito.ArgumentMatchers.eq(null),   // syncUnassigned
                org.mockito.ArgumentMatchers.eq(null),   // projectIds
                org.mockito.ArgumentMatchers.eq(pageable));
    }

    @Test
    void list_hasActivePlaylist_passesThroughToRepository() {
        var statusViewRepository = mock(DeviceStatusViewRepository.class);
        var s = new DeviceManagementService(deviceRepository, statusViewRepository,
                regionRepository, facilityRepository, incidentRepository, operatorScopeResolver);
        var pageable = org.springframework.data.domain.PageRequest.of(0, 20);
        when(statusViewRepository.findFiltered(any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        s.list(null, null, null, null, null, null, null, null, null, Boolean.TRUE, null, pageable);

        // hasActivePlaylist is the 10th positional arg; syncUnassigned (11th) then projectIds/pageable.
        org.mockito.Mockito.verify(statusViewRepository).findFiltered(
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.eq(Boolean.TRUE),   // hasActivePlaylist
                org.mockito.ArgumentMatchers.eq(null),           // syncUnassigned
                org.mockito.ArgumentMatchers.eq(null),           // projectIds
                org.mockito.ArgumentMatchers.eq(pageable));
    }

    @Test
    void list_hasActivePlaylist_isOrthogonalToUnassignedAndDeviceGroup_noGuard() {
        var statusViewRepository = mock(DeviceStatusViewRepository.class);
        var s = new DeviceManagementService(deviceRepository, statusViewRepository,
                regionRepository, facilityRepository, incidentRepository, operatorScopeResolver);
        var pageable = org.springframework.data.domain.PageRequest.of(0, 20);
        when(statusViewRepository.findFiltered(any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        // hasActivePlaylist + unassigned together (no deviceGroupId) → no exception.
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                s.list(null, 1L, null, null, null, Boolean.TRUE, null, null, null, Boolean.TRUE, null, pageable));
        // hasActivePlaylist + deviceGroupId together (unassigned null) → no exception.
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                s.list(null, 1L, null, null, 5L, null, null, null, null, Boolean.FALSE, null, pageable));
    }

    @Test
    void setLocation_movesDeviceToNewRegionAndFacility() {
        var oldRegion = scopedRegion();
        var device = new Device(oldRegion, null, "SN-A", "TV-A");
        when(deviceRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(device));

        var newRegion = mock(Region.class);
        when(newRegion.getId()).thenReturn(5L);
        when(regionRepository.findById(5L)).thenReturn(Optional.of(newRegion));

        var facility = mock(Facility.class);
        when(facility.getRegion()).thenReturn(newRegion);
        when(facilityRepository.findById(9L)).thenReturn(Optional.of(facility));

        var result = service.setLocation(1L, 5L, 9L);

        assertSame(newRegion, result.getRegion());
        assertSame(facility, result.getFacility());
    }

    @Test
    void setLocation_nullFacility_clearsFacility() {
        var oldRegion = scopedRegion();
        var oldFacility = mock(Facility.class);
        var device = new Device(oldRegion, oldFacility, "SN-B", "TV-B");
        when(deviceRepository.findByIdAndDeletedAtIsNull(2L)).thenReturn(Optional.of(device));

        var newRegion = mock(Region.class);
        when(regionRepository.findById(5L)).thenReturn(Optional.of(newRegion));

        var result = service.setLocation(2L, 5L, null);

        assertSame(newRegion, result.getRegion());
        assertNull(result.getFacility());
    }

    @Test
    void setLocation_facilityFromDifferentRegion_throws400() {
        var oldRegion = scopedRegion();
        var device = new Device(oldRegion, null, "SN-C", "TV-C");
        when(deviceRepository.findByIdAndDeletedAtIsNull(3L)).thenReturn(Optional.of(device));

        var targetRegion = mock(Region.class);
        when(regionRepository.findById(5L)).thenReturn(Optional.of(targetRegion));

        var otherRegion = mock(Region.class);
        when(otherRegion.getId()).thenReturn(7L);
        var facility = mock(Facility.class);
        when(facility.getRegion()).thenReturn(otherRegion);
        when(facilityRepository.findById(99L)).thenReturn(Optional.of(facility));

        var ex = assertThrows(IllegalArgumentException.class,
                () -> service.setLocation(3L, 5L, 99L));
        assertTrue(ex.getMessage().contains("Facility 99 is not in region 5"),
                "Expected explicit fid/rid in message; got: " + ex.getMessage());
    }

    @Test
    void setLocation_deviceInGroupFromDifferentProject_throws409() {
        // The current group belongs to project 1; the target region belongs to project 2.
        var groupProject = mock(Project.class);
        when(groupProject.getId()).thenReturn(1L);
        var oldRegion = scopedRegion();
        var group = mock(DeviceGroup.class);
        when(group.getId()).thenReturn(42L);
        when(group.getProject()).thenReturn(groupProject);
        var device = new Device(oldRegion, null, "SN-D", "TV-D");
        device.setDeviceGroup(group);
        when(deviceRepository.findByIdAndDeletedAtIsNull(4L)).thenReturn(Optional.of(device));

        var targetProject = mock(Project.class);
        when(targetProject.getId()).thenReturn(2L);
        var targetRegion = mock(Region.class);
        when(targetRegion.getProject()).thenReturn(targetProject);
        when(regionRepository.findById(7L)).thenReturn(Optional.of(targetRegion));

        var ex = assertThrows(IllegalStateException.class,
                () -> service.setLocation(4L, 7L, null));
        var msg = ex.getMessage();
        assertTrue(msg.contains("Device 4") && msg.contains("device group 42")
                        && msg.contains("different project")
                        && msg.contains("remove from the group"),
                "Expected the spec'd 409 message; got: " + msg);
    }

    @Test
    void setLocation_deviceInGroupFromSameProject_allowed() {
        // Relaxed model: groups are project-scoped, so a cross-region move that stays inside
        // the SAME project must SUCCEED — the group's project and the target region's project
        // both resolve to id 100.
        var sameProject = mock(Project.class);
        when(sameProject.getId()).thenReturn(100L);
        var group = mock(DeviceGroup.class);
        when(group.getProject()).thenReturn(sameProject);
        var oldRegion = scopedRegion();
        var device = new Device(oldRegion, null, "SN-E", "TV-E");
        device.setDeviceGroup(group);
        when(deviceRepository.findByIdAndDeletedAtIsNull(5L)).thenReturn(Optional.of(device));

        var targetRegion = mock(Region.class);
        when(targetRegion.getProject()).thenReturn(sameProject);
        when(regionRepository.findById(7L)).thenReturn(Optional.of(targetRegion));

        var result = service.setLocation(5L, 7L, null);

        assertSame(targetRegion, result.getRegion());
    }

    @Test
    void setLocation_deviceMissingOrSoftDeleted_throws404() {
        when(deviceRepository.findByIdAndDeletedAtIsNull(404L)).thenReturn(Optional.empty());

        var ex = assertThrows(ResourceNotFoundException.class,
                () -> service.setLocation(404L, 1L, null));
        assertEquals("Device not found with id: 404", ex.getMessage());
    }

    @Test
    void setLocation_regionMissing_throws404() {
        var device = new Device(scopedRegion(), null, "SN-F", "TV-F");
        when(deviceRepository.findByIdAndDeletedAtIsNull(6L)).thenReturn(Optional.of(device));
        when(regionRepository.findById(999L)).thenReturn(Optional.empty());

        var ex = assertThrows(ResourceNotFoundException.class,
                () -> service.setLocation(6L, 999L, null));
        assertEquals("Region not found with id: 999", ex.getMessage());
    }

    @Test
    void setLocation_facilityMissing_throws404() {
        var device = new Device(scopedRegion(), null, "SN-G", "TV-G");
        when(deviceRepository.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(device));
        when(regionRepository.findById(5L)).thenReturn(Optional.of(mock(Region.class)));
        when(facilityRepository.findById(404L)).thenReturn(Optional.empty());

        var ex = assertThrows(ResourceNotFoundException.class,
                () -> service.setLocation(7L, 5L, 404L));
        assertEquals("Facility not found with id: 404", ex.getMessage());
    }

    // ===== Soft-delete reconciles incidents (a deleted device must stop reporting) =====

    @Test
    void softDelete_resolvesOpenIncidentsWithSystemResolver() {
        var device = new Device(mock(Region.class), null, "SN-DEL", "TV-DEL");
        when(deviceRepository.findByIdAndDeletedAtIsNull(8L)).thenReturn(Optional.of(device));

        var open = newIncident(device);
        var acknowledged = newIncident(device);
        acknowledged.acknowledge("operator");
        var manuallyResolved = newIncident(device);
        manuallyResolved.resolve("alice"); // manual close — must stay sealed, not re-resolved
        when(incidentRepository.findByDeviceIdOrderByUpdatedAtDesc(8L))
                .thenReturn(List.of(open, acknowledged, manuallyResolved));

        service.softDelete(8L);

        assertTrue(device.isDeleted(), "device must be soft-deleted");
        // Both still-open incidents (OPEN + ACKNOWLEDGED) are auto-resolved by the system.
        assertEquals(Incident.Status.RESOLVED, open.getStatus());
        assertEquals(Incident.Status.RESOLVED, acknowledged.getStatus());
        assertEquals(Incident.SYSTEM_RESOLVER, open.getResolvedBy());
        assertEquals(Incident.SYSTEM_RESOLVER, acknowledged.getResolvedBy());
        assertFalse(open.wasManuallyResolved(), "auto-close on delete must not read as manual");
        // The manually-resolved one is untouched — no re-resolve, no IllegalStateException.
        assertEquals("alice", manuallyResolved.getResolvedBy());
    }

    @Test
    void softDelete_noIncidents_softDeletesCleanly() {
        var device = new Device(mock(Region.class), null, "SN-CLEAN", "TV-CLEAN");
        when(deviceRepository.findByIdAndDeletedAtIsNull(9L)).thenReturn(Optional.of(device));
        when(incidentRepository.findByDeviceIdOrderByUpdatedAtDesc(9L)).thenReturn(List.of());

        service.softDelete(9L);

        assertTrue(device.isDeleted());
    }

    @Test
    void softDelete_deviceMissingOrAlreadyDeleted_throws404_andTouchesNoIncidents() {
        when(deviceRepository.findByIdAndDeletedAtIsNull(404L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.softDelete(404L));
        verify(incidentRepository, never()).findByDeviceIdOrderByUpdatedAtDesc(any());
    }

    /**
     * A Region mock whose project chain is navigable — {@code assertInScope} reads
     * {@code device.getRegion().getProject().getId()} eagerly (before the unrestricted
     * scope short-circuits), so the device's current region must expose a non-null project.
     * The id value is irrelevant under an unrestricted scope (excludes() returns false).
     */
    private static Region scopedRegion() {
        var region = mock(Region.class);
        var project = mock(Project.class);
        when(project.getId()).thenReturn(100L);
        when(region.getProject()).thenReturn(project);
        return region;
    }

    private static Incident newIncident(Device device) {
        var event = new Event(device, "DEVICE_OFFLINE", Event.Priority.CRITICAL, "{}", Instant.now());
        return new Incident(device, "DEVICE_OFFLINE", Event.Priority.CRITICAL, "auto", event);
    }

    // ===== Derived status lookups (single source of truth = device_status_view) =====

    @Test
    void computedStatus_readsComputedValueFromView() {
        var view = mock(DeviceStatusView.class);
        when(view.getComputedStatus()).thenReturn(Device.Status.OFFLINE);
        when(statusViewRepository.findById(7L)).thenReturn(Optional.of(view));

        assertEquals(Device.Status.OFFLINE, service.computedStatus(7L));
    }

    @Test
    void computedStatus_unknownOrDeleted_returnsNull() {
        when(statusViewRepository.findById(404L)).thenReturn(Optional.empty());

        assertNull(service.computedStatus(404L));
    }

    @Test
    void computedStatuses_mapsEachIdToItsComputedStatus() {
        var v1 = mock(DeviceStatusView.class);
        when(v1.getId()).thenReturn(1L);
        when(v1.getComputedStatus()).thenReturn(Device.Status.ONLINE);
        var v2 = mock(DeviceStatusView.class);
        when(v2.getId()).thenReturn(2L);
        when(v2.getComputedStatus()).thenReturn(Device.Status.OFFLINE);
        when(statusViewRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(v1, v2));

        var map = service.computedStatuses(List.of(1L, 2L));

        assertEquals(Device.Status.ONLINE, map.get(1L));
        assertEquals(Device.Status.OFFLINE, map.get(2L));
    }

    @Test
    void computedStatuses_emptyInput_returnsEmptyMap_withoutQuerying() {
        assertTrue(service.computedStatuses(List.of()).isEmpty());
        verifyNoInteractions(statusViewRepository);
    }

    // ===== Volume: per-device override (setVolume / clearVolume) =====

    @Test
    void setVolume_inScope_setsDeviceOverride() {
        var device = new Device(scopedRegion(), null, "SN-V1", "TV-V1");
        when(deviceRepository.findByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.of(device));

        service.setVolume(10L, 45);

        assertEquals(45, device.getDesiredVolume());
    }

    @Test
    void setVolume_outOfScope_throws404_andDoesNotSet() {
        // A restricted operator with project [7] cannot touch a device whose project is 100.
        when(operatorScopeResolver.resolve()).thenReturn(restrictedScope(List.of(7L)));
        var device = new Device(scopedRegion(), null, "SN-V2", "TV-V2");
        when(deviceRepository.findByIdAndDeletedAtIsNull(11L)).thenReturn(Optional.of(device));

        assertThrows(ResourceNotFoundException.class, () -> service.setVolume(11L, 60));
        assertNull(device.getDesiredVolume(), "out-of-scope device override must remain unset");
    }

    @Test
    void setVolume_unknownOrSoftDeleted_throws404() {
        when(deviceRepository.findByIdAndDeletedAtIsNull(404L)).thenReturn(Optional.empty());

        var ex = assertThrows(ResourceNotFoundException.class, () -> service.setVolume(404L, 50));
        assertEquals("Device not found with id: 404", ex.getMessage());
    }

    @Test
    void clearVolume_inScope_clearsOverrideToInherit() {
        var device = new Device(scopedRegion(), null, "SN-V3", "TV-V3");
        device.setDesiredVolume(80); // pre-existing override
        when(deviceRepository.findByIdAndDeletedAtIsNull(12L)).thenReturn(Optional.of(device));

        service.clearVolume(12L);

        assertNull(device.getDesiredVolume(), "clearVolume must null out the override (inherit)");
    }

    // ===== Volume: apply-to-all (setVolumeForAll) is operator-scoped =====

    @Test
    void setVolumeForAll_unrestrictedAdmin_bulkUpdatesAllWithNullProjectIds() {
        // The default @BeforeEach stub already resolves to an unrestricted (ADMIN) scope.
        when(deviceRepository.bulkSetDesiredVolume(org.mockito.ArgumentMatchers.eq(55),
                any(java.time.Instant.class), org.mockito.ArgumentMatchers.isNull())).thenReturn(42);

        assertEquals(42, service.setVolumeForAll(55));

        verify(deviceRepository).bulkSetDesiredVolume(org.mockito.ArgumentMatchers.eq(55),
                any(java.time.Instant.class), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void setVolumeForAll_restrictedOperator_narrowsToAssignedProjects() {
        when(operatorScopeResolver.resolve()).thenReturn(restrictedScope(List.of(7L)));
        when(deviceRepository.bulkSetDesiredVolume(org.mockito.ArgumentMatchers.eq(30),
                any(java.time.Instant.class), org.mockito.ArgumentMatchers.eq(List.of(7L)))).thenReturn(3);

        assertEquals(3, service.setVolumeForAll(30));

        verify(deviceRepository).bulkSetDesiredVolume(org.mockito.ArgumentMatchers.eq(30),
                any(java.time.Instant.class), org.mockito.ArgumentMatchers.eq(List.of(7L)));
    }

    @Test
    void setVolumeForAll_emptyScope_returns0_withoutTouchingRepo() {
        when(operatorScopeResolver.resolve()).thenReturn(restrictedScope(List.of()));

        assertEquals(0, service.setVolumeForAll(70));

        verify(deviceRepository, never()).bulkSetDesiredVolume(
                org.mockito.ArgumentMatchers.anyInt(), any(), any());
    }

    // ===== Volume: effective resolution (override ?? group ?? default) =====

    @Test
    void effectiveVolume_overrideWins() {
        var device = new Device(mock(Region.class), null, "SN-E1", "TV-E1");
        device.setDesiredVolume(33);
        when(deviceRepository.findById(20L)).thenReturn(Optional.of(device));

        assertEquals(33, service.effectiveVolume(20L));
    }

    @Test
    void effectiveVolume_inheritsGroupVolume_whenNoOverride() {
        var group = mock(DeviceGroup.class);
        when(group.getVolume()).thenReturn(70);
        var device = new Device(mock(Region.class), null, "SN-E2", "TV-E2");
        device.setDeviceGroup(group);
        when(deviceRepository.findById(21L)).thenReturn(Optional.of(device));

        assertEquals(70, service.effectiveVolume(21L));
    }

    @Test
    void effectiveVolume_fallsBackToDefault_whenNoOverrideAndNoGroup() {
        var device = new Device(mock(Region.class), null, "SN-E3", "TV-E3");
        when(deviceRepository.findById(22L)).thenReturn(Optional.of(device));

        assertEquals(DeviceVolumeResolver.DEFAULT_VOLUME, service.effectiveVolume(22L));
    }

    @Test
    void effectiveVolume_unknownDevice_throws404() {
        when(deviceRepository.findById(404L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.effectiveVolume(404L));
    }

    @Test
    void effectiveVolumes_emptyInput_returnsEmptyMap_withoutQuerying() {
        assertTrue(service.effectiveVolumes(List.of()).isEmpty());
        verify(deviceRepository, never()).findAllByIdInAndDeletedAtIsNull(any());
    }

    @Test
    void effectiveVolumes_nullInput_returnsEmptyMap() {
        assertTrue(service.effectiveVolumes(null).isEmpty());
        verify(deviceRepository, never()).findAllByIdInAndDeletedAtIsNull(any());
    }

    @Test
    void effectiveVolumes_resolvesEachIdToItsEffectiveVolume() {
        // id is JPA-managed (no setter), so mock the devices to control getId() for the map keys.
        var d1 = mock(Device.class);
        when(d1.getId()).thenReturn(1L);
        when(d1.getDesiredVolume()).thenReturn(25); // override wins
        var d2 = mock(Device.class);
        when(d2.getId()).thenReturn(2L);
        when(d2.getDesiredVolume()).thenReturn(null); // no override...
        when(d2.getDeviceGroup()).thenReturn(null);   // ...and no group → default
        when(deviceRepository.findAllByIdInAndDeletedAtIsNull(List.of(1L, 2L)))
                .thenReturn(List.of(d1, d2));

        var map = service.effectiveVolumes(List.of(1L, 2L));

        assertEquals(25, map.get(1L));
        assertEquals(DeviceVolumeResolver.DEFAULT_VOLUME, map.get(2L));
    }

    /** Restricted operator scope over the given project ids (empty list ⇒ empty scope). */
    private static OperatorScopeResolver.ScopedProjects restrictedScope(List<Long> projectIds) {
        return new OperatorScopeResolver.ScopedProjects(
                "operator", uz.orientadvertise.services.domain.auth.Role.OPERATOR, projectIds, true);
    }
}
