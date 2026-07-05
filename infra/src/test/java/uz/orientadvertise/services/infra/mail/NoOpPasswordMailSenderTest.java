package uz.orientadvertise.services.infra.mail;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoOpPasswordMailSenderTest {

    private static final String URL = "http://localhost:5173/reset-password?token=secret-zzz";

    private ListAppender<ILoggingEvent> attachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(NoOpPasswordMailSender.class);
        logger.setLevel(Level.DEBUG);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    @Test
    void resetLink_loggedWhenLogResetLinkTrue() {
        var appender = attachAppender();
        new NoOpPasswordMailSender(true).sendPasswordResetLink("u@e.com", "alice", URL, 30);
        assertTrue(appender.list.stream().anyMatch(e -> e.getFormattedMessage().contains(URL)),
                "dev mode must log the full reset URL");
    }

    @Test
    void resetLink_notLoggedWhenLogResetLinkFalse() {
        var appender = attachAppender();
        new NoOpPasswordMailSender(false).sendPasswordResetLink("u@e.com", "alice", URL, 30);
        assertTrue(appender.list.stream().noneMatch(e -> e.getFormattedMessage().contains(URL)),
                "prod default must never write the reset URL to the logs");
    }

    @Test
    void allSends_areNoOps_andIsEnabledFalse() {
        var noop = new NoOpPasswordMailSender(false);
        assertDoesNotThrow(() -> {
            noop.sendPasswordResetLink("u@e.com", "alice", URL, 30);
            noop.sendPasswordChangedNotice("u@e.com", "alice");
        });
        assertFalse(noop.isEnabled());
    }
}
