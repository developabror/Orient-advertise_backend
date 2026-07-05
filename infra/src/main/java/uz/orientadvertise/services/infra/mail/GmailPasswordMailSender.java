package uz.orientadvertise.services.infra.mail;

import java.nio.charset.StandardCharsets;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import uz.orientadvertise.services.domain.notification.PasswordMailSender;

/**
 * Real {@link PasswordMailSender} — sends MIME HTML over Gmail SMTP using the auto-configured
 * {@link JavaMailSender} (authenticated with a Gmail <em>App Password</em> from
 * {@code spring.mail.password}). Wired only when {@code app.mail.enabled=true}.
 *
 * <p><b>Never throws to the caller.</b> Every send is wrapped — a Mail/SMTP failure is logged
 * at WARN and swallowed. A forgot-password caller must not be able to observe a delivery
 * failure: that would leak account existence and create a timing oracle.
 */
public class GmailPasswordMailSender implements PasswordMailSender {

    private static final Logger log = LoggerFactory.getLogger(GmailPasswordMailSender.class);

    private final JavaMailSender mailSender;
    private final MailProperties props;

    public GmailPasswordMailSender(JavaMailSender mailSender, MailProperties props) {
        this.mailSender = mailSender;
        this.props = props;
    }

    @Override
    public void sendPasswordResetLink(String toEmail, String username, String resetUrl, long ttlMinutes) {
        String subject = "Reset your " + props.getFromName() + " password";
        String html = """
                <p>Hello %s,</p>
                <p>We received a request to reset your %s password. Click the button below to choose a new one:</p>
                <p><a href="%s" style="display:inline-block;padding:10px 18px;background:#1a73e8;color:#fff;text-decoration:none;border-radius:4px">Reset my password</a></p>
                <p>Or paste this link into your browser:<br><a href="%s">%s</a></p>
                <p>This link expires in %d minutes. If you didn't request this, you can safely ignore this email — your password won't change.</p>
                """.formatted(escape(username), escape(props.getFromName()), resetUrl, resetUrl, escape(resetUrl), ttlMinutes);
        send(toEmail, subject, html);
    }

    @Override
    public void sendPasswordChangedNotice(String toEmail, String username) {
        String subject = "Your " + props.getFromName() + " password was changed";
        String html = """
                <p>Hello %s,</p>
                <p>Your %s password was just changed and all active sessions were signed out.</p>
                <p>If this wasn't you, contact your administrator immediately.</p>
                """.formatted(escape(username), escape(props.getFromName()));
        send(toEmail, subject, html);
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    private void send(String toEmail, String subject, String html) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setText(html, true);
            if (props.getFrom() != null && !props.getFrom().isBlank()) {
                // Gmail rewrites From to the authenticated account anyway; set the display name.
                helper.setFrom(props.getFrom(), props.getFromName());
            }
            mailSender.send(message);
            log.info("Sent password mail [subject={}]", subject);
        } catch (Exception e) {
            // Swallow: callers (esp. forgot-password) must not observe delivery outcomes.
            log.warn("Failed to send password mail [subject={}]: {}", subject, e.getMessage());
        }
    }

    /** Minimal HTML escape for values interpolated into the message body. */
    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
