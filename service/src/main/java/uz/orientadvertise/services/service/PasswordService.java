package uz.orientadvertise.services.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.orientadvertise.services.common.exception.AuthenticationException;
import uz.orientadvertise.services.domain.model.AppUser;
import uz.orientadvertise.services.domain.notification.PasswordMailSender;
import uz.orientadvertise.services.domain.repository.AppUserRepository;
import uz.orientadvertise.services.infra.auth.PasswordResetTokenRepository;
import uz.orientadvertise.services.infra.auth.RefreshTokenRepository;

/**
 * Self-service password management: authenticated change, unauthenticated forgot/reset, and
 * recovery-email maintenance. The standard posture on any successful change/reset is "rotate
 * creds → kill all sessions" — every refresh family for the user is invalidated; the access
 * token (≤15 min) expires on its own and the FE re-logs-in.
 *
 * <p><b>No account enumeration.</b> {@link #requestReset} returns {@code void} and the
 * controller answers 202 whether or not the email matched. The reset-link email is dispatched
 * <em>off the request thread</em> so the known-email path isn't measurably slower than the
 * unknown one (a timing oracle would defeat the no-enumeration guarantee).
 */
@Service
public class PasswordService {

    private static final Logger log = LoggerFactory.getLogger(PasswordService.class);
    private static final int MIN_LEN = 8;
    private static final int MAX_LEN = 200;
    /** Identical generic message on every invalid/expired/used/missing token path (no leaks). */
    private static final String INVALID_TOKEN_MSG = "This reset link is invalid or has expired. Request a new one.";

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordResetTokenRepository resetTokenRepository;
    private final PasswordMailSender mailSender;
    private final PasswordResetRateLimiter rateLimiter;
    private final Executor mailExecutor;
    private final long resetTokenTtlMinutes;
    private final String frontendBaseUrl;

