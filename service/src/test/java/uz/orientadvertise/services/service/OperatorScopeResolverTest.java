package uz.orientadvertise.services.service;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import uz.orientadvertise.services.domain.auth.Role;
import uz.orientadvertise.services.domain.model.AppUser;
import uz.orientadvertise.services.domain.repository.AppUserRepository;
import uz.orientadvertise.services.domain.repository.ProjectOperatorRepository;
import uz.orientadvertise.services.service.OperatorScopeResolver.ScopedProjects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The scope resolver is the security backbone of operator scoping. The most important property
 * is FAIL-CLOSED: an unknown / inactive / null caller must resolve to restricted-with-empty, never
 * to unrestricted. resolve() (SecurityContext) and resolveForUsername() must agree.
 */
class OperatorScopeResolverTest {

    private AppUserRepository userRepository;
    private ProjectOperatorRepository projectOperatorRepository;
    private OperatorScopeResolver resolver;

    @BeforeEach
    void setUp() {
        userRepository = mock(AppUserRepository.class);
        projectOperatorRepository = mock(ProjectOperatorRepository.class);
        resolver = new OperatorScopeResolver(userRepository, projectOperatorRepository);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private AppUser user(long id, Role role) {
        var u = mock(AppUser.class);
        when(u.getId()).thenReturn(id);
        when(u.getRole()).thenReturn(role);
        return u;
    }

    private void authenticate(String username) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, null, List.of()));
    }

    @Test
    void resolve_admin_unrestricted() {
        authenticate("admin");
        var admin = user(1L, Role.ADMIN);
        when(userRepository.findByUsernameAndIsActiveTrue("admin")).thenReturn(Optional.of(admin));

        ScopedProjects scope = resolver.resolve();

        assertFalse(scope.restricted());
        assertNull(scope.projectIds());
        assertFalse(scope.excludes(999L));   // admin excludes nothing
    }

    @Test
    void resolve_operator_returnsAssignedSet() {
        authenticate("op");
        var op = user(5L, Role.OPERATOR);
        when(userRepository.findByUsernameAndIsActiveTrue("op")).thenReturn(Optional.of(op));
        when(projectOperatorRepository.findProjectIdsByUserId(5L)).thenReturn(List.of(4L, 7L));

        ScopedProjects scope = resolver.resolve();

        assertTrue(scope.restricted());
        assertEquals(List.of(4L, 7L), scope.projectIds());
        assertFalse(scope.isEmptyScope());
        assertFalse(scope.excludes(4L));
        assertTrue(scope.excludes(9L));     // outside the assigned set
    }

    @Test
    void resolve_operatorWithZeroProjects_restrictedEmpty() {
        authenticate("op");
        var op = user(5L, Role.OPERATOR);
        when(userRepository.findByUsernameAndIsActiveTrue("op")).thenReturn(Optional.of(op));
        when(projectOperatorRepository.findProjectIdsByUserId(5L)).thenReturn(List.of());

        ScopedProjects scope = resolver.resolve();

        assertTrue(scope.restricted());
        assertTrue(scope.isEmptyScope());
        assertTrue(scope.excludes(4L));     // an empty-scope operator sees nothing
    }

    @Test
    void resolve_unknownOrInactiveUser_failsClosed() {
        authenticate("ghost");
        when(userRepository.findByUsernameAndIsActiveTrue("ghost")).thenReturn(Optional.empty());

        ScopedProjects scope = resolver.resolve();

        // FAIL-CLOSED — restricted with an empty set, NOT unrestricted.
        assertTrue(scope.restricted());
        assertTrue(scope.isEmptyScope());
        assertTrue(scope.excludes(1L));
    }

    @Test
    void resolve_noAuthentication_failsClosed() {
        SecurityContextHolder.clearContext();

        ScopedProjects scope = resolver.resolve();

        assertTrue(scope.restricted());
        assertTrue(scope.isEmptyScope());
    }

    @Test
    void resolveForUsername_nullOrBlank_failsClosed() {
        assertTrue(resolver.resolveForUsername(null).isEmptyScope());
        assertTrue(resolver.resolveForUsername("  ").isEmptyScope());
        assertTrue(resolver.resolveForUsername(null).restricted());
    }

    @Test
    void resolveForUsername_parityWithResolve() {
        var op = user(5L, Role.OPERATOR);
        when(userRepository.findByUsernameAndIsActiveTrue("op")).thenReturn(Optional.of(op));
        when(projectOperatorRepository.findProjectIdsByUserId(5L)).thenReturn(List.of(4L));

        authenticate("op");
        ScopedProjects viaContext = resolver.resolve();
        ScopedProjects viaUsername = resolver.resolveForUsername("op");

        assertEquals(viaContext.restricted(), viaUsername.restricted());
        assertEquals(viaContext.projectIds(), viaUsername.projectIds());
    }
}
