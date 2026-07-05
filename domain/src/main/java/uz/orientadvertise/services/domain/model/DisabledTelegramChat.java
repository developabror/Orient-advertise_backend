package uz.orientadvertise.services.domain.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A Telegram chat id that the bot must skip when broadcasting. Populated automatically
 * when Telegram replies {@code 403 Forbidden} (the bot was kicked, blocked, or removed
 * from the chat). Persisted so the disable survives JVM restart — without persistence,
 * a bot bounce would re-attempt every previously-kicked chat and immediately be
 * 403-disabled all over again.
 *
 * <p><b>Manual re-enable:</b> {@code DELETE FROM telegram_disabled_chat WHERE chat_id =
 * ?}. The in-memory cache on {@code DisabledChatRegistry} reloads on a 5-minute
 * schedule, so the deletion takes effect within that window without an app restart.
 */
@Entity
@Table(name = "telegram_disabled_chat")
public class DisabledTelegramChat {

    @Id
    @Column(name = "chat_id")
    private Long chatId;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Column(name = "disabled_at", nullable = false)
    private Instant disabledAt;

    /** JPA. */
    protected DisabledTelegramChat() {}

    public DisabledTelegramChat(Long chatId, String reason, Instant disabledAt) {
        this.chatId = chatId;
        this.reason = reason;
        this.disabledAt = disabledAt;
    }

    public Long getChatId() { return chatId; }
    public String getReason() { return reason; }
    public Instant getDisabledAt() { return disabledAt; }
}
