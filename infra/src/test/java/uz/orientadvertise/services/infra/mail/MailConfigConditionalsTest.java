package uz.orientadvertise.services.infra.mail;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.javamail.JavaMailSender;
import uz.orientadvertise.services.domain.notification.PasswordMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Verifies the conditional wiring contract for the mail subsystem — the security-critical
 * part: the {@code app.mail.enabled} flag drives whether a real SMTP sender is built, and an
 * enabled-but-unconfigured mailer fails fast at startup rather than booting half-broken.
 *
 * <p>Uses {@link ApplicationContextRunner} (slim per-case context, no SMTP/DB). A stub
 * {@link JavaMailSender} is supplied so the enabled path can construct the Gmail sender
 * without Boot's mail autoconfig.
 */
class MailConfigConditionalsTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(JavaMailSender.class, () -> mock(JavaMailSender.class))
            .withUserConfiguration(MailConfig.class);

    @Test
    void disabled_byDefault_wiresNoOpAndOmitsGmail() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(PasswordMailSender.class);
            assertThat(ctx.getBean(PasswordMailSender.class)).isInstanceOf(NoOpPasswordMailSender.class);
            assertThat(ctx).doesNotHaveBean(GmailPasswordMailSender.class);
            assertThat(ctx.getBean(PasswordMailSender.class).isEnabled()).isFalse();
        });
    }

    @Test
    void enabledFalse_explicit_stillWiresNoOp() {
        runner.withPropertyValues("app.mail.enabled=false")
                .run(ctx -> assertThat(ctx.getBean(PasswordMailSender.class))
                        .isInstanceOf(NoOpPasswordMailSender.class));
    }

    @Test
    void enabledTrue_validFrom_wiresGmailSender() {
        runner.withPropertyValues("app.mail.enabled=true", "app.mail.from=ops@gmail.com")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(GmailPasswordMailSender.class);
                    assertThat(ctx.getBean(PasswordMailSender.class))
                            .isInstanceOf(GmailPasswordMailSender.class);
                    assertThat(ctx.getBean(PasswordMailSender.class).isEnabled()).isTrue();
                });
    }

    @Test
    void enabledTrue_blankFrom_failsFastAtStartup() {
        runner.withPropertyValues("app.mail.enabled=true", "app.mail.from=")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).hasMessageContaining("app.mail.from");
                });
    }

    @Test
    void logResetLink_boundFromProperty() {
        runner.withPropertyValues("app.mail.log-reset-link=true")
                .run(ctx -> assertThat(ctx.getBean(MailProperties.class).isLogResetLink()).isTrue());
    }
}
