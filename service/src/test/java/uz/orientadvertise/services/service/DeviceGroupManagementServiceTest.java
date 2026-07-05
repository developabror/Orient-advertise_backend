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
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.auth.Role;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.DeviceGroup;
import uz.orientadvertise.services.domain.model.Project;
import uz.orientadvertise.services.domain.model.Region;
import uz.orientadvertise.services.domain.repository.ContentAssignmentRepository;
import uz.orientadvertise.services.domain.repository.DeviceGroupRepository;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.ProjectRepository;
import uz.orientadvertise.services.service.OperatorScopeResolver.ScopedProjects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for the group→project membership invariants (spec §8 "Add"):
 * <ul>
 *   <li>(a) cross-project devices are rejected with the §0 400 message;</li>
 *   <li>(b) a device in a <i>different region but the same project</i> is now accepted
 *       (the relaxed, project-spanning model);</li>
 *   <li>(c) the operator project-scope guard collapses out-of-scope membership
 *       mutation to a 404 on both {@code addDevices} and {@code removeDevice}.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeviceGroupManagementServiceTest {

    private static final long GROUP_ID = 10L;
    private static final long GROUP_PROJECT_ID = 100L;

    @Mock private DeviceGroupRepository groupRepository;
    @Mock private DeviceRepository deviceRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private ContentAssignmentRepository assignmentRepository;
    @Mock private OperatorScopeResolver operatorScopeResolver;

    @InjectMocks private DeviceGroupManagementService service;

    /** Unrestricted (ADMIN-style) scope: {@code excludes()} always false. */
    private void scopeUnrestricted() {
        when(operatorScopeResolver.resolve())
                .thenReturn(new ScopedProjects(null, Role.ADMIN, null, false));
    }

    /** Restricted scope that does NOT contain the group's project ⇒ {@code excludes()} true. */
    private void scopeExcludingGroupProject() {
        when(operatorScopeResolver.resolve())
                .thenReturn(new ScopedProjects("op", Role.OPERATOR, List.of(999L), true));
    }

    /** A group whose parent project id is {@link #GROUP_PROJECT_ID}. */
    private DeviceGroup groupInProject() {
        Project project = mock(Project.class);
        when(project.getId()).thenReturn(GROUP_PROJECT_ID);
        DeviceGroup group = mock(DeviceGroup.class);
        when(group.getId()).thenReturn(GROUP_ID);
        when(group.getProject()).thenReturn(project);
        return group;
    }

    /**
     * A device whose region's project id is {@code projectId}. Region id is decorative
     * here — the invariant pivots on the project, not the region, which is exactly what
     * the relaxed model must prove.
     */
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

    // ---- (a) cross-project device rejected (400 at API) -----------------------------

    @Test
    void addDevices_deviceFromDifferentProject_throwsCrossProjectAndAddsNothing() {
        scopeUnrestricted();
        DeviceGroup group = groupInProject();
        when(groupRepository.findByIdAndDeletedAtIsNull(GROUP_ID)).thenReturn(Optional.of(group));

        Device offender = deviceInProject(55L, 200L); // different project
        when(deviceRepository.findAllByIdInAndDeletedAtIsNull(List.of(55L)))
                .thenReturn(List.of(offender));

        var ex = assertThrows(IllegalArgumentException.class,
                () -> service.addDevices(GROUP_ID, List.of(55L)));

        assertTrue(ex.getMessage().contains("different project"),
                "Expected cross-project message; got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("55"),
                "Offending device id must appear in message; got: " + ex.getMessage());
        // The whole batch must roll back: no membership write may have happened.
        verify(offender, never()).setDeviceGroup(group);
    }

    // ---- (b) different region, SAME project is now allowed ---------------------------

    @Test
    void addDevices_deviceFromDifferentRegionSameProject_isAdded() {
        scopeUnrestricted();
        DeviceGroup group = groupInProject();
        when(groupRepository.findByIdAndDeletedAtIsNull(GROUP_ID)).thenReturn(Optional.of(group));

        // Same project (100) as the group, but a region the group did not originate from.
        Device device = deviceInProject(77L, GROUP_PROJECT_ID);
        when(device.getDeviceGroup()).thenReturn(null); // currently unassigned
        when(deviceRepository.findAllByIdInAndDeletedAtIsNull(List.of(77L)))
                .thenReturn(List.of(device));

        var result = service.addDevices(GROUP_ID, List.of(77L));

        assertEquals(1, result.addedCount(),
                "A same-project device from another region must be accepted under the relaxed model");
        assertTrue(result.alreadyMember().isEmpty());
        assertTrue(result.movedFrom().isEmpty());
        verify(device).setDeviceGroup(group);
    }

    // ---- (c) operator-scope guard ⇒ 404 on membership mutation -----------------------

    @Test
    void addDevices_groupOutsideOperatorScope_throws404AndNeverLoadsDevices() {
        scopeExcludingGroupProject();
        DeviceGroup group = groupInProject();
        when(groupRepository.findByIdAndDeletedAtIsNull(GROUP_ID)).thenReturn(Optional.of(group));

        var ex = assertThrows(ResourceNotFoundException.class,
                () -> service.addDevices(GROUP_ID, List.of(1L)));

        assertTrue(ex.getMessage().contains("DeviceGroup"),
                "Out-of-scope mutation must collapse to a DeviceGroup 404; got: " + ex.getMessage());
        // Guard must short-circuit before touching the device table.
        verify(deviceRepository, never()).findAllByIdInAndDeletedAtIsNull(anyList());
    }

    @Test
    void removeDevice_groupOutsideOperatorScope_throws404AndNeverLoadsDevice() {
        scopeExcludingGroupProject();
        DeviceGroup group = groupInProject();
        when(groupRepository.findByIdAndDeletedAtIsNull(GROUP_ID)).thenReturn(Optional.of(group));

        var ex = assertThrows(ResourceNotFoundException.class,
                () -> service.removeDevice(GROUP_ID, 1L));

        assertTrue(ex.getMessage().contains("DeviceGroup"),
                "Out-of-scope mutation must collapse to a DeviceGroup 404; got: " + ex.getMessage());
        verify(deviceRepository, never()).findByIdAndDeletedAtIsNull(1L);
    }

    // ---- guard ordering: group 404 precedes the scope check -------------------------

    @Test
    void addDevices_missingGroup_throwsDeviceGroup404() {
        when(groupRepository.findByIdAndDeletedAtIsNull(GROUP_ID)).thenReturn(Optional.empty());

        var ex = assertThrows(ResourceNotFoundException.class,
                () -> service.addDevices(GROUP_ID, List.of(1L)));
        assertTrue(ex.getMessage().contains("DeviceGroup"),
                "Missing group must 404 as DeviceGroup; got: " + ex.getMessage());
    }

    // ---- removeDevice positive path: in-scope removal detaches the device -----------

    @Test
    void removeDevice_inScopeAndMember_detachesDevice() {
        scopeUnrestricted();
        DeviceGroup group = groupInProject();
        when(groupRepository.findByIdAndDeletedAtIsNull(GROUP_ID)).thenReturn(Optional.of(group));

        Device device = mock(Device.class);
        when(device.getDeviceGroup()).thenReturn(group); // currently a member of this group
        when(deviceRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(device));

        service.removeDevice(GROUP_ID, 1L);

        verify(device).setDeviceGroup(null);
    }

    // ---- setVolume: applies group volume AND overrides every member's per-device value ----

    @Test
    void setVolume_inScope_setsGroupVolumeAndClearsAllMemberOverrides() {
        scopeUnrestricted();
        DeviceGroup group = groupInProject();
        when(groupRepository.findByIdAndDeletedAtIsNull(GROUP_ID)).thenReturn(Optional.of(group));

        service.setVolume(GROUP_ID, 40);

        verify(group).setVolume(40);
        // The override semantic: every member's per-device desiredVolume is wiped so they
        // inherit the freshly applied group volume — manual overrides must not survive.
        verify(deviceRepository).bulkClearDesiredVolumeByGroup(eq(GROUP_ID), any());
    }

    @Test
    void setVolume_groupOutsideOperatorScope_throws404AndNeverClearsOverrides() {
        scopeExcludingGroupProject();
        DeviceGroup group = groupInProject();
        when(groupRepository.findByIdAndDeletedAtIsNull(GROUP_ID)).thenReturn(Optional.of(group));

        var ex = assertThrows(ResourceNotFoundException.class,
                () -> service.setVolume(GROUP_ID, 40));

        assertTrue(ex.getMessage().contains("DeviceGroup"),
                "Out-of-scope volume apply must collapse to a DeviceGroup 404; got: " + ex.getMessage());
        verify(group, never()).setVolume(anyInt());
        verify(deviceRepository, never()).bulkClearDesiredVolumeByGroup(anyLong(), any());
    }

    @Test
    void setVolume_missingGroup_throwsDeviceGroup404AndNeverClearsOverrides() {
        when(groupRepository.findByIdAndDeletedAtIsNull(GROUP_ID)).thenReturn(Optional.empty());

        var ex = assertThrows(ResourceNotFoundException.class,
                () -> service.setVolume(GROUP_ID, 40));
        assertTrue(ex.getMessage().contains("DeviceGroup"),
                "Missing group must 404 as DeviceGroup; got: " + ex.getMessage());
        verify(deviceRepository, never()).bulkClearDesiredVolumeByGroup(anyLong(), any());
    }
}
