package uz.orientadvertise.services.service;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.orientadvertise.services.common.exception.AuthenticationException;
import uz.orientadvertise.services.domain.auth.LoginRequest;
import uz.orientadvertise.services.domain.auth.Role;
import uz.orientadvertise.services.domain.model.AppUser;
import uz.orientadvertise.services.domain.repository.AppUserRepository;
import uz.orientadvertise.services.infra.auth.JwtTokenProvider;
import uz.orientadvertise.services.infra.auth.RefreshTokenRepository;
import uz.orientadvertise.services.infra.auth.RefreshTokenRepository.RefreshTokenData;
import uz.orientadvertise.services.service.seed.DefaultLoginPolicy;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    private JwtTokenProvider jwtTokenProvider;
    private RefreshTokenRepository refreshTokenRepository;
    private AppUserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        jwtTokenProvider = mock(JwtTokenProvider.class);
        refreshTokenRepository = mock(RefreshTokenRepository.class);
        userRepository = mock(AppUserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        authService = new AuthService(jwtTokenProvider, refreshTokenRepository, userRepository,
                passwordEncoder);
    }

    @Test
    void login_success_returnsTokenPairWithRole() {
        var user = new AppUser("admin", "stored-hash", Role.ADMIN);
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password", "stored-hash")).thenReturn(true);
        when(jwtTokenProvider.createAccessToken("admin", Role.ADMIN)).thenReturn("access-jwt");
        when(refreshTokenRepository.store(anyString(), anyString())).thenReturn("refresh-id");

        var result = authService.login(new LoginRequest("admin", "password"));

        assertNotNull(result);
        assertEquals("access-jwt", result.accessToken());
    }

    /**
     * Guards the V33 seed migration: the committed BCrypt hash must verify against
     * 'password' with the SAME encoder the app wires (BCryptPasswordEncoder), or every
     * dev login breaks after the migration runs. The hash lives in {@link DefaultLoginPolicy},
     * whose own test pins it to the V33/V45 literals.
     */
    @Test
    void bcryptSeedHash_matchesPassword() {
        var realEncoder = new BCryptPasswordEncoder();
        assertTrue(realEncoder.matches("password", DefaultLoginPolicy.DEFAULT_PASSWORD_HASH));
    }

    @Test
    void login_invalidPassword_throws401() {
        var user = new AppUser("admin", "password", Role.ADMIN);
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));

        assertThrows(AuthenticationException.class, () ->
                authService.login(new LoginRequest("admin", "wrong")));
    }

    @Test
    void login_deactivatedUser_throws401_genericMessage() {
        var user = new AppUser("deactivated", "stored-hash", Role.VIEWER);
        user.deactivate();
        when(userRepository.findByUsername("deactivated")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password", "stored-hash")).thenReturn(true);

        var ex = assertThrows(AuthenticationException.class, () ->
                authService.login(new LoginRequest("deactivated", "password")));
        // Generic message — a deactivated account is indistinguishable from a wrong password
        // (no account enumeration).
        assertEquals("Invalid credentials", ex.getMessage());
    }

    @Test
    void login_unknownUser_throws401() {
        when(userRepository.findByUsername("unknown")).thenReturn(Optional.empty());

        assertThrows(AuthenticationException.class, () ->
                authService.login(new LoginRequest("unknown", "password")));
    }

    @Test
    void refresh_deactivatedUser_rejectsAndInvalidatesFamily() {
        var tokenData = new RefreshTokenData("tok", "fam-1", "user1", System.currentTimeMillis());
        when(refreshTokenRepository.find("tok")).thenReturn(Optional.of(tokenData));
        var user = new AppUser("user1", "password", Role.VIEWER);
        user.deactivate();
        when(userRepository.findByUsername("user1")).thenReturn(Optional.of(user));

        assertThrows(AuthenticationException.class, () -> authService.refresh("tok"));
        verify(refreshTokenRepository).invalidateFamily("fam-1");
    }

    @Test
    void refresh_activeUser_usesCurrentRole() {
        var tokenData = new RefreshTokenData("tok", "fam-1", "op", System.currentTimeMillis());
        when(refreshTokenRepository.find("tok")).thenReturn(Optional.of(tokenData));
        var user = new AppUser("op", "password", Role.ADMIN);
        when(userRepository.findByUsername("op")).thenReturn(Optional.of(user));
        when(jwtTokenProvider.createAccessToken("op", Role.ADMIN)).thenReturn("new-access");
        when(refreshTokenRepository.store("op", "fam-1")).thenReturn("new-refresh");

        var result = authService.refresh("tok");

        assertEquals("new-access", result.accessToken());
        verify(jwtTokenProvider).createAccessToken("op", Role.ADMIN);
    }
}
