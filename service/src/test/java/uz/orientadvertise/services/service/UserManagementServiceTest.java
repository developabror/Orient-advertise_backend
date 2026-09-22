package uz.orientadvertise.services.service;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.auth.Role;
import uz.orientadvertise.services.domain.model.AppUser;
import uz.orientadvertise.services.domain.repository.AppUserRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserManagementServiceTest {

    private AppUserRepository userRepository;
    private AdvertiserContentService advertiserContentService;
    private OperatorContentService operatorContentService;
    private ProjectOperatorService projectOperatorService;
    private PasswordEncoder passwordEncoder;
    private UserManagementService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(AppUserRepository.class);
        advertiserContentService = mock(AdvertiserContentService.class);
        operatorContentService = mock(OperatorContentService.class);
        projectOperatorService = mock(ProjectOperatorService.class);
        passwordEncoder = mock(PasswordEncoder.class);
        when(passwordEncoder.encode(any())).thenAnswer(inv -> "bcrypt:" + inv.getArgument(0));
        service = new UserManagementService(userRepository, advertiserContentService,
                operatorContentService, projectOperatorService, passwordEncoder);
    }

    @Test
    void create_advertiser_persistsAndReturnsUser() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.empty());
        when(userRepository.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));

        var user = service.create("alice", "secret123", Role.ADVERTISER, null);

        assertEquals("alice", user.getUsername());
        assertEquals(Role.ADVERTISER, user.getRole());
        assertTrue(user.isActive());
        // Password must be stored encoded, never raw — otherwise login can never match it.
        verify(passwordEncoder).encode("secret123");
        assertEquals("bcrypt:secret123", user.getPassword());
    }

    @Test
    void create_withEmail_persistsNormalizedEmail() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.empty());
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(false);
        when(userRepository.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));

        var user = service.create("alice", "secret123", Role.ADVERTISER, "  Alice@Example.com ");

        assertEquals("alice@example.com", user.getEmail());
    }

    @Test
    void create_duplicateEmail_throws409() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.empty());
        when(userRepository.existsByEmail("dup@example.com")).thenReturn(true);

        assertThrows(IllegalStateException.class, () ->
                service.create("alice", "secret123", Role.ADVERTISER, "dup@example.com"));
    }

    @Test
    void create_blankUsername_throws400() {
        assertThrows(IllegalArgumentException.class, () ->
                service.create("  ", "secret123", Role.ADVERTISER, null));
    }

    @Test
    void create_shortPassword_throws400() {
        assertThrows(IllegalArgumentException.class, () ->
                service.create("alice", "abc", Role.ADVERTISER, null));
    }

    // AUTH-08: creation used to accept 6 characters while change/reset required 8.
    @Test
    void create_sevenCharacterPassword_isRejected_likeChangeAndReset() {
        assertThrows(IllegalArgumentException.class, () ->
                service.create("alice", "seven77", Role.ADVERTISER, null));
    }

    @Test
    void create_eightCharacterPassword_isAccepted() {
        when(userRepository.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));

        var user = service.create("alice", "eight888", Role.ADVERTISER, null);

        assertEquals("alice", user.getUsername());
    }

    // bcrypt reads at most 72 bytes and the encoder throws past that — reject it up front.
    @Test
    void create_passwordOverBcryptsSeventyTwoBytes_isRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                service.create("alice", "x".repeat(73), Role.ADVERTISER, null));
        // 37 Cyrillic letters: under 72 characters, but 74 bytes.
        assertThrows(IllegalArgumentException.class, () ->
                service.create("alice", "ж".repeat(37), Role.ADVERTISER, null));
    }

    @Test
    void create_passwordOfExactlySeventyTwoBytes_isAccepted() {
        when(userRepository.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));

        assertEquals("alice", service.create("alice", "x".repeat(72), Role.ADVERTISER, null).getUsername());
    }

    @Test
    void create_nullRole_throws400() {
        assertThrows(IllegalArgumentException.class, () ->
                service.create("alice", "secret123", null, null));
    }

    @Test
    void create_duplicateUsername_throws409() {
        var existing = mock(AppUser.class);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(existing));

        assertThrows(IllegalStateException.class, () ->
                service.create("alice", "secret123", Role.ADVERTISER, null));
    }

    @Test
    void delete_advertiser_cascadesContentAccess() {
        var user = mock(AppUser.class);
        when(user.getId()).thenReturn(7L);
        when(user.getRole()).thenReturn(Role.ADVERTISER);
        when(user.getUsername()).thenReturn("alice");
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));

        service.delete(7L);

        verify(advertiserContentService, times(1)).revokeAllForUser(eq(7L));
        verify(userRepository).delete(user);
    }

    @Test
    void delete_operator_cascadesProjectAndContentGrants() {
        var user = mock(AppUser.class);
        when(user.getId()).thenReturn(8L);
        when(user.getRole()).thenReturn(Role.OPERATOR);
        when(user.getUsername()).thenReturn("ops");
        when(userRepository.findById(8L)).thenReturn(Optional.of(user));

        service.delete(8L);

        // OPERATOR delete cascades both operator content grants and project assignments.
        verify(operatorContentService, times(1)).revokeAllForUser(eq(8L));
        verify(projectOperatorService, times(1)).revokeAllForUser(eq(8L));
        verify(advertiserContentService, never()).revokeAllForUser(any());
        verify(userRepository).delete(user);
    }

    @Test
    void delete_viewer_skipsCascade() {
        var user = mock(AppUser.class);
        when(user.getId()).thenReturn(9L);
        when(user.getRole()).thenReturn(Role.VIEWER);
        when(user.getUsername()).thenReturn("view");
        when(userRepository.findById(9L)).thenReturn(Optional.of(user));

        service.delete(9L);

        verify(advertiserContentService, never()).revokeAllForUser(any());
        verify(operatorContentService, never()).revokeAllForUser(any());
        verify(projectOperatorService, never()).revokeAllForUser(any());
        verify(userRepository).delete(user);
    }

    @Test
    void delete_unknownUser_throws404() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.delete(99L));
        verify(advertiserContentService, never()).revokeAllForUser(any());
    }
}
