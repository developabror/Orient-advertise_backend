package uz.orientadvertise.services.service;

import java.util.Collection;
import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.orientadvertise.services.domain.auth.Role;
import uz.orientadvertise.services.domain.model.AppUser;
import uz.orientadvertise.services.domain.repository.AppUserRepository;
import uz.orientadvertise.services.domain.repository.ProjectOperatorRepository;

/**
 * Resolves the caller's project scope once per request and hands it to every project-scoped
 * read/list/mutation. The contract is <b>fail-closed</b>: an unknown, deactivated, or
 * null/blank caller resolves to {@code restricted} with an <b>empty</b> project set (which
 * yields empty lists / 404s everywhere), never to unrestricted.
 *
 * <ul>
 *   <li>{@code ADMIN} → unrestricted (no narrowing).</li>
 *   <li>{@code OPERATOR} → restricted to the assigned project set (possibly empty).</li>
 *   <li>{@code VIEWER} / {@code ADVERTISER} → unrestricted here (advertiser content scoping
 *       stays in its own branch; this resolver does not narrow them).</li>
 * </ul>
 */
@Service
public class OperatorScopeResolver {

    private final AppUserRepository userRepository;
    private final ProjectOperatorRepository projectOperatorRepository;

    public OperatorScopeResolver(AppUserRepository userRepository,
                                 ProjectOperatorRepository projectOperatorRepository) {
        this.userRepository = userRepository;
        this.projectOperatorRepository = projectOperatorRepository;
    }

    /** HTTP path: derive the username from the security context, then resolve. */
    @Transactional(readOnly = true)
    public ScopedProjects resolve() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String username = (auth != null) ? auth.getName() : null;
        return resolveForUsername(username);
    }

    /**
     * Context-free path (WebSocket handshake, system threads). MUST NOT read the
     * SecurityContext — the caller supplies the username explicitly.
     */
    @Transactional(readOnly = true)
    public ScopedProjects resolveForUsername(String username) {
        if (username == null || username.isBlank()) {
            return ScopedProjects.failClosed(username);
        }
        AppUser user = userRepository.findByUsernameAndIsActiveTrue(username).orElse(null);
        if (user == null) {
            return ScopedProjects.failClosed(username);
        }
        Role role = user.getRole();
        if (role == Role.OPERATOR) {
            List<Long> projectIds = projectOperatorRepository.findProjectIdsByUserId(user.getId());
            return new ScopedProjects(username, role, List.copyOf(projectIds), true);
        }
        // ADMIN / VIEWER / ADVERTISER (and any future role) — not narrowed by project scope.
        return new ScopedProjects(username, role, null, false);
    }

    /**
     * Resolved caller scope. {@code restricted} callers see only {@code projectIds}
     * (non-null, possibly empty); unrestricted callers carry {@code projectIds == null}.
     */
    public record ScopedProjects(String username, Role role, List<Long> projectIds, boolean restricted) {

        /** Fail-closed: restricted with an empty project set (unknown/inactive/null caller). */
        static ScopedProjects failClosed(String username) {
            return new ScopedProjects(username, null, List.of(), true);
        }

        /** True when a restricted caller has zero projects — every scoped result is empty. */
        public boolean isEmptyScope() {
            return restricted && projectIds.isEmpty();
        }

        /** Narrowing ids for repository queries: the project set when restricted, else {@code null} (unrestricted). */
        public Collection<Long> narrowingIds() {
            return restricted ? projectIds : null;
        }

        /** True when this caller may NOT see the given project (out of scope). */
        public boolean excludes(Long projectId) {
            return restricted && (projectId == null || !projectIds.contains(projectId));
        }

        /** Per-username dashboard cache key for restricted callers; one shared key otherwise. */
        public String cacheKey() {
            return restricted ? "summary:" + (username != null ? username : "_unknown") : "summary:_global";
        }
    }
}
