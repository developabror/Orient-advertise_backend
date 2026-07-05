package uz.orientadvertise.services.infra.telegram;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

class NoOpTelegramNotifierTest {

    private final NoOpTelegramNotifier notifier = new NoOpTelegramNotifier();

    @Test
    void isEnabled_returnsFalse() {
        assertFalse(notifier.isEnabled());
    }

    @Test
    void sendMessage_silentlyDrops_anyInput() {
        // Must accept null/blank/long chatId and message without throwing — callers
        // expect the no-op to be a true black hole.
        assertDoesNotThrow(() -> notifier.sendMessage("123", "hello"));
        assertDoesNotThrow(() -> notifier.sendMessage(null, null));
        assertDoesNotThrow(() -> notifier.sendMessage("", ""));
        assertDoesNotThrow(() -> notifier.sendMessage("123", "x".repeat(10_000)));
    }

    @Test
    void broadcastMarkdown_silentlyDrops_anyInput() {
        assertDoesNotThrow(() -> notifier.broadcastMarkdown("alert"));
        assertDoesNotThrow(() -> notifier.broadcastMarkdown(null));
        assertDoesNotThrow(() -> notifier.broadcastMarkdown("x".repeat(10_000)));
    }
}
