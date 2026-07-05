package uz.orientadvertise.services.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.auth.Role;
import uz.orientadvertise.services.domain.model.AppUser;
import uz.orientadvertise.services.domain.repository.AppUserRepository;

/**
 * Admin-only user lifecycle: create, delete (with access cascade).
 *
 * <p>Account deletion is a soft delete (deactivate) on the user row plus a hard delete of
 * any {@code AdvertiserContentAccess} grants — the user record is retained so that
 * historical events, incidents, and playback rows continue to resolve their actor, while
 * the FK to advertiser_content_access is cleared so the username could be re-issued without
 * stale grants leaking back in.
 */
@Service
public class UserManagementService {

    private static final Logger log = LoggerFactory.getLogger(UserManagementService.class);

    private final AppUserRepository userRepository;
    private final AdvertiserContentService advertiserContentService;
    private final OperatorContentService operatorContentService;
    private final ProjectOperatorService projectOperatorService;
    private final PasswordEncoder passwordEncoder;

    public UserManagementService(AppUserRepository userRepository,
                                  AdvertiserContentService advertiserContentService,
                                  OperatorContentService operatorContentService,
                                  ProjectOperatorService projectOperatorService,
                                  PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.advertiserContentService = advertiserContentService;
        this.operatorContentService = operatorContentService;
        this.projectOperatorService = projectOperatorService;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Paginated user list for the admin dashboard. Includes deactivated rows so an
     * admin can see and manage them; the {@code active} flag on each result tells the
     * UI which to render disabled.
     */
    @Transactional(readOnly = true)
    public Page<AppUser> list(Pageable pageable) {
        return userRepository.findAll(pageable);
    }

    /**
     * Detail lookup for the admin/operator dashboards. Soft-deleted (deactivated) users
     * surface as 404 — callers that need to render disabled rows should rely on the
     * paginated list, which intentionally includes them.
     */
    @Transactional(readOnly = true)
    public AppUser getById(Long userId) {
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        if (!user.isActive()) {
            throw new ResourceNotFoundException("User", userId);
        }
        return user;
    }

    @Transactional
    public AppUser create(String username, String password, Role role, String email) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username is required");
        }
        if (password == null || password.length() < 6) {
            throw new IllegalArgumentException("Password must be at least 6 characters");
        }
        if (role == null) {
            throw new IllegalArgumentException("Role is required");
        }
        if (userRepository.findByUsername(username).isPresent()) {
            throw new IllegalStateException("Username already exists: " + username);
        }
        // Recovery email is optional. Normalize (lowercase/trim) and reject a collision so the
        // forgot-password lookup stays unambiguous. NULL email is fine (no collision).
        String normalizedEmail = (email == null || email.isBlank()) ? null : email.trim().toLowerCase();
        if (normalizedEmail != null && userRepository.existsByEmail(normalizedEmail)) {
            throw new IllegalStateException("Email already in use: " + normalizedEmail);
        }
        // Store a BCrypt hash, never the raw password — AuthService.login verifies with
        // passwordEncoder.matches(raw, stored), so an unencoded password can never log in.
        var user = new AppUser(username, passwordEncoder.encode(password), role);
        if (normalizedEmail != null) {
            user.setEmail(normalizedEmail);
        }
        user = userRepository.save(user);
        log.info("Created user [username={} role={} id={}]", username, role, user.getId());
        return user;
    }

    /**
     * Delete a user. For ADVERTISERs this also drops every content access grant — see the
     * service-level Javadoc for the cascade rationale. For non-advertisers the cascade
     * call is a no-op (advertiser_content_access rows only exist for the ADVERTISER role).
     */
    @Transactional
    public void delete(Long userId) {
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        if (user.getRole() == Role.ADVERTISER) {
            advertiserContentService.revokeAllForUser(userId);
        }
        if (user.getRole() == Role.OPERATOR) {
            // Mirror the advertiser cascade: drop the operator's content grants and project
            // assignments so the non-cascading FKs don't block the delete and no stale rows
            // leak back if the username is re-issued.
            operatorContentService.revokeAllForUser(userId);
            projectOperatorService.revokeAllForUser(userId);
        }
        userRepository.delete(user);
        log.info("Deleted user [username={} role={} id={}]", user.getUsername(), user.getRole(), userId);
    }
}
