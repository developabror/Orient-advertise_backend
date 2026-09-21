package uz.orientadvertise.services.service;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.orientadvertise.services.common.exception.AccessForbiddenException;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.auth.Role;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.domain.model.Project;
import uz.orientadvertise.services.domain.repository.ContentFileRepository;
import uz.orientadvertise.services.domain.repository.PlaylistItemRepository;
import uz.orientadvertise.services.domain.repository.ProjectRepository;
import uz.orientadvertise.services.service.OperatorScopeResolver.ScopedProjects;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AUTHZ-01: {@code PATCH /api/content/{id}/project} must keep a restricted operator inside their
 * own project set — both the project the file leaves and the one it goes to. Row ownership
 * (owned vs granted) is the controller's check and is covered by the controller tests.
 */
class ContentManagementServiceTest {

    private static final long CONTENT_ID = 7L;
    private static final long IN_SCOPE = 1L;
    private static final long OUT_OF_SCOPE = 2L;

    private ContentFileRepository contentFileRepository;
    private ProjectRepository projectRepository;
    private OperatorScopeResolver operatorScopeResolver;
    private ContentManagementService service;

    @BeforeEach
    void setUp() {
        contentFileRepository = mock(ContentFileRepository.class);
        projectRepository = mock(ProjectRepository.class);
        operatorScopeResolver = mock(OperatorScopeResolver.class);
        service = new ContentManagementService(contentFileRepository, mock(PlaylistItemRepository.class),
                projectRepository, operatorScopeResolver);
        var inScope = project(IN_SCOPE);
        when(projectRepository.findById(IN_SCOPE)).thenReturn(Optional.of(inScope));
    }

    private static Project project(long id) {
        var project = mock(Project.class);
        when(project.getId()).thenReturn(id);
        return project;
    }

    private ContentFile contentIn(Long projectId) {
        // Built before the stub: a mock created inside thenReturn(...) breaks Mockito's stubbing.
        var bound = projectId == null ? null : project(projectId);
        var content = mock(ContentFile.class);
        when(content.getProject()).thenReturn(bound);
        when(contentFileRepository.findById(CONTENT_ID)).thenReturn(Optional.of(content));
        return content;
    }

    private void operatorScopedTo(long projectId) {
        when(operatorScopeResolver.resolve())
                .thenReturn(new ScopedProjects("op", Role.OPERATOR, List.of(projectId), true));
    }

    @Test
    void operator_movesContentBetweenInScopeProjects() {
        operatorScopedTo(IN_SCOPE);
        var content = contentIn(null);

        service.assignProject(CONTENT_ID, IN_SCOPE);

        verify(content).setProject(any(Project.class));
    }

    @Test
    void operator_targetProjectOutOfScope_is404AndNothingChanges() {
        operatorScopedTo(IN_SCOPE);
        var content = contentIn(IN_SCOPE);

        var ex = assertThrows(ResourceNotFoundException.class,
                () -> service.assignProject(CONTENT_ID, OUT_OF_SCOPE));

        // Same 404 as a project that doesn't exist — and the project row is never even loaded.
        assertTrue(ex.getMessage().contains("Project"));
        verify(projectRepository, never()).findById(OUT_OF_SCOPE);
        verify(content, never()).setProject(any());
    }

    @Test
    void operator_contentBoundToOutOfScopeProject_is403_evenWhenClearing() {
        operatorScopedTo(IN_SCOPE);
        var content = contentIn(OUT_OF_SCOPE);

        assertThrows(AccessForbiddenException.class, () -> service.assignProject(CONTENT_ID, IN_SCOPE));
        assertThrows(AccessForbiddenException.class, () -> service.assignProject(CONTENT_ID, null));

        verify(content, never()).setProject(any());
    }

    @Test
    void operator_clearsBindingOnInScopeContent() {
        operatorScopedTo(IN_SCOPE);
        var content = contentIn(IN_SCOPE);

        service.assignProject(CONTENT_ID, null);

        verify(content).setProject(null);
    }

    @Test
    void admin_isUnrestricted() {
        when(operatorScopeResolver.resolve()).thenReturn(new ScopedProjects("admin", Role.ADMIN, null, false));
        var content = contentIn(OUT_OF_SCOPE);

        service.assignProject(CONTENT_ID, IN_SCOPE);

        verify(content).setProject(any(Project.class));
    }
}
