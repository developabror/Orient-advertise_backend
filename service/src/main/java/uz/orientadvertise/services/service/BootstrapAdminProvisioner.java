package uz.orientadvertise.services.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.orientadvertise.services.common.exception.IllegalConfigurationException;
import uz.orientadvertise.services.domain.auth.Role;
import uz.orientadvertise.services.domain.model.AppUser;
import uz.orientadvertise.services.domain.repository.AppUserRepository;
import uz.orientadvertise.services.infra.auth.RefreshTokenRepository;

/**
 * AUTH-01: provisions the first ADMIN from {@code APP_BOOTSTRAP_ADMIN_USERNAME} /
 * {@code APP_BOOTSTRAP_ADMIN_PASSWORD} instead of from a password committed in a migration.
 *
 * <p>Acts only when no active ADMIN exists, so a running system is never touched. If the
 * username already exists (e.g. an account V45 deactivated) it is re-activated, promoted to
 * ADMIN and given the configured password; otherwise a new ADMIN is created. Either way every
 * refresh-token family of that username is revoked, exactly as a password change does — a
 * session opened with the old (possibly public) password must not survive, and a promoted
 * account's old session must not come back as ADMIN. Misconfiguration fails startup, and the
 * message names the variables, never the password.
 */
@Component
public class BootstrapAdminProvisioner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminProvisioner.class);

    static final int MIN_PASSWORD_LENGTH = 12;

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenRepository refreshTokenRepository;
    private final String username;
    private final String password;

    public BootstrapAdminProvisioner(AppUserRepository userRepository,
                                     PasswordEncoder passwordEncoder,
                                     RefreshTokenRepository refreshTokenRepository,
                                     @Value("${app.bootstrap-admin.username:}") String username,
                                     @Value("${app.bootstrap-admin.password:}") String password) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenRepository = refreshTokenRepository;
        this.username = username == null ? "" : username.trim();
        this.password = password == null ? "" : password;
        if (this.username.isEmpty() != this.password.isEmpty()) {
            throw new IllegalConfigurationException(
                    "APP_BOOTSTRAP_ADMIN_USERNAME and APP_BOOTSTRAP_ADMIN_PASSWORD must be set together");
        }
        if (this.password.isEmpty()) {
            return;
        }
        // A stray space or '\r' from an .env file would become part of a password nobody can
        // type — and once that admin exists, fixing the env and restarting changes nothing.
        if (this.password.isBlank() || !this.password.equals(this.password.strip())) {
            throw new IllegalConfigurationException(
                    "APP_BOOTSTRAP_ADMIN_PASSWORD must not start or end with whitespace");
        }
        if (this.password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalConfigurationException(
                    "APP_BOOTSTRAP_ADMIN_PASSWORD must be at least " + MIN_PASSWORD_LENGTH + " characters long");
        }
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.existsByRoleAndIsActiveTrue(Role.ADMIN)) {
            if (!username.isEmpty()) {
                log.warn("APP_BOOTSTRAP_ADMIN_USERNAME / APP_BOOTSTRAP_ADMIN_PASSWORD are set but unused "
                        + "(an active ADMIN exists). Remove them from the environment.");
            }
            return;
        }
        if (username.isEmpty()) {
            log.warn("No active ADMIN account exists. Set APP_BOOTSTRAP_ADMIN_USERNAME and "
                    + "APP_BOOTSTRAP_ADMIN_PASSWORD to provision one on the next start.");
            return;
        }

        String encoded = passwordEncoder.encode(password);
        var existing = userRepository.findByUsername(username);
        if (existing.isPresent()) {
            AppUser user = existing.get();
            if (user.getRole() != Role.ADMIN) {
                log.warn("Bootstrap admin: promoting existing user '{}' from {} to ADMIN", username, user.getRole());
            }
            user.changePassword(encoded);
            user.setRole(Role.ADMIN);
            user.activate();
            userRepository.save(user);
        } else {
            userRepository.save(new AppUser(username, encoded, Role.ADMIN));
        }
        refreshTokenRepository.invalidateAllForUser(username);
        log.warn("Bootstrap admin '{}' provisioned because no active ADMIN existed. Log in, then remove "
                + "APP_BOOTSTRAP_ADMIN_USERNAME / APP_BOOTSTRAP_ADMIN_PASSWORD from the environment.", username);
    }
}
