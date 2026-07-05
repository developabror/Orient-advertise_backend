package uz.orientadvertise.services.domain.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import uz.orientadvertise.services.domain.model.AppUser;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByUsername(String username);

    Optional<AppUser> findByUsernameAndIsActiveTrue(String username);

    boolean existsByUsernameAndIsActiveTrue(String username);

    /**
     * Lookup by recovery email, active users only — the forgot-password entry point. The
     * argument MUST be already lowercased/trimmed (see {@link AppUser#setEmail}); the column
     * stores normalized values so no functional LOWER() index is needed.
     */
    Optional<AppUser> findByEmailAndIsActiveTrue(String email);

    /** Uniqueness guard for set-email / create-user. Argument must be already normalized. */
    boolean existsByEmail(String email);
}
