package uz.orientadvertise.services.infra.telegram;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import uz.orientadvertise.services.domain.model.DisabledTelegramChat;
import uz.orientadvertise.services.domain.repository.DisabledTelegramChatRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DisabledChatRegistryTest {

    private DisabledTelegramChatRepository repo;
    private DisabledChatRegistry registry;

    @BeforeEach
    void setUp() {
        repo = mock(DisabledTelegramChatRepository.class);
        when(repo.findAll()).thenReturn(List.of());
        registry = new DisabledChatRegistry(repo);
    }

    @Test
    void initialLoad_populatesCacheFromDatabase() {
        // Two rows in the DB at boot — both must end up in the cache.
        when(repo.findAll()).thenReturn(List.of(
                new DisabledTelegramChat(100L, "kicked", Instant.now()),
                new DisabledTelegramChat(200L, "blocked", Instant.now())
        ));
        var freshRegistry = new DisabledChatRegistry(repo);

        // Trigger initialLoad (annotated @PostConstruct in production; we call the
        // public reload() to exercise the same path).
        freshRegistry.reload();

        assertTrue(freshRegistry.isDisabled(100L));
        assertTrue(freshRegistry.isDisabled(200L));
        assertFalse(freshRegistry.isDisabled(300L));
        assertEquals(2, freshRegistry.size());
    }

    @Test
    void disable_persistsToDatabase_andUpdatesCache() {
        registry.disable(100L, "kicked from group");

        ArgumentCaptor<DisabledTelegramChat> captor = ArgumentCaptor.forClass(DisabledTelegramChat.class);
        verify(repo).save(captor.capture());
        assertEquals(100L, captor.getValue().getChatId());
        assertEquals("kicked from group", captor.getValue().getReason());
        // Cache reflects the disable immediately — no need to wait for the next reload.
        assertTrue(registry.isDisabled(100L));
    }

    @Test
    void disable_idempotent_secondCallSkipsDbWrite() {
        // First disable persists; second call for the same chat is a no-op.
        registry.disable(100L, "kicked");
        registry.disable(100L, "kicked again");

        verify(repo, times(1)).save(org.mockito.ArgumentMatchers.any());
        assertTrue(registry.isDisabled(100L));
    }

    @Test
    void disable_withNullChatId_silentlyIgnored() {
        registry.disable(null, "boom");
        verify(repo, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void disable_persistsTrucnatedReason_when500CharLimit() {
        // The DB column is 500 chars. A long Telegram error message must be truncated
        // before persistence, not surface the column-too-long DB error.
        String longReason = "x".repeat(800);
        registry.disable(100L, longReason);

        ArgumentCaptor<DisabledTelegramChat> captor = ArgumentCaptor.forClass(DisabledTelegramChat.class);
        verify(repo).save(captor.capture());
        assertTrue(captor.getValue().getReason().length() <= 500,
                "reason truncated to <= 500 chars: " + captor.getValue().getReason().length());
    }

    @Test
    void disable_blankReason_substitutesUnknown() {
        registry.disable(100L, "  ");
        ArgumentCaptor<DisabledTelegramChat> captor = ArgumentCaptor.forClass(DisabledTelegramChat.class);
        verify(repo).save(captor.capture());
        assertEquals("unknown", captor.getValue().getReason());
    }

    @Test
    void disable_persistenceFails_cacheNotUpdated_nextAttemptRetries() {
        // DB save throws — the cache must NOT be updated, otherwise an in-memory-disabled
        // / not-durable mismatch would survive across reloads. The next 403 retries.
        when(repo.save(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("connection refused"));

        registry.disable(100L, "kicked");

        assertFalse(registry.isDisabled(100L), "cache must not have updated on persistence failure");
    }

    @Test
    void disable_duplicateConstraintViolation_treatedAsSuccess_cacheUpdated() {
        // Concurrent 403s for the same chat (or a row already in the DB from a prior boot
        // that wasn't yet in our cache) → the unique-by-PK INSERT collides. We must
        // treat that as success and refresh the cache, not loop forever.
        when(repo.save(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new DataIntegrityViolationException("unique violation"));

        registry.disable(100L, "kicked");

        assertTrue(registry.isDisabled(100L),
                "duplicate-key constraint should still update the cache");
    }

    @Test
    void reload_replacesCache_withDatabaseSnapshot() {
        // Initial state: chat 100 in the DB.
        when(repo.findAll()).thenReturn(List.of(
                new DisabledTelegramChat(100L, "kicked", Instant.now())));
        registry.reload();
        assertTrue(registry.isDisabled(100L));

        // DB now has chat 200 instead (admin DELETEd 100, INSERTed 200) — reload must
        // pick up the new state.
        when(repo.findAll()).thenReturn(List.of(
                new DisabledTelegramChat(200L, "blocked", Instant.now())));
        registry.reload();

        assertFalse(registry.isDisabled(100L), "reload picks up admin re-enable");
        assertTrue(registry.isDisabled(200L), "reload picks up new disables");
    }

    @Test
    void reload_dbFailure_keepsExistingCache_doesNotBlankIt() {
        // The "DB hiccup blanks the cache → bot starts retrying every disabled chat
        // at once" failure mode is exactly what we're avoiding. Verify the cache
        // survives a transient findAll() failure.
        when(repo.findAll()).thenReturn(List.of(
                new DisabledTelegramChat(100L, "kicked", Instant.now())));
        registry.reload();
        assertEquals(1, registry.size());

        when(repo.findAll()).thenThrow(new RuntimeException("db down"));
        registry.reload();

        assertTrue(registry.isDisabled(100L),
                "DB failure must not blank the in-memory disable list");
        assertEquals(1, registry.size());
    }

    @Test
    void isDisabled_nullChatId_returnsFalse_notNpe() {
        assertFalse(registry.isDisabled(null));
    }

    @Test
    void snapshot_returnsImmutableSnapshot() {
        registry.disable(100L, "kicked");
        var snap = registry.snapshot();
        assertEquals(1, snap.size());
        // Set.copyOf returns immutable — operator can read but not mutate.
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class, () -> snap.add(999L));
    }
}
