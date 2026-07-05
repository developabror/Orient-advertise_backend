package uz.orientadvertise.services.infra.telegram;

import java.util.Collections;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Telegram bot configuration. Bound from {@code telegram.bot.*} keys.
 *
 * <p><b>Token policy.</b> {@link #token} must be supplied via the {@code TELEGRAM_BOT_TOKEN}
 * environment variable. The {@code application.yml} uses {@code ${TELEGRAM_BOT_TOKEN:}}
 * so the default is the empty string — never a real value baked into source. The bean
 * configuration fail-fasts at startup when {@link #enabled} is {@code true} but the
 * token is blank.
 *
 * <p><b>Authorization model.</b> {@link #authorizedChatIds} is the allow-list of Telegram
 * chat ids the bot will both <em>send to</em> and <em>accept commands from</em>. Bound
 * from a comma-separated env var via Spring's {@code StringToCollectionConverter}
 * ({@code TELEGRAM_BOT_AUTHORIZED_CHAT_IDS=123,-456,789}). An empty set is allowed at
 * boot time — the bot still initializes, but every send becomes a no-op (logged once
 * at startup, not per call) and every inbound command is silently dropped. This is the
 * "don't crash on missing config" edge case from the spec.
 *
 * <p>{@link #enabled} defaults to {@code false} so dev / test stay quiet unless
 * explicitly opted in.
 */
@ConfigurationProperties(prefix = "telegram.bot")
public class TelegramBotProperties {

    private boolean enabled = false;
    private String token = "";
    private String username = "";
    private Set<Long> authorizedChatIds = Set.of();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public Set<Long> getAuthorizedChatIds() { return authorizedChatIds; }
    public void setAuthorizedChatIds(Set<Long> authorizedChatIds) {
        // Defensive copy to an unmodifiable set — prevents downstream code from
        // mutating the allow-list at runtime through the property reference.
        this.authorizedChatIds = authorizedChatIds == null
                ? Set.of()
                : Collections.unmodifiableSet(Set.copyOf(authorizedChatIds));
    }

    /** Convenience for hot-path checks — never throws on null input. */
    public boolean isAuthorized(Long chatId) {
        return chatId != null && authorizedChatIds.contains(chatId);
    }
}