    public PasswordService(AppUserRepository userRepository,
                           PasswordEncoder passwordEncoder,
                           RefreshTokenRepository refreshTokenRepository,
                           PasswordResetTokenRepository resetTokenRepository,
                           PasswordMailSender mailSender,
                           PasswordResetRateLimiter rateLimiter,
                           @Qualifier("auditExecutor") Executor mailExecutor,
                           @Value("${app.mail.reset-token-ttl-minutes:30}") long resetTokenTtlMinutes,
                           @Value("${app.frontend.base-url:http://localhost:5173}") String frontendBaseUrl) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenRepository = refreshTokenRepository;
        this.resetTokenRepository = resetTokenRepository;
        this.mailSender = mailSender;
        this.rateLimiter = rateLimiter;
        this.mailExecutor = mailExecutor;
        this.resetTokenTtlMinutes = resetTokenTtlMinutes;
        this.frontendBaseUrl = frontendBaseUrl;
    }

    /** Authenticated change: current password verified, new policy enforced, all sessions revoked. */
    @Transactional
    public void changeOwnPassword(String username, String currentPassword,
                                  String newPassword, String confirmPassword) {
        validateNewPassword(newPassword, confirmPassword);
        var user = userRepository.findByUsernameAndIsActiveTrue(username)
                // Context lost mid-request (deactivation, etc.) — 401, never 404.
                .orElseThrow(() -> new AuthenticationException("Authenticated user not found"));
        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw new IllegalArgumentException("Current password is incorrect");
        }
        if (passwordEncoder.matches(newPassword, user.getPassword())) {
            throw new IllegalArgumentException("New password must differ from the current one");
        }
        user.changePassword(passwordEncoder.encode(newPassword));
        refreshTokenRepository.invalidateAllForUser(username);
        notifyChanged(user);
        log.info("Password changed for user [{}]; all sessions revoked", username);
    }

    /**
     * Forgot-password entry point. Always returns normally (controller → 202). Sends a reset
     * link only when an <em>active</em> account owns the (normalized) email; otherwise returns
     * silently. Rate-limited per-IP and per-email (address-blind 429).
     */
    public void requestReset(String email, String clientIp) {
        rateLimiter.checkForgotAllowed(clientIp, email);
        String normalized = normalizeEmail(email);
        if (normalized == null) {
            return;
        }
        var userOpt = userRepository.findByEmailAndIsActiveTrue(normalized);
        if (userOpt.isEmpty()) {
            // No enumeration — indistinguishable from the matched path to the caller.
            log.debug("Forgot-password for an address with no active account — nothing sent");
            return;
        }
        var user = userOpt.get();
        String raw = resetTokenRepository.issue(user.getUsername(), Duration.ofMinutes(resetTokenTtlMinutes));
        String resetUrl = frontendBaseUrl + "/reset-password?token=" + urlEncode(raw);
        String toEmail = user.getEmail();
        String username = user.getUsername();
        long ttl = resetTokenTtlMinutes;
        // Off the request thread → constant-time response regardless of match (no timing oracle).
        dispatchMail(() -> mailSender.sendPasswordResetLink(toEmail, username, resetUrl, ttl));
        log.info("Password-reset link issued for user [{}]", username);
    }

    /** Consume a reset token (single-use), set the new password, revoke all sessions. */
    @Transactional
    public void resetPassword(String token, String newPassword, String confirmPassword) {
        validateNewPassword(newPassword, confirmPassword);
        String username = resetTokenRepository.consume(token)
                .orElseThrow(() -> new IllegalArgumentException(INVALID_TOKEN_MSG));
        var user = userRepository.findByUsernameAndIsActiveTrue(username)
                // Don't leak that the user vanished / was deactivated — same generic 400.
                .orElseThrow(() -> new IllegalArgumentException(INVALID_TOKEN_MSG));
        user.changePassword(passwordEncoder.encode(newPassword));
        refreshTokenRepository.invalidateAllForUser(username);
        notifyChanged(user);
        log.info("Password reset via token for user [{}]; all sessions revoked", username);
    }

    /** Non-consuming validity check for the FE reset page. */
    public boolean isResetTokenValid(String token) {
        return resetTokenRepository.peekUsername(token).isPresent();
    }

    /** Set or clear (blank/null) the caller's recovery email. */
    @Transactional
    public void setOwnEmail(String username, String email) {
        var user = userRepository.findByUsernameAndIsActiveTrue(username)
                .orElseThrow(() -> new AuthenticationException("Authenticated user not found"));
        String normalized = normalizeEmail(email);
        if (normalized == null) {
            user.setEmail(null);
            log.info("Recovery email cleared for user [{}]", username);
            return;
        }
        if (!normalized.equals(user.getEmail()) && userRepository.existsByEmail(normalized)) {
            throw new IllegalStateException("That email is already in use.");
        }
        user.setEmail(normalized);
        log.info("Recovery email updated for user [{}]", username);
        // Note: previous-address "recovery email changed" notice is intentionally omitted —
        // the mail port has no such message and reusing "password changed" would be a false
        // security alert. Email is set-and-trust here (see PROMPT §10).
    }

    private void notifyChanged(AppUser user) {
        if (user.getEmail() != null) {
            String toEmail = user.getEmail();
            String username = user.getUsername();
            dispatchMail(() -> mailSender.sendPasswordChangedNotice(toEmail, username));
        }
    }

    /** Belt-and-suspenders server-side policy (DTO {@code @Size}/{@code @NotBlank} is the first gate). */
    private void validateNewPassword(String newPassword, String confirmPassword) {
        if (newPassword == null || newPassword.length() < MIN_LEN || newPassword.length() > MAX_LEN) {
            throw new IllegalArgumentException("New password must be between 8 and 200 characters.");
        }
        if (!newPassword.equals(confirmPassword)) {
            throw new IllegalArgumentException("Passwords do not match.");
        }
    }

    /**
     * Run a mail send off the request thread (audit pool). Falls back to inline on executor
     * rejection. The {@link PasswordMailSender} swallows its own delivery failures, so this
     * only guards against the dispatch itself failing.
     */
    private void dispatchMail(Runnable send) {
        try {
            mailExecutor.execute(() -> {
                try {
                    send.run();
                } catch (Exception e) {
                    log.debug("Async password mail failed: {}", e.getMessage());
                }
            });
        } catch (Exception e) {
            log.debug("Mail executor unavailable, sending inline: {}", e.getMessage());
            try {
                send.run();
            } catch (Exception ignored) {
                // sender already swallows; nothing further to do
            }
        }
    }

    private static String normalizeEmail(String email) {
        if (email == null) {
            return null;
        }
        String trimmed = email.trim().toLowerCase();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String urlEncode(String value) {
        // The token is Base64URL (already URL-safe); encode defensively anyway.
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
