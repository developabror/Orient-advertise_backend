package uz.orientadvertise.services.service.seed;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;
import uz.orientadvertise.services.common.exception.IllegalConfigurationException;
import uz.orientadvertise.services.domain.model.AppUser;
import uz.orientadvertise.services.domain.repository.AppUserRepository;

/**
 * AUTH-01: the V12/V33 default logins ({@code admin}/{@code password} and friends) exist only
 * where seed data is enabled.
 *
 * <ul>
 *   <li><b>Seed on</b> ({@code app.seed.enabled=true}, dev): re-activates the accounts V12 created
 *       active and V45 deactivated, as long as they are still on {@link #DEFAULT_PASSWORD_HASH}.
 *       An account that was deleted or given a real password is left alone.</li>
 *   <li><b>Seed off</b>: refuses to start while any active account is still on that hash.</li>
 * </ul>
 *
 * <p>Runs as a {@link SmartInitializingSingleton}: after Flyway (the repository needs the
 * EntityManagerFactory, which waits for the migration) and before the web server opens its
 * port or any {@code ApplicationRunner} fires — so a refused start never serves a request, and
 * the dev re-activation is done before {@code BootstrapAdminProvisioner} looks for an admin.
 *
 * <p>Known gap: only the exact V33 hash is recognised. A password later set back to
 * {@code password} gets a fresh salt and is not caught here.
 */
@Component
public class DefaultLoginPolicy implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(DefaultLoginPolicy.class);

    /**
     * BCrypt of {@code password}, written by V33 and matched by V45. Public: it is committed in
     * the migrations, which is exactly why no active non-dev account may hold it.
     */
    public static final String DEFAULT_PASSWORD_HASH =
            "$2b$10$7G6HLwBeJv/5nByvd/R43.2TjtngYZFWjpO5ExQR5U5B4r9YUxUIS";

    /** The accounts V12 created active. {@code deactivated} was created inactive and stays that way. */
    static final List<String> DEV_USERNAMES =
            List.of("developabror@gmail.com", "admin", "operator", "viewer", "advertiser");

    private final SeedProperties seedProperties;
    private final AppUserRepository userRepository;

    public DefaultLoginPolicy(SeedProperties seedProperties, AppUserRepository userRepository) {
        this.seedProperties = seedProperties;
        this.userRepository = userRepository;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (seedProperties.isEnabled()) {
            reactivateDevLogins();
        } else {
            refuseActiveDefaultLogins();
        }
    }

    private void reactivateDevLogins() {
        for (String username : DEV_USERNAMES) {
            userRepository.findByUsername(username)
                    .filter(u -> !u.isActive() && DEFAULT_PASSWORD_HASH.equals(u.getPassword()))
                    .ifPresent(u -> {
                        u.activate();
                        userRepository.save(u);
                    });
        }
        log.warn("Seed data is enabled: default dev logins ({}) use the password 'password'. "
                + "Never enable APP_SEED_ENABLED in production.", DEV_USERNAMES);
    }

    private void refuseActiveDefaultLogins() {
        List<String> offenders = userRepository.findByPasswordAndIsActiveTrue(DEFAULT_PASSWORD_HASH)
                .stream().map(AppUser::getUsername).sorted().toList();
        if (!offenders.isEmpty()) {
            // Never suggest APP_SEED_ENABLED=true here: on a real deployment that re-enables every
            // default login and seeds fake devices.
            throw new IllegalConfigurationException(
                    "Refusing to start: active account(s) " + offenders + " still use the public default "
                            + "password. Deactivate them (UPDATE app_user SET is_active = FALSE WHERE username IN "
                            + "(...)), then start again; use APP_BOOTSTRAP_ADMIN_USERNAME / "
                            + "APP_BOOTSTRAP_ADMIN_PASSWORD if that leaves no active ADMIN.");
        }
    }
}
