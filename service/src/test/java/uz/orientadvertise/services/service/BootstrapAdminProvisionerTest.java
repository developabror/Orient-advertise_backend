package uz.orientadvertise.services.service;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import uz.orientadvertise.services.common.exception.IllegalConfigurationException;
import uz.orientadvertise.services.domain.auth.Role;
import uz.orientadvertise.services.domain.model.AppUser;
import uz.orientadvertise.services.domain.repository.AppUserRepository;
import uz.orientadvertise.services.infra.auth.RefreshTokenRepository;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BootstrapAdminProvisionerTest {

    private static final String PASSWORD = "correct-horse-battery";

    private AppUserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private RefreshTokenRepository refreshTokenRepository;

    @BeforeEach
    void setUp() {
        userRepository = mock(AppUserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        refreshTokenRepository = mock(RefreshTokenRepository.class);
        when(passwordEncoder.encode(anyString())).thenAnswer(inv -> "bcrypt:" + inv.getArgument(0));
    }

    private BootstrapAdminProvisioner provisioner(String username, String password) {
        return new BootstrapAdminProvisioner(userRepository, passwordEncoder, refreshTokenRepository,
                username, password);
    }

    // ----- construction (fail fast) -----

    @Test
    void usernameWithoutPassword_failsFast() {
        var ex = assertThrows(IllegalConfigurationException.class, () -> provisioner("root", ""));
        assertTrue(ex.getMessage().contains("APP_BOOTSTRAP_ADMIN_PASSWORD"));
    }

    @Test
    void passwordWithoutUsername_failsFast() {
        var ex = assertThrows(IllegalConfigurationException.class, () -> provisioner(" ", PASSWORD));
        assertTrue(ex.getMessage().contains("APP_BOOTSTRAP_ADMIN_USERNAME"));
    }

    @Test
    void passwordOneShortOfMinimum_failsFast_withoutEchoingIt() {
        var ex = assertThrows(IllegalConfigurationException.class, () -> provisioner("root", "elevenchars"));
        assertFalse(ex.getMessage().contains("elevenchars"), "message must never contain the password");
    }

    @Test
    void passwordAtMinimumLength_isAccepted() {
        assertDoesNotThrow(() -> provisioner("root", "twelve-chars"));
    }

    @Test
    void passwordWithTrailingWhitespace_failsFast() {
        // e.g. a CRLF .env file: the '\r' would become part of a password nobody can type.
        assertThrows(IllegalConfigurationException.class, () -> provisioner("root", PASSWORD + "\r"));
        assertThrows(IllegalConfigurationException.class, () -> provisioner("root", " " + PASSWORD));
    }

    // ----- run -----

    @Test
    void activeAdminExists_doesNothing() {
        when(userRepository.existsByRoleAndIsActiveTrue(Role.ADMIN)).thenReturn(true);

        provisioner("root", PASSWORD).run(null);

        verify(userRepository, never()).save(any());
        verify(passwordEncoder, never()).encode(anyString());
        verify(refreshTokenRepository, never()).invalidateAllForUser(anyString());
    }

    @Test
    void noAdmin_notConfigured_savesNothing() {
        provisioner("", "").run(null);

        verify(userRepository, never()).save(any());
        verify(refreshTokenRepository, never()).invalidateAllForUser(anyString());
    }

    @Test
    void noAdmin_newUsername_createsActiveAdminAndRevokesSessions() {
        provisioner(" root ", PASSWORD).run(null);

        var saved = ArgumentCaptor.forClass(AppUser.class);
        verify(userRepository).save(saved.capture());
        assertEquals("root", saved.getValue().getUsername(), "username is trimmed");
        assertEquals(Role.ADMIN, saved.getValue().getRole());
        assertTrue(saved.getValue().isActive());
        assertEquals("bcrypt:" + PASSWORD, saved.getValue().getPassword());
        // A hard-deleted username can be re-created; its old refresh families must not survive.
        verify(refreshTokenRepository).invalidateAllForUser("root");
    }

    @Test
    void noAdmin_existingInactiveUser_isReactivatedPromotedAndItsSessionsRevoked() {
        var existing = new AppUser("viewer", "old-hash", Role.VIEWER);
        existing.deactivate();
        when(userRepository.findByUsername("viewer")).thenReturn(Optional.of(existing));

        provisioner("viewer", PASSWORD).run(null);

        var saved = ArgumentCaptor.forClass(AppUser.class);
        verify(userRepository).save(saved.capture());
        assertSame(existing, saved.getValue(), "must update the existing row, not insert a duplicate");
        assertEquals(Role.ADMIN, saved.getValue().getRole());
        assertTrue(saved.getValue().isActive());
        assertEquals("bcrypt:" + PASSWORD, saved.getValue().getPassword());
        // A session opened with the old password must not come back — least of all as ADMIN.
        verify(refreshTokenRepository).invalidateAllForUser("viewer");
    }
}
