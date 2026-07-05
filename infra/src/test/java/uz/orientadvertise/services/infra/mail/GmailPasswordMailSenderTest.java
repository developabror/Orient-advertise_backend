package uz.orientadvertise.services.infra.mail;

import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GmailPasswordMailSenderTest {

    private MailProperties props() {
        var props = new MailProperties();
        props.setFrom("ops@gmail.com");
        props.setFromName("Orient Advertise");
        return props;
    }

    @Test
    void sendPasswordResetLink_buildsAndSendsMimeMessageWithUrlInBody() throws Exception {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        // createMimeMessage builds a real (empty) MimeMessage — no SMTP connection needed.
        when(mailSender.createMimeMessage()).thenReturn(new JavaMailSenderImpl().createMimeMessage());
        var sender = new GmailPasswordMailSender(mailSender, props());

        String url = "http://localhost:5173/reset-password?token=ABC123xyz";
        sender.sendPasswordResetLink("user@example.com", "alice", url, 30);

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage sent = captor.getValue();

        assertEquals("Reset your Orient Advertise password", sent.getSubject());
        assertEquals("user@example.com", sent.getAllRecipients()[0].toString());
        assertTrue(((String) sent.getContent()).contains(url), "reset URL must appear in the HTML body");
    }

    @Test
    void sendPasswordChangedNotice_sends() throws Exception {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage()).thenReturn(new JavaMailSenderImpl().createMimeMessage());
        var sender = new GmailPasswordMailSender(mailSender, props());

        sender.sendPasswordChangedNotice("user@example.com", "alice");

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        assertEquals("Your Orient Advertise password was changed", captor.getValue().getSubject());
    }

    @Test
    void send_swallowsMailException_neverThrowsToCaller() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage()).thenReturn(new JavaMailSenderImpl().createMimeMessage());
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(MimeMessage.class));
        var sender = new GmailPasswordMailSender(mailSender, props());

        // A forgot-password caller must not observe a delivery failure.
        assertDoesNotThrow(() ->
                sender.sendPasswordResetLink("u@example.com", "alice", "http://x/reset-password?token=z", 30));
    }

    @Test
    void isEnabled_true() {
        assertTrue(new GmailPasswordMailSender(mock(JavaMailSender.class), props()).isEnabled());
    }
}
