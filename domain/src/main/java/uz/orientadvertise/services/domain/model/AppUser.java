package uz.orientadvertise.services.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import uz.orientadvertise.services.domain.auth.Role;

@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String username;

    @Column(nullable = false, length = 200)
    private String password;

    // Recovery email — nullable, stored lowercased + trimmed (see setEmail) so the plain
    // UNIQUE constraint (ux_app_user_email, V39) is portable to H2 PG-mode without relying
    // on functional LOWER() indexes. Keyed by forgot-password; null until the user sets one.
    @Column(length = 255, unique = true)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(nullable = false)
    private boolean isActive;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected AppUser() {
    }

    public AppUser(String username, String password, Role role) {
        this.username = username;
        this.password = password;
        this.role = role;
        this.isActive = true;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getPassword() { return password; }
    public String getEmail() { return email; }
    public Role getRole() { return role; }
    public boolean isActive() { return isActive; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setRole(Role role) { this.role = role; this.updatedAt = Instant.now(); }

    /**
     * Normalize and set the recovery email. Lowercases + trims; a null/blank input clears
     * the email to {@code null} (NULLs don't collide under the UNIQUE constraint). Bumps
     * {@code updatedAt}. Storing the already-normalized value keeps the plain unique
     * constraint correct without a functional LOWER() index.
     */
    public void setEmail(String email) {
        this.email = (email == null || email.isBlank()) ? null : email.trim().toLowerCase();
        this.updatedAt = Instant.now();
    }

    /**
     * Replace the stored password hash. Caller is responsible for encoding (BCrypt) — this
     * mutator never sees a raw password. Mirrors the setter-less-field idiom of {@link #setRole}.
     */
    public void changePassword(String encodedPassword) {
        this.password = encodedPassword;
        this.updatedAt = Instant.now();
    }

    public void deactivate() {
        this.isActive = false;
        this.updatedAt = Instant.now();
    }

    public void activate() {
        this.isActive = true;
        this.updatedAt = Instant.now();
    }
}
