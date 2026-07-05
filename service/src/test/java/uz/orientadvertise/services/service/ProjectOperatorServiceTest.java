package uz.orientadvertise.services.service;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.auth.Role;
import uz.orientadvertise.services.domain.model.AppUser;
import uz.orientadvertise.services.domain.model.Project;
import uz.orientadvertise.services.domain.model.ProjectOperator;
import uz.orientadvertise.services.domain.repository.AppUserRepository;
import uz.orientadvertise.services.domain.repository.ProjectOperatorRepository;
import uz.orientadvertise.services.domain.repository.ProjectRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectOperatorServiceTest {

    private ProjectOperatorRepository projectOperatorRepository;
    private ProjectRepository projectRepository;
    private AppUserRepository userRepository;
    private ProjectOperatorService service;

    @BeforeEach
    void setUp() {
        projectOperatorRepository = mock(ProjectOperatorRepository.class);
        projectRepository = mock(ProjectRepository.class);
        userRepository = mock(AppUserRepository.class);
        service = new ProjectOperatorService(projectOperatorRepository, projectRepository, userRepository);
    }

    private AppUser user(long id, Role role) {
        var u = mock(AppUser.class);
        when(u.getId()).thenReturn(id);
        when(u.getRole()).thenReturn(role);
        return u;
    }

    @Test
    void assign_unknownProject_throws404() {
        when(projectRepository.findById(1L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.assignOperator(1L, 5L, "admin"));
        verify(projectOperatorRepository, never()).save(any());
    }

    @Test
    void assign_unknownUser_throws404() {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(mock(Project.class)));
        when(userRepository.findById(5L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.assignOperator(1L, 5L, "admin"));
    }

    @Test
    void assign_nonOperatorUser_throws409() {
        var viewer = user(5L, Role.VIEWER);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(mock(Project.class)));
        when(userRepository.findById(5L)).thenReturn(Optional.of(viewer));
        assertThrows(IllegalStateException.class, () -> service.assignOperator(1L, 5L, "admin"));
    }

    @Test
    void assign_duplicate_throws409() {
        var op = user(5L, Role.OPERATOR);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(mock(Project.class)));
        when(userRepository.findById(5L)).thenReturn(Optional.of(op));
        when(projectOperatorRepository.existsByUserIdAndProjectId(5L, 1L)).thenReturn(true);
        assertThrows(IllegalStateException.class, () -> service.assignOperator(1L, 5L, "admin"));
    }

    @Test
    void assign_success_persists() {
        var op = user(5L, Role.OPERATOR);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(mock(Project.class)));
        when(userRepository.findById(5L)).thenReturn(Optional.of(op));
        when(projectOperatorRepository.existsByUserIdAndProjectId(5L, 1L)).thenReturn(false);
        when(projectOperatorRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.assignOperator(1L, 5L, "admin");

        verify(projectOperatorRepository).save(any(ProjectOperator.class));
    }

    @Test
    void unassign_idempotentWhenAbsent_noThrow() {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(mock(Project.class)));
        when(projectOperatorRepository.findByUserIdAndProjectId(5L, 1L)).thenReturn(Optional.empty());

        service.unassignOperator(1L, 5L);   // no exception

        verify(projectOperatorRepository, never()).delete(any());
    }

    @Test
    void unassign_unknownProject_throws404() {
        when(projectRepository.findById(1L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.unassignOperator(1L, 5L));
    }

    @Test
    void listOperators_unknownProject_throws404() {
        when(projectRepository.findById(1L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.listOperators(1L));
    }

    @Test
    void setOperators_rejectsNonOperator_409() {
        var advertiser = user(5L, Role.ADVERTISER);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(mock(Project.class)));
        when(userRepository.findById(5L)).thenReturn(Optional.of(advertiser));
        assertThrows(IllegalStateException.class, () -> service.setOperators(1L, List.of(5L), "admin"));
    }

    @Test
    void setOperators_diffsAddAndRemove() {
        var project = mock(Project.class);
        var sixth = user(6L, Role.OPERATOR);
        // Currently assigned: user 5. Desired: user 6 → remove 5, add 6.
        var existingUser = user(5L, Role.OPERATOR);
        var existing = mock(ProjectOperator.class);
        when(existing.getUser()).thenReturn(existingUser);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(userRepository.findById(6L)).thenReturn(Optional.of(sixth));
        when(projectOperatorRepository.findOperatorsByProjectId(1L)).thenReturn(List.of(existing), List.of());

        service.setOperators(1L, List.of(6L), "admin");

        verify(projectOperatorRepository).delete(existing);          // removed
        verify(projectOperatorRepository, times(1)).save(any());      // added 6
    }

    @Test
    void revokeAllForUser_delegatesToRepo() {
        when(projectOperatorRepository.deleteAllByUserId(5L)).thenReturn(2);
        assertEquals(2, service.revokeAllForUser(5L));
        verify(projectOperatorRepository).deleteAllByUserId(eq(5L));
    }
}
