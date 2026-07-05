package uz.orientadvertise.services.service;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import uz.orientadvertise.services.common.exception.AuthenticationException;
import uz.orientadvertise.services.domain.auth.Role;
import uz.orientadvertise.services.domain.model.AppUser;
import uz.orientadvertise.services.domain.notification.PasswordMailSender;
import uz.orientadvertise.services.domain.repository.AppUserRepository;
import uz.orientadvertise.services.infra.auth.PasswordResetTokenRepository;
import uz.orientadvertise.services.infra.auth.RefreshTokenRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PasswordServiceTest {

    private AppUserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private RefreshTokenRepository refreshTokenRepository;
    private PasswordResetTokenRepository resetTokenRepository;
    private PasswordMailSender mailSender;
    private PasswordResetRateLimiter rateLimiter;
    private PasswordService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(AppUserRepository.class);
        passwordEncoder = new BCryptPasswordEncoder();
        refreshTokenRepository = mock(RefreshTokenRepository.class);
        resetTokenRepository = mock(PasswordResetTokenRepository.class);
        mailSender = mock(PasswordMailSender.class);
        rateLimiter = mock(PasswordResetRateLimiter.class);
        service = new PasswordService(userRepository, passwordEncoder, refreshTokenRepository,
                resetTokenRepository, mailSender, rateLimiter,
                Runnable::run,           // synchronous executor so mail sends are verifiable inline
                30, "http://localhost:5173");
    }

    private AppUser activeUser(String username, String rawPassword, String email) {
        var user = new AppUser(username, passwordEncoder.encode(rawPassword), Role.ADMIN);
        if (email != null) {
            user.setEmail(email);
        }
        return user;
    }

    // --- changeOwnPassword ---------------------------------------------------

    @Test
    void changeOwnPassword_wrongCurrent_throws400() {
        when(userRepository.findByUsernameAndIsActiveTrue("alice"))
                .thenReturn(Optional.of(activeUser("alice", "oldpass1", null)));

        var ex = assertThrows(IllegalArgumentException.class, () ->
                service.changeOwnPassword("alice", "wrongpass", "newpass12", "newpass12"));
        assertEquals("Current password is incorrect", ex.getMessage());
    }

    @Test
    void changeOwnPassword_mismatch_throws400() {
        // Validation runs before any repository access — user lookup never happens.
        assertThrows(IllegalArgumentException.class, () ->
                service.changeOwnPassword("alice", "oldpass1", "newpass12", "different34"));
        verify(userRepository, never()).findByUsernameAndIsActiveTrue(anyString());
    }

    @Test
    void changeOwnPassword_policyTooShort_throws400() {
        assertThrows(IllegalArgumentException.class, () ->
                service.changeOwnPassword("alice", "oldpass1", "short", "short"));
    }

    @Test
    void changeOwnPassword_sameAsCurrent_throws400() {
        when(userRepository.findByUsernameAndIsActiveTrue("alice"))
                .thenReturn(Optional.of(activeUser("alice", "samepass1", null)));

        var ex = assertThrows(IllegalArgumentException.class, () ->
                service.changeOwnPassword("alice", "samepass1", "samepass1", "samepass1"));
        assertEquals("New password must differ from the current one", ex.getMessage());
    }

    @Test
    void changeOwnPassword_userGone_throws401() {
        when(userRepository.findByUsernameAndIsActiveTrue("ghost")).thenReturn(Optional.empty());
        assertThrows(AuthenticationException.class, () ->
                service.changeOwnPassword("ghost", "oldpass1", "newpass12", "newpass12"));
    }

    @Test
    void changeOwnPassword_success_reEncodesRevokesSessionsAndNotifies() {
        var user = activeUser("alice", "oldpass1", "alice@example.com");
        when(userRepository.findByUsernameAndIsActiveTrue("alice")).thenReturn(Optional.of(user));

        service.changeOwnPassword("alice", "oldpass1", "newpass12", "newpass12");

        assertTrue(passwordEncoder.matches("newpass12", user.getPassword()), "password must be re-encoded");
        verify(refreshTokenRepository).invalidateAllForUser("alice");
        verify(mailSender).sendPasswordChangedNotice("alice@example.com", "alice");
    }

    // --- requestReset --------------------------------------------------------

    @Test
    void requestReset_unknownEmail_noThrow_noToken_noSend() {
        when(userRepository.findByEmailAndIsActiveTrue("ghost@example.com")).thenReturn(Optional.empty());

        service.requestReset("ghost@example.com", "1.2.3.4");

        verify(rateLimiter).checkForgotAllowed("1.2.3.4", "ghost@example.com");
        verify(resetTokenRepository, never()).issue(anyString(), any());
        verify(mailSender, never()).sendPasswordResetLink(anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void requestReset_knownEmail_issuesTokenAndSendsLink() {
        var user = activeUser("alice", "oldpass1", "alice@example.com");
        when(userRepository.findByEmailAndIsActiveTrue("alice@example.com")).thenReturn(Optional.of(user));
        when(resetTokenRepository.issue("alice", Duration.ofMinutes(30))).thenReturn("RAWTOKEN123");

        // Mixed-case input is normalized before lookup.
        service.requestReset("Alice@Example.com", "1.2.3.4");

        verify(resetTokenRepository).issue("alice", Duration.ofMinutes(30));
        verify(mailSender).sendPasswordResetLink(
                "alice@example.com", "alice",
                "http://localhost:5173/reset-password?token=RAWTOKEN123", 30);
    }

    // --- resetPassword -------------------------------------------------------

    @Test
    void resetPassword_invalidOrUsedToken_throws400() {
        when(resetTokenRepository.consume("bad-token")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () ->
                service.resetPassword("bad-token", "newpass12", "newpass12"));
    }

    @Test
    void resetPassword_success_consumesTokenRevokesSessionsAndNotifies() {
        var user = activeUser("alice", "oldpass1", "alice@example.com");
        when(resetTokenRepository.consume("good-token")).thenReturn(Optional.of("alice"));
        when(userRepository.findByUsernameAndIsActiveTrue("alice")).thenReturn(Optional.of(user));

        service.resetPassword("good-token", "newpass12", "newpass12");

        assertTrue(passwordEncoder.matches("newpass12", user.getPassword()));
        verify(refreshTokenRepository).invalidateAllForUser("alice");
        verify(mailSender).sendPasswordChangedNotice("alice@example.com", "alice");
    }

    @Test
    void resetPassword_userVanished_throws400Generic_notLeaky() {
        when(resetTokenRepository.consume("good-token")).thenReturn(Optional.of("alice"));
        when(userRepository.findByUsernameAndIsActiveTrue("alice")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () ->
                service.resetPassword("good-token", "newpass12", "newpass12"));
    }

    // --- isResetTokenValid ---------------------------------------------------

    @Test
    void isResetTokenValid_reflectsPeek() {
        when(resetTokenRepository.peekUsername("live")).thenReturn(Optional.of("alice"));
        when(resetTokenRepository.peekUsername("dead")).thenReturn(Optional.empty());
        assertTrue(service.isResetTokenValid("live"));
        assertFalse(service.isResetTokenValid("dead"));
    }

    // --- setOwnEmail ---------------------------------------------------------

    @Test
    void setOwnEmail_duplicate_throws409() {
        when(userRepository.findByUsernameAndIsActiveTrue("alice"))
                .thenReturn(Optional.of(activeUser("alice", "oldpass1", null)));
        when(userRepository.existsByEmail("dup@example.com")).thenReturn(true);

        assertThrows(IllegalStateException.class, () -> service.setOwnEmail("alice", "dup@example.com"));
    }

    @Test
    void setOwnEmail_blank_clearsEmail() {
        var user = activeUser("alice", "oldpass1", "old@example.com");
        when(userRepository.findByUsernameAndIsActiveTrue("alice")).thenReturn(Optional.of(user));

        service.setOwnEmail("alice", "   ");

        assertEquals(null, user.getEmail());
    }

    @Test
    void setOwnEmail_sameAsCurrent_allowed_skipsDuplicateCheck() {
        var user = activeUser("alice", "oldpass1", "alice@example.com");
        when(userRepository.findByUsernameAndIsActiveTrue("alice")).thenReturn(Optional.of(user));
        // Even if the address "exists", re-submitting your OWN email must not 409 — the
        // !equals(current) short-circuit must skip the uniqueness check entirely.
        when(userRepository.existsByEmail(anyString())).thenReturn(true);

        service.setOwnEmail("alice", "Alice@Example.com");

        assertEquals("alice@example.com", user.getEmail());
        verify(userRepository, never()).existsByEmail(anyString());
    }

    @Test
    void setOwnEmail_new_normalizesAndSets() {
        var user = activeUser("alice", "oldpass1", null);
        when(userRepository.findByUsernameAndIsActiveTrue("alice")).thenReturn(Optional.of(user));
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);

        service.setOwnEmail("alice", "  New@Example.com ");

        assertEquals("new@example.com", user.getEmail());
    }
}
