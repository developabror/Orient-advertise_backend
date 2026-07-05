package uz.orientadvertise.services.infra.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uz.orientadvertise.services.domain.notification.TelegramNotifier;

/**
 * Fallback {@link TelegramNotifier} used when {@code telegram.bot.enabled=false} (or the
 * property is missing). Drops every message and reports {@link #isEnabled()} {@code false}.
 *
 * <p>Lets every consuming service wire {@code TelegramNotifier} unconditionally — they
 * get a real bot in production and a silent no-op in dev, without conditional injection
 * sprinkled through the codebase.
 *
 * <p><b>Single send-site invariant.</b> No {@link OrientTelegramBot} bean is constructed
 * when this class is the active impl, so no Telegram HTTP connection opens, no token is
 * read, no calls leave the JVM. Verified by {@code TelegramBotConfigConditionalsTest}.
 */
public class NoOpTelegramNotifier implements TelegramNotifier {

    private static final Logger log = LoggerFactory.getLogger(NoOpTelegramNotifier.class);

    @Override
    public void sendMessage(String chatId, String message) {
        log.debug("Telegram disabled — dropping message [chatId={}, length={}]",
                chatId, message == null ? 0 : message.length());
    }

    @Override
    public void broadcastMarkdown(String message) {
        log.debug("Telegram disabled — dropping broadcast [length={}]",
                message == null ? 0 : message.length());
    }

    @Override
    public void broadcastMarkdown(String message,
                                  uz.orientadvertise.services.common.telegram.TelegramMessageBuilder.Severity severity) {
        log.debug("Telegram disabled — dropping {} broadcast [length={}]",
                severity, message == null ? 0 : message.length());
    }

    @Override
    public boolean isEnabled() {
        return false;
    }
}
