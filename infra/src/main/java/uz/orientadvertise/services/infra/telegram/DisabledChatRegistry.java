package uz.orientadvertise.services.infra.telegram;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import uz.orientadvertise.services.domain.model.DisabledTelegramChat;
import uz.orientadvertise.services.domain.repository.DisabledTelegramChatRepository;

/**
 * Cache + DB-backed allow-list of Telegram chat ids the bot must SKIP when broadcasting.
 * Populated automatically when Telegram returns {@code 403 Forbidden} (bot was kicked,
 * blocked, or removed from a chat) — that's the spec's "endless retry storm" guard:
 * once a chat is disabled, the bot stops attempting it until a human re-enables.
 *
 * <p><b>Layered storage.</b> The {@link DisabledTelegramChatRepository}-backed table is
 * the source of truth. A volatile {@code Set<Long>} cache fronts it for hot-path
 * {@link #isDisabled(Long)} checks (called per-chat per-broadcast — too frequent to
 * touch the DB). Writes go to DB first, then the cache is rebuilt from the new state.
 *
 * <p><b>Manual re-enable.</b> Operator runs {@code DELETE FROM telegram_disabled_chat
 * WHERE chat_id = ?} (or via an admin endpoint added later). The {@link #reload()}
 * scheduler runs every 5 minutes and picks up the deletion automatically — no app
 * restart needed. Until reload, the in-memory cache still says "disabled" and the chat
 * stays skipped.
 *
 * <p><b>Persistence justification.</b> The spec calls this out specifically: persist to
 * database, not just memory. Without persistence, a JVM bounce would re-attempt every
 * previously-kicked chat, get 403'd again, and re-disable them — operationally equivalent
 * but burns API quota and shows up as a spike of failures every restart. The DB makes
 * the disable durable.
 *
 * <p><b>Reload behaviour.</b> Transient DB failures during {@link #reload} keep the
 * existing cache rather than blanking it — a "DB hiccup → bot starts retrying every
 * disabled chat" failure mode would be far worse than a stale cache. We log and keep
 * the last good snapshot.
 */
public class DisabledChatRegistry {

    private static final Logger log = LoggerFactory.getLogger(DisabledChatRegistry.class);

    private final DisabledTelegramChatRepository repository;
    private final ReentrantLock writeLock = new ReentrantLock();
    private volatile Set<Long> disabledIds = Set.of();

    public DisabledChatRegistry(DisabledTelegramChatRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    void initialLoad() {
        reload();
    }

    /**
     * Periodic refresh — picks up manual re-enables (DB row deletion) within 5 minutes.
     * Kept short enough that an operator un-disabling a chat doesn't have to wait long
     * to confirm; long enough that we don't hammer the DB on a quiet system.
     */
    @Scheduled(fixedDelayString = "${telegram.bot.disabled-chat-reload-ms:300000}",
               initialDelayString = "${telegram.bot.disabled-chat-reload-ms:300000}")
    public void reload() {
        try {
            var snapshot = new HashSet<Long>();
            for (DisabledTelegramChat row : repository.findAll()) {
                snapshot.add(row.getChatId());
            }
            int previousSize = disabledIds.size();
            disabledIds = Set.copyOf(snapshot);
            if (snapshot.size() != previousSize) {
                log.info("DisabledChatRegistry reloaded — {} chat(s) disabled (was {})",
                        snapshot.size(), previousSize);
            }
        } catch (Exception e) {
            // KEEP the existing cache on transient DB failure. Replacing it with empty
            // would mean every disabled chat starts getting retried during the outage,
            // which is exactly the storm we're trying to prevent.
            log.warn("DisabledChatRegistry reload failed — keeping previous cache: {}",
                    e.getMessage());
        }
    }

    /** Hot path. Per-broadcast, per-chat. Must be O(1) and lock-free. */
    public boolean isDisabled(Long chatId) {
        return chatId != null && disabledIds.contains(chatId);
    }

    /**
     * Mark a chat as disabled. Idempotent — already-disabled chats are a no-op (no
     * duplicate INSERT, no duplicate log line). DB write happens first; the cache is
     * only updated on successful persistence so a DB error doesn't leave us with an
     * "in-memory disabled but not durable" state.
     */
    public void disable(Long chatId, String reason) {
        if (chatId == null) return;
        if (disabledIds.contains(chatId)) return;
        writeLock.lock();
        try {
            // Re-check inside the lock to collapse the race between two concurrent 403s
            // for the same chat.
            if (disabledIds.contains(chatId)) return;
            String safeReason = (reason == null || reason.isBlank()) ? "unknown" : reason;
            if (safeReason.length() > 500) safeReason = safeReason.substring(0, 500);
            try {
                repository.save(new DisabledTelegramChat(chatId, safeReason, Instant.now()));
            } catch (org.springframework.dao.DataIntegrityViolationException dup) {
                // Already in the DB from a prior boot — treat as success and just
                // refresh the cache. The unique-by-PK constraint catches inserts that
                // crossed a reload window.
                log.debug("Disabled chat {} already in DB — refreshing cache", chatId);
            }
            var newCache = new HashSet<>(disabledIds);
            newCache.add(chatId);
            disabledIds = Set.copyOf(newCache);
            log.warn("Telegram chat {} disabled — reason: {}. Will not retry until "
                    + "manually re-enabled (DELETE FROM telegram_disabled_chat WHERE "
                    + "chat_id = {}).", chatId, safeReason, chatId);
        } catch (Exception e) {
            // Don't update the cache on persistence failure — the next 403 will retry
            // the disable. Log and move on so the broadcast loop isn't poisoned.
            log.error("Failed to persist disabled chat {}: {}", chatId, e.getMessage());
        } finally {
            writeLock.unlock();
        }
    }

    /** Visible for tests / diagnostics. */
    public int size() {
        return disabledIds.size();
    }

    /** Visible for tests / diagnostics. */
    public Set<Long> snapshot() {
        return disabledIds;
    }
}
