package uz.orientadvertise.services.infra.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uz.orientadvertise.services.domain.notification.PasswordMailSender;

/**
 * Fallback {@link PasswordMailSender} used when {@code app.mail.enabled=false} (or unset).
 * Drops every send and reports {@link #isEnabled()} {@code false} — no SMTP socket opens.
 *
 * <p><b>Dev convenience.</b> When {@code app.mail.log-reset-link=true} (dev profile only),
 * {@link #sendPasswordResetLink} logs the full reset URL at INFO so a developer with mail
 * disabled can copy it locally and exercise the flow. This MUST stay off in prod — otherwise
 * a deploy that forgot to flip {@code app.mail.enabled=true} would silently write live reset
 * links into the logs (a real secret leak).
 */
public class NoOpPasswordMailSender implements PasswordMailSender {

    private static final Logger log = LoggerFactory.getLogger(NoOpPasswordMailSender.class);

    private final boolean logResetLink;

    public NoOpPasswordMailSender(boolean logResetLink) {
        this.logResetLink = logResetLink;
    }

    @Override
    public void sendPasswordResetLink(String toEmail, String username, String resetUrl, long ttlMinutes) {
        if (logResetLink) {
            log.info("[mail disabled] Password-reset link for user [{}] (expires in {} min): {}",
                    username, ttlMinutes, resetUrl);
        } else {
            log.debug("[mail disabled] dropping password-reset link for user [{}]", username);
        }
    }

    @Override
    public void sendPasswordChangedNotice(String toEmail, String username) {
        log.debug("[mail disabled] dropping password-changed notice for user [{}]", username);
    }

    @Override
    public boolean isEnabled() {
        return false;
    }
}
