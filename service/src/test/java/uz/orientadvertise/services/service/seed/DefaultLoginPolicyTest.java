package uz.orientadvertise.services.service.seed;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.orientadvertise.services.common.exception.IllegalConfigurationException;
import uz.orientadvertise.services.domain.auth.Role;
import uz.orientadvertise.services.domain.model.AppUser;
import uz.orientadvertise.services.domain.repository.AppUserRepository;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uz.orientadvertise.services.service.seed.DefaultLoginPolicy.DEFAULT_PASSWORD_HASH;

class DefaultLoginPolicyTest {

    private AppUserRepository userRepository;
    private SeedProperties seedProperties;
    private DefaultLoginPolicy policy;

    @BeforeEach
    void setUp() {
        userRepository = mock(AppUserRepository.class);
        seedProperties = new SeedProperties();
        policy = new DefaultLoginPolicy(seedProperties, userRepository);
    }

    private static AppUser inactive(String username, String hash) {
        var user = new AppUser(username, hash, Role.ADMIN);
        user.deactivate();
        return user;
    }

    // ----- seed off: guard -----

    @Test
    void seedOff_activeDefaultLogins_refusesToStartAndNamesThem() {
        var admin = new AppUser("admin", DEFAULT_PASSWORD_HASH, Role.ADMIN);
        var viewer = new AppUser("viewer", DEFAULT_PASSWORD_HASH, Role.VIEWER);
        when(userRepository.findByPasswordAndIsActiveTrue(DEFAULT_PASSWORD_HASH)).thenReturn(List.of(viewer, admin));

        var ex = assertThrows(IllegalConfigurationException.class, policy::afterSingletonsInstantiated);

        assertTrue(ex.getMessage().contains("[admin, viewer]"), ex.getMessage());
        assertTrue(ex.getMessage().contains("UPDATE app_user SET is_active = FALSE"), "message must name the way out");
        assertFalse(ex.getMessage().contains("APP_SEED_ENABLED"),
                "must never steer a real deployment to seed mode, which re-enables every default login");
        assertFalse(ex.getMessage().contains(DEFAULT_PASSWORD_HASH), "message must never contain the hash");
    }

    @Test
    void seedOff_noDefaultLogins_starts() {
        when(userRepository.findByPasswordAndIsActiveTrue(DEFAULT_PASSWORD_HASH)).thenReturn(List.of());

        assertDoesNotThrow(policy::afterSingletonsInstantiated);
        verify(userRepository, never()).save(any());
    }

    // ----- seed on: dev re-activation -----

    @Test
    void seedOn_inactiveDevAccountOnDefaultHash_isReactivated() {
        seedProperties.setEnabled(true);
        var admin = inactive("admin", DEFAULT_PASSWORD_HASH);
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));

        policy.afterSingletonsInstantiated();

        var saved = ArgumentCaptor.forClass(AppUser.class);
        verify(userRepository).save(saved.capture());
        assertEquals("admin", saved.getValue().getUsername());
        assertTrue(saved.getValue().isActive());
    }

    @Test
    void seedOn_devAccountWithChangedPassword_isLeftAlone() {
        seedProperties.setEnabled(true);
        var admin = inactive("admin", "$2a$10$someoneChangedThisPassword");
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));

        policy.afterSingletonsInstantiated();

        verify(userRepository, never()).save(any());
        assertFalse(admin.isActive());
    }

    @Test
    void seedOn_alreadyActiveDevAccount_isNotSavedAgain() {
        seedProperties.setEnabled(true);
        var admin = new AppUser("admin", DEFAULT_PASSWORD_HASH, Role.ADMIN);
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));

        policy.afterSingletonsInstantiated();

        verify(userRepository, never()).save(any());
    }

    @Test
    void seedOn_neverTouchesTheDeactivatedAccountOrRunsTheGuard() {
        seedProperties.setEnabled(true);

        assertDoesNotThrow(policy::afterSingletonsInstantiated);
        verify(userRepository, never()).findByUsername("deactivated");
        verify(userRepository, never()).findByPasswordAndIsActiveTrue(any());
    }

    // ----- the constant (BCrypt-of-'password' is pinned in AuthServiceTest) -----

    @Test
    void defaultHash_matchesTheHashInV33AndV45() throws IOException {
        // The guard, the dev re-activation and V45 must agree on one hash; a drift in any of
        // them silently re-opens AUTH-01.
        assertTrue(migration("V33__bcrypt_seed_passwords.sql").contains(DEFAULT_PASSWORD_HASH));
        assertTrue(migration("V45__deactivate_default_seed_logins.sql").contains(DEFAULT_PASSWORD_HASH));
    }

    private static String migration(String file) throws IOException {
        try (InputStream in = DefaultLoginPolicyTest.class.getClassLoader()
                .getResourceAsStream("db/migration/" + file)) {
            assertNotNull(in, "migration not on the classpath: " + file);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
