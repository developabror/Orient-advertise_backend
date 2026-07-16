package uz.orientadvertise.services.service;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.auth.Role;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.Project;
import uz.orientadvertise.services.domain.model.Region;
import uz.orientadvertise.services.domain.model.SyncGroup;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.ProjectRepository;
import uz.orientadvertise.services.domain.repository.SyncGroupRepository;
import uz.orientadvertise.services.service.OperatorScopeResolver.ScopedProjects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@link SyncGroupManagementService}: scope guards, move-not-reject
 * membership bucketing, dedupe, duplicate + no-op rename, the hard-delete active-member guard,
 * cross-project rejection, and missing-device 404s.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SyncGroupManagementServiceTest {

    private static final long GROUP_ID = 10L;
    private static final long GROUP_PROJECT_ID = 100L;

    @Mock private SyncGroupRepository groupRepository;
    @Mock private DeviceRepository deviceRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private OperatorScopeResolver operatorScopeResolver;

    @InjectMocks private SyncGroupManagementService service;

    private void scopeUnrestricted() {
        when(operatorScopeResolver.resolve())
                .thenReturn(new ScopedProjects(null, Role.ADMIN, null, false));
    }

    private void scopeExcludingGroupProject() {
        when(operatorScopeResolver.resolve())
                .thenReturn(new ScopedProjects("op", Role.OPERATOR, List.of(999L), true));
    }

    private void scopeEmpty() {
        when(operatorScopeResolver.resolve())
                .thenReturn(new ScopedProjects("op", Role.OPERATOR, List.of(), true));
    }

    private SyncGroup groupInProject() {
        Project project = mock(Project.class);
        when(project.getId()).thenReturn(GROUP_PROJECT_ID);
        SyncGroup group = mock(SyncGroup.class);
        when(group.getId()).thenReturn(GROUP_ID);
        when(group.getProject()).thenReturn(project);
        return group;
    }

    private Device deviceInProject(long deviceId, long projectId) {
        Project project = mock(Project.class);
        when(project.getId()).thenReturn(projectId);
        Region region = mock(Region.class);
        when(region.getProject()).thenReturn(project);
        Device device = mock(Device.class);
        when(device.getId()).thenReturn(deviceId);
        when(device.getRegion()).thenReturn(region);
        return device;
    }

    // ---- list: empty operator scope → empty page ------------------------------------

    @Test
    void list_emptyScope_returnsEmptyPageWithoutQuerying() {
        scopeEmpty();
        var page = service.list(null, null, PageRequest.of(0, 20));
        assertTrue(page.isEmpty());
        verify(groupRepository, never()).findFiltered(any(), any(), any(), any());
    }

    @Test
    void list_pageSizeOver100_throws400() {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> service.list(null, null, PageRequest.of(0, 101)));
        assertTrue(ex.getMessage().contains("100"));
    }

    // ---- getDetail out-of-scope → 404 ------------------------------------------------

    @Test
    void getDetail_outOfScope_throws404() {
        scopeExcludingGroupProject();
        SyncGroup group = groupInProject();
        when(groupRepository.findByIdWithProject(GROUP_ID)).thenReturn(Optional.of(group));

        var ex = assertThrows(ResourceNotFoundException.class, () -> service.getDetail(GROUP_ID));
        assertTrue(ex.getMessage().contains("SyncGroup"));
        verify(deviceRepository, never()).findBySyncGroupIdAndDeletedAtIsNull(any());
    }

    // ---- create: duplicate 409 (exact message) + unknown project 404 -----------------

    @Test
    void create_duplicate_throws409WithOperatorMessage() {
        scopeUnrestricted();
        Project project = mock(Project.class);
        when(projectRepository.findById(3L)).thenReturn(Optional.of(project));
        when(groupRepository.existsByProjectIdAndName(3L, "Entrance wall")).thenReturn(true);

        var ex = assertThrows(IllegalStateException.class,
                () -> service.create(3L, "Entrance wall"));
        assertEquals("Sync group with name 'Entrance wall' already exists in project 3", ex.getMessage());
        verify(groupRepository, never()).save(any());
    }

    @Test
    void create_unknownProject_throws404() {
        when(projectRepository.findById(999L)).thenReturn(Optional.empty());
        var ex = assertThrows(ResourceNotFoundException.class, () -> service.create(999L, "X"));
        assertTrue(ex.getMessage().contains("Project"));
    }

    @Test
    void create_projectOutsideOperatorScope_throws404AndDoesNotSave() {
        // A restricted operator must not create a group in a project outside their scope; the
        // guard collapses to a Project 404 (no existence oracle) and never writes a row.
        Project project = mock(Project.class);
        when(projectRepository.findById(3L)).thenReturn(Optional.of(project));
        when(operatorScopeResolver.resolve())
                .thenReturn(new ScopedProjects("op", Role.OPERATOR, List.of(999L), true));

        var ex = assertThrows(ResourceNotFoundException.class, () -> service.create(3L, "New"));
        assertTrue(ex.getMessage().contains("Project"));
        verify(groupRepository, never()).existsByProjectIdAndName(any(), any());
        verify(groupRepository, never()).save(any());
    }

    // ---- rename: no-op short-circuits; duplicate 409 ---------------------------------

    @Test
    void rename_noOp_skipsDuplicateCheckAndWrite() {
        scopeUnrestricted();
        SyncGroup group = groupInProject();
        when(group.getName()).thenReturn("Same");
        when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.of(group));
        when(groupRepository.findByIdWithProject(GROUP_ID)).thenReturn(Optional.of(group));
        when(deviceRepository.findBySyncGroupIdAndDeletedAtIsNull(GROUP_ID)).thenReturn(List.of());

        service.rename(GROUP_ID, "Same");

        verify(groupRepository, never()).existsDuplicateExcluding(any(), any(), any());
        verify(group, never()).setName(any());
    }

    @Test
    void rename_duplicateNewName_throws409() {
        scopeUnrestricted();
        SyncGroup group = groupInProject();
        when(group.getName()).thenReturn("Old");
        when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.of(group));
        when(groupRepository.existsDuplicateExcluding(GROUP_PROJECT_ID, "Taken", GROUP_ID)).thenReturn(true);

        var ex = assertThrows(IllegalStateException.class, () -> service.rename(GROUP_ID, "Taken"));
        assertEquals("Sync group with name 'Taken' already exists in project " + GROUP_PROJECT_ID,
                ex.getMessage());
        verify(group, never()).setName(any());
    }

    // ---- addDevices: cross-project 400 -----------------------------------------------

    @Test
    void addDevices_deviceFromDifferentProject_throwsCrossProjectAndAddsNothing() {
        scopeUnrestricted();
        SyncGroup group = groupInProject();
        when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.of(group));

        Device offender = deviceInProject(55L, 200L);
        when(deviceRepository.findAllByIdInAndDeletedAtIsNull(List.of(55L))).thenReturn(List.of(offender));

        var ex = assertThrows(IllegalArgumentException.class,
                () -> service.addDevices(GROUP_ID, List.of(55L)));
        assertTrue(ex.getMessage().contains("different project"));
        assertTrue(ex.getMessage().contains("55"));
        verify(offender, never()).setSyncGroup(group);
    }

    // ---- addDevices: same project, different region → accepted -----------------------

    @Test
    void addDevices_sameProjectDifferentRegion_isAdded() {
        scopeUnrestricted();
        SyncGroup group = groupInProject();
        when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.of(group));

        Device device = deviceInProject(77L, GROUP_PROJECT_ID);
        when(device.getSyncGroup()).thenReturn(null);
        when(deviceRepository.findAllByIdInAndDeletedAtIsNull(List.of(77L))).thenReturn(List.of(device));

        var result = service.addDevices(GROUP_ID, List.of(77L));

        assertEquals(1, result.addedCount());
        assertTrue(result.alreadyMember().isEmpty());
        assertTrue(result.movedFrom().isEmpty());
        verify(device).setSyncGroup(group);
    }

    // ---- addDevices: move-not-reject bucketing + dedupe ------------------------------

    @Test
    void addDevices_bucketsAddedAlreadyMemberAndMoved_withDedupe() {
        scopeUnrestricted();
        SyncGroup group = groupInProject();
        when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.of(group));

        // 100 unassigned → added; 101 already in THIS group → alreadyMember;
        // 102 in a DIFFERENT sync group (id 7) → moved + added.
        Device d100 = deviceInProject(100L, GROUP_PROJECT_ID);
        when(d100.getSyncGroup()).thenReturn(null);

        SyncGroup thisGroupRef = mock(SyncGroup.class);
        when(thisGroupRef.getId()).thenReturn(GROUP_ID);
        Device d101 = deviceInProject(101L, GROUP_PROJECT_ID);
        when(d101.getSyncGroup()).thenReturn(thisGroupRef);

        SyncGroup otherGroup = mock(SyncGroup.class);
        when(otherGroup.getId()).thenReturn(7L);
        Device d102 = deviceInProject(102L, GROUP_PROJECT_ID);
        when(d102.getSyncGroup()).thenReturn(otherGroup);

        // Input carries a duplicate 100 → deduped to a single action.
        when(deviceRepository.findAllByIdInAndDeletedAtIsNull(List.of(100L, 101L, 102L)))
                .thenReturn(List.of(d100, d101, d102));

        var result = service.addDevices(GROUP_ID, List.of(100L, 100L, 101L, 102L));

        assertEquals(2, result.addedCount(), "100 (unassigned) + 102 (moved) both land in the group");
        assertEquals(List.of(101L), result.alreadyMember());
        assertEquals(7L, result.movedFrom().get(102L), "previous SYNC-group id is reported");
        verify(d100).setSyncGroup(group);
        verify(d102).setSyncGroup(group);
        verify(d101, never()).setSyncGroup(any());
    }

    // ---- addDevices: missing device → 404 --------------------------------------------

    @Test
    void addDevices_missingDevice_throws404WithIds() {
        scopeUnrestricted();
        SyncGroup group = groupInProject();
        when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.of(group));
        when(deviceRepository.findAllByIdInAndDeletedAtIsNull(List.of(101L, 102L))).thenReturn(List.of());

        var ex = assertThrows(ResourceNotFoundException.class,
                () -> service.addDevices(GROUP_ID, List.of(101L, 102L)));
        assertTrue(ex.getMessage().contains("101"));
        assertTrue(ex.getMessage().contains("102"));
    }

    // ---- addDevices / removeDevice: out-of-scope guard short-circuits → 404 -----------

    @Test
    void addDevices_outOfScope_throws404AndNeverLoadsDevices() {
        scopeExcludingGroupProject();
        SyncGroup group = groupInProject();
        when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.of(group));

        var ex = assertThrows(ResourceNotFoundException.class,
                () -> service.addDevices(GROUP_ID, List.of(1L)));
        assertTrue(ex.getMessage().contains("SyncGroup"));
        verify(deviceRepository, never()).findAllByIdInAndDeletedAtIsNull(anyList());
    }

    @Test
    void removeDevice_outOfScope_throws404AndNeverLoadsDevice() {
        scopeExcludingGroupProject();
        SyncGroup group = groupInProject();
        when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.of(group));

        var ex = assertThrows(ResourceNotFoundException.class,
                () -> service.removeDevice(GROUP_ID, 1L));
        assertTrue(ex.getMessage().contains("SyncGroup"));
        verify(deviceRepository, never()).findByIdAndDeletedAtIsNull(1L);
    }

    @Test
    void removeDevice_inScopeAndMember_detaches() {
        scopeUnrestricted();
        SyncGroup group = groupInProject();
        when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.of(group));

        SyncGroup thisGroupRef = mock(SyncGroup.class);
        when(thisGroupRef.getId()).thenReturn(GROUP_ID);
        Device device = mock(Device.class);
        when(device.getSyncGroup()).thenReturn(thisGroupRef);
        when(deviceRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(device));

        service.removeDevice(GROUP_ID, 1L);
        verify(device).setSyncGroup(null);
    }

    @Test
    void removeDevice_deviceInDifferentGroup_throws404NotLeakingItsGroup() {
        scopeUnrestricted();
        SyncGroup group = groupInProject();
        when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.of(group));

        SyncGroup otherGroup = mock(SyncGroup.class);
        when(otherGroup.getId()).thenReturn(99L);
        Device device = mock(Device.class);
        when(device.getSyncGroup()).thenReturn(otherGroup);
        when(deviceRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(device));

        var ex = assertThrows(ResourceNotFoundException.class, () -> service.removeDevice(GROUP_ID, 1L));
        assertTrue(ex.getMessage().contains("Device"));
        verify(device, never()).setSyncGroup(any());
    }

    // ---- delete: active-member 409 guard + hard-delete success -----------------------

    @Test
    void delete_hasActiveMembers_throws409AndDoesNotDelete() {
        scopeUnrestricted();
        SyncGroup group = groupInProject();
        when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.of(group));
        when(deviceRepository.countBySyncGroupIdAndDeletedAtIsNull(GROUP_ID)).thenReturn(3L);

        var ex = assertThrows(IllegalStateException.class, () -> service.delete(GROUP_ID));
        assertEquals("Sync group has 3 active device(s); remove them first", ex.getMessage());
        verify(groupRepository, never()).delete(any());
        verify(deviceRepository, never()).bulkClearSyncGroup(any(), any());
    }

    @Test
    void delete_noActiveMembers_detachesStragglersAndHardDeletes() {
        scopeUnrestricted();
        SyncGroup group = groupInProject();
        when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.of(group));
        when(deviceRepository.countBySyncGroupIdAndDeletedAtIsNull(GROUP_ID)).thenReturn(0L);

        service.delete(GROUP_ID);

        verify(deviceRepository).bulkClearSyncGroup(eq(GROUP_ID), any());
        verify(groupRepository).delete(group);
    }

    @Test
    void delete_outOfScope_throws404() {
        scopeExcludingGroupProject();
        SyncGroup group = groupInProject();
        when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.of(group));

        var ex = assertThrows(ResourceNotFoundException.class, () -> service.delete(GROUP_ID));
        assertTrue(ex.getMessage().contains("SyncGroup"));
        verify(groupRepository, never()).delete(any());
    }

    @Test
    void delete_missing_throws404() {
        when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.empty());
        var ex = assertThrows(ResourceNotFoundException.class, () -> service.delete(GROUP_ID));
        assertTrue(ex.getMessage().contains("SyncGroup"));
    }
}
