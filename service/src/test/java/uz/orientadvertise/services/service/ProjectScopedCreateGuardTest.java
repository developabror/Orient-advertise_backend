package uz.orientadvertise.services.service;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.auth.Role;
import uz.orientadvertise.services.domain.model.DeviceGroup;
import uz.orientadvertise.services.domain.model.Facility;
import uz.orientadvertise.services.domain.model.Playlist;
import uz.orientadvertise.services.domain.model.Project;
import uz.orientadvertise.services.domain.model.Region;
import uz.orientadvertise.services.domain.repository.ContentAssignmentRepository;
import uz.orientadvertise.services.domain.repository.DeviceGroupRepository;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.FacilityRepository;
import uz.orientadvertise.services.domain.repository.PlaylistItemRepository;
import uz.orientadvertise.services.domain.repository.PlaylistRepository;
import uz.orientadvertise.services.domain.repository.ProjectRepository;
import uz.orientadvertise.services.domain.repository.RegionRepository;
import uz.orientadvertise.services.service.OperatorScopeResolver.ScopedProjects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AUTHZ-02: {@code create()} on Region, Facility, Playlist and DeviceGroup must apply the same
 * operator project-scope guard as their rename/update paths and as {@code SyncGroup} create. An
 * out-of-scope project is the same 404 as a missing one, is decided BEFORE the duplicate-name
 * check (a 409 would reveal names in another tenant's project), and saves nothing.
 */
class ProjectScopedCreateGuardTest {

    private static final long IN_SCOPE = 1L;
    private static final long OUT_OF_SCOPE = 2L;
    private static final long REGION_IN_OTHER_PROJECT = 20L;
    private static final long REGION_IN_OWN_PROJECT = 10L;

    private final ProjectRepository projectRepository = mock(ProjectRepository.class);
    private final RegionRepository regionRepository = mock(RegionRepository.class);
    private final FacilityRepository facilityRepository = mock(FacilityRepository.class);
    private final PlaylistRepository playlistRepository = mock(PlaylistRepository.class);
    private final DeviceGroupRepository groupRepository = mock(DeviceGroupRepository.class);
    private final OperatorScopeResolver operatorScopeResolver = mock(OperatorScopeResolver.class);

    private RegionManagementService regions;
    private FacilityManagementService facilities;
    private PlaylistManagementService playlists;
    private DeviceGroupManagementService groups;

    @BeforeEach
    void setUp() {
        var deviceRepository = mock(DeviceRepository.class);
        var assignmentRepository = mock(ContentAssignmentRepository.class);
        regions = new RegionManagementService(regionRepository, projectRepository, facilityRepository,
                deviceRepository, operatorScopeResolver);
        facilities = new FacilityManagementService(facilityRepository, regionRepository, deviceRepository,
                assignmentRepository, operatorScopeResolver);
        playlists = new PlaylistManagementService(playlistRepository, mock(PlaylistItemRepository.class),
                assignmentRepository, projectRepository, operatorScopeResolver);
        groups = new DeviceGroupManagementService(groupRepository, deviceRepository, projectRepository,
                assignmentRepository, operatorScopeResolver);

        var own = project(IN_SCOPE);
        var other = project(OUT_OF_SCOPE);
        when(projectRepository.findById(IN_SCOPE)).thenReturn(Optional.of(own));
        when(projectRepository.findById(OUT_OF_SCOPE)).thenReturn(Optional.of(other));
        var ownRegion = new Region(own, "Own", "OWN");
        var otherRegion = new Region(other, "Other", "OTH");
        when(regionRepository.findById(REGION_IN_OWN_PROJECT)).thenReturn(Optional.of(ownRegion));
        when(regionRepository.findById(REGION_IN_OTHER_PROJECT)).thenReturn(Optional.of(otherRegion));

        when(regionRepository.save(any(Region.class))).thenAnswer(inv -> inv.getArgument(0));
        when(facilityRepository.save(any(Facility.class))).thenAnswer(inv -> inv.getArgument(0));
        when(playlistRepository.save(any(Playlist.class))).thenAnswer(inv -> inv.getArgument(0));
        when(groupRepository.save(any(DeviceGroup.class))).thenAnswer(inv -> inv.getArgument(0));

        when(operatorScopeResolver.resolve())
                .thenReturn(new ScopedProjects("op", Role.OPERATOR, List.of(IN_SCOPE), true));
    }

    private static Project project(long id) {
        var project = mock(Project.class);
        when(project.getId()).thenReturn(id);
        return project;
    }

    private static void assertNotFound(String entity, long id, Executable call) {
        var ex = assertThrows(ResourceNotFoundException.class, call);
        // Identical to the missing-row message, so the two cases can't be told apart.
        assertEquals(new ResourceNotFoundException(entity, id).getMessage(), ex.getMessage());
    }

    // ---- out of scope: 404, no duplicate probe, nothing saved --------------------------

    @Test
    void region_outOfScopeProject_is404() {
        assertNotFound("Project", OUT_OF_SCOPE, () -> regions.create(OUT_OF_SCOPE, "X1", "X"));
        verify(regionRepository, never()).existsByProjectIdAndCode(anyLong(), anyString());
        verify(regionRepository, never()).save(any());
    }

    @Test
    void facility_regionInOutOfScopeProject_is404() {
        assertNotFound("Region", REGION_IN_OTHER_PROJECT,
                () -> facilities.create(REGION_IN_OTHER_PROJECT, "Mall", null));
        verify(facilityRepository, never()).existsByRegionIdAndName(anyLong(), anyString());
        verify(facilityRepository, never()).save(any());
    }

    @Test
    void playlist_outOfScopeProject_is404() {
        assertNotFound("Project", OUT_OF_SCOPE, () -> playlists.create(OUT_OF_SCOPE, "Morning"));
        verify(playlistRepository, never()).existsByProjectIdAndNameAndDeletedAtIsNull(anyLong(), anyString());
        verify(playlistRepository, never()).save(any());
    }

    @Test
    void deviceGroup_outOfScopeProject_is404() {
        assertNotFound("Project", OUT_OF_SCOPE, () -> groups.create(OUT_OF_SCOPE, "Lobby"));
        verify(groupRepository, never()).existsByProjectIdAndNameAndDeletedAtIsNull(anyLong(), anyString());
        verify(groupRepository, never()).save(any());
    }

    @Test
    void emptyOperatorScope_cannotCreateAnywhere() {
        when(operatorScopeResolver.resolve())
                .thenReturn(new ScopedProjects("op", Role.OPERATOR, List.of(), true));

        assertNotFound("Project", IN_SCOPE, () -> regions.create(IN_SCOPE, "X1", "X"));
        assertNotFound("Region", REGION_IN_OWN_PROJECT, () -> facilities.create(REGION_IN_OWN_PROJECT, "Mall", null));
        assertNotFound("Project", IN_SCOPE, () -> playlists.create(IN_SCOPE, "Morning"));
        assertNotFound("Project", IN_SCOPE, () -> groups.create(IN_SCOPE, "Lobby"));
    }

    // ---- in scope, and admin: created as before ----------------------------------------

    @Test
    void operator_createsInOwnProject() {
        regions.create(IN_SCOPE, "X1", "X");
        facilities.create(REGION_IN_OWN_PROJECT, "Mall", null);
        playlists.create(IN_SCOPE, "Morning");
        groups.create(IN_SCOPE, "Lobby");

        verify(regionRepository).save(any(Region.class));
        verify(facilityRepository).save(any(Facility.class));
        verify(playlistRepository).save(any(Playlist.class));
        verify(groupRepository).save(any(DeviceGroup.class));
    }

    @Test
    void admin_createsInAnyProject() {
        when(operatorScopeResolver.resolve()).thenReturn(new ScopedProjects("admin", Role.ADMIN, null, false));

        regions.create(OUT_OF_SCOPE, "X1", "X");
        facilities.create(REGION_IN_OTHER_PROJECT, "Mall", null);
        playlists.create(OUT_OF_SCOPE, "Morning");
        groups.create(OUT_OF_SCOPE, "Lobby");

        verify(regionRepository).save(any(Region.class));
        verify(facilityRepository).save(any(Facility.class));
        verify(playlistRepository).save(any(Playlist.class));
        verify(groupRepository).save(any(DeviceGroup.class));
    }
}
