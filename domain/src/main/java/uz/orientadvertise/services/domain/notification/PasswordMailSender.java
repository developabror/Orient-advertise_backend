package uz.orientadvertise.services.domain.notification;

/**
 * Domain port for outbound password-management email. Service-layer code injects this and
 * sends without depending on {@code spring-boot-starter-mail} / {@code JavaMailSender} — the
 * impl lives in infra. Mirrors {@link TelegramNotifier}: a thin port, conditionally wired,
 * that never throws.
 *
 * <p>Two implementations are wired conditionally on {@code app.mail.enabled}:
 * <ul>
 *   <li>{@code GmailPasswordMailSender} — real SMTP over a Gmail App Password, only created
 *       when {@code app.mail.enabled=true} AND a non-blank {@code app.mail.from} is set.</li>
 *   <li>{@code NoOpPasswordMailSender} — fallback that drops sends and reports
 *       {@link #isEnabled()} {@code false}, used in dev / test / when the feature is
 *       disabled. It optionally logs the reset link (dev only) so the flow is testable
 *       without an SMTP socket.</li>
 * </ul>
 *
 * <p>The port itself never throws — implementations log and swallow delivery failures. A
 * forgot-password caller in particular must not be able to observe a send failure: that
 * would leak account existence and create a timing oracle.
 */
public interface PasswordMailSender {

    /**
     * Email a one-time password-reset link. No-op when mail is disabled. Must swallow
     * delivery failures (never throw to the caller).
     *
     * @param toEmail    recipient (the user's recovery email)
     * @param username   the account the link resets — used in the message body only
     * @param resetUrl   the full {@code .../reset-password?token=...} link
     * @param ttlMinutes how long the link stays valid, rendered into the body
     */
    void sendPasswordResetLink(String toEmail, String username, String resetUrl, long ttlMinutes);

    /**
     * Security notice sent after a successful password change/reset ("your password was
     * changed"). No-op when disabled. Swallows failures.
     */
    void sendPasswordChangedNotice(String toEmail, String username);

    /** @return {@code true} when a real SMTP sender is wired; {@code false} for the no-op. */
    boolean isEnabled();
}
