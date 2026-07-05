package uz.orientadvertise.services.infra.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Password-mail configuration, bound from {@code app.mail.*}. The SMTP host/port/username/
 * password live under {@code spring.mail.*} and are bound by Boot's mail autoconfig — they
 * are deliberately NOT re-bound here.
 *
 * <p>{@link #enabled} defaults to {@code false} so dev/test/CI never open an SMTP socket;
 * opt in per environment (mirrors the Telegram bot's flag discipline). {@link #from} must
 * be supplied (usually identical to {@code spring.mail.username}) when enabled — the bean
 * config fail-fasts on a blank value.
 */
@ConfigurationProperties(prefix = "app.mail")
public class MailProperties {

    private boolean enabled = false;
    private String from = "";
    private String fromName = "Orient Advertise";
    private long resetTokenTtlMinutes = 30;
    /**
     * Dev-only escape hatch: when {@code true}, the No-Op sender logs the full reset link at
     * INFO so the flow can be tested without SMTP. NEVER enable in prod — a forgotten
     * {@code app.mail.enabled=true} would otherwise write live reset links into the logs.
     */
    private boolean logResetLink = false;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getFrom() { return from; }
    public void setFrom(String from) { this.from = from; }

    public String getFromName() { return fromName; }
    public void setFromName(String fromName) { this.fromName = fromName; }

    public long getResetTokenTtlMinutes() { return resetTokenTtlMinutes; }
    public void setResetTokenTtlMinutes(long resetTokenTtlMinutes) {
        this.resetTokenTtlMinutes = resetTokenTtlMinutes;
    }

    public boolean isLogResetLink() { return logResetLink; }
    public void setLogResetLink(boolean logResetLink) { this.logResetLink = logResetLink; }
}
