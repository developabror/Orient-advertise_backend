package uz.orientadvertise.services.infra.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import uz.orientadvertise.services.domain.notification.PasswordMailSender;

/**
 * Conditional wiring for the password-mail subsystem — copies {@code TelegramBotConfig}'s
 * flag-driven structure.
 *
 * <p><b>When {@code app.mail.enabled=true}:</b> {@link GmailPasswordMailSender} is published
 * as the active {@link PasswordMailSender}. <b>Fail-fast</b> if {@code app.mail.from} is blank
 * (mirrors the Telegram "enabled but token empty" guard) — better than booting with a
 * silently-broken mailer. The SMTP username/password are validated by Boot's mail autoconfig
 * at first send.
 *
 * <p><b>When disabled (default):</b> {@link #noOpPasswordMailSender} provides a no-op fallback
 * so every {@code PasswordMailSender} injection site still resolves. No SMTP connection opens.
 */
@Configuration
@EnableConfigurationProperties(MailProperties.class)
public class MailConfig {

    private static final Logger log = LoggerFactory.getLogger(MailConfig.class);

    @Bean
    @ConditionalOnProperty(name = "app.mail.enabled", havingValue = "true")
    public GmailPasswordMailSender gmailPasswordMailSender(JavaMailSender sender, MailProperties props) {
        if (props.getFrom() == null || props.getFrom().isBlank()) {
            // Fail-fast: refuse to start an enabled-but-unconfigured mailer. The deploy must
            // supply APP_MAIL_FROM (usually identical to MAIL_USERNAME / spring.mail.username).
            throw new IllegalStateException(
                    "app.mail.enabled=true but app.mail.from is blank. "
                            + "Set APP_MAIL_FROM (usually the Gmail address), or set app.mail.enabled=false.");
        }
        log.info("Password mail ENABLED — wiring Gmail SMTP sender [from={}]", props.getFrom());
        return new GmailPasswordMailSender(sender, props);
    }

    @Bean
    @ConditionalOnMissingBean(PasswordMailSender.class)
    public PasswordMailSender noOpPasswordMailSender(MailProperties props) {
        log.info("Mail disabled — wiring no-op password mail sender (log-reset-link={})",
                props.isLogResetLink());
        return new NoOpPasswordMailSender(props.isLogResetLink());
    }
}
