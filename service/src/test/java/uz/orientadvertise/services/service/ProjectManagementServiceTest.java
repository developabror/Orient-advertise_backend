package uz.orientadvertise.services.service;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import uz.orientadvertise.services.domain.model.DeviceGroup;
import uz.orientadvertise.services.domain.model.Project;
import uz.orientadvertise.services.domain.repository.DeviceGroupRepository;
import uz.orientadvertise.services.domain.repository.ProjectRepository;
import uz.orientadvertise.services.domain.repository.RegionRepository;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ProjectManagementService#delete} — the two-guard hard delete.
 *
 * <p>The device-group guard was added with the V37 region→project rebind: a device group is now
 * a direct child of the project (FK {@code fk_device_group_project}, no cascade), so an attached
 * group must be refused with a clean 409 rather than failing at the DB FK as a 500.
 */
@ExtendWith(MockitoExtension.class)
class ProjectManagementServiceTest {

    @Mock private ProjectRepository projectRepository;
    @Mock private RegionRepository regionRepository;
    @Mock private DeviceGroupRepository deviceGroupRepository;
    @Mock private OperatorScopeResolver operatorScopeResolver;

    @InjectMocks private ProjectManagementService service;

    @Test
    void delete_withActiveDeviceGroups_throws409AndDoesNotDelete() {
        when(projectRepository.findById(7L)).thenReturn(Optional.of(mock(Project.class)));
        when(regionRepository.countByProjectId(7L)).thenReturn(0L);
        when(deviceGroupRepository.findByProjectIdAndDeletedAtIsNull(7L))
                .thenReturn(List.of(mock(DeviceGroup.class), mock(DeviceGroup.class)));

        var ex = assertThrows(IllegalStateException.class, () -> service.delete(7L));
        assertTrue(ex.getMessage().contains("device group"),
                "message should name device groups: " + ex.getMessage());
        verify(projectRepository, never()).delete(any());
    }

    @Test
    void delete_regionGuardFiresBeforeGroupGuard() {
        when(projectRepository.findById(7L)).thenReturn(Optional.of(mock(Project.class)));
        when(regionRepository.countByProjectId(7L)).thenReturn(3L);

        var ex = assertThrows(IllegalStateException.class, () -> service.delete(7L));
        assertTrue(ex.getMessage().contains("region"), ex.getMessage());
        // Short-circuits: the group guard must not even be consulted once regions block.
        verify(deviceGroupRepository, never()).findByProjectIdAndDeletedAtIsNull(any());
        verify(projectRepository, never()).delete(any());
    }

    @Test
    void delete_noRegionsNoActiveGroups_deletes() {
        var project = mock(Project.class);
        when(projectRepository.findById(7L)).thenReturn(Optional.of(project));
        when(regionRepository.countByProjectId(7L)).thenReturn(0L);
        when(deviceGroupRepository.findByProjectIdAndDeletedAtIsNull(7L)).thenReturn(List.of());

        service.delete(7L);

        verify(projectRepository).delete(project);
    }
}
