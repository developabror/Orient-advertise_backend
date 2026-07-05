package uz.orientadvertise.services.infra.auth;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PasswordResetTokenRepositoryTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private PasswordResetTokenRepository repository;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        repository = new PasswordResetTokenRepository(redis);
    }

    @Test
    void issue_storesHashNotRawToken_withTtl() {
        when(valueOps.get("pwdreset:user:alice")).thenReturn(null);

        String raw = repository.issue("alice", Duration.ofMinutes(30));

        assertNotNull(raw);
        assertFalse(raw.isBlank());
        String hash = PasswordResetTokenRepository.sha256Hex(raw);
        // The stored key is the HASH, never the raw token — a Redis leak can't be replayed.
        assertNotEquals(raw, hash);
        verify(valueOps).set("pwdreset:tok:" + hash, "alice", Duration.ofMinutes(30));
        verify(valueOps).set("pwdreset:user:alice", hash, Duration.ofMinutes(30));
    }

    @Test
    void issue_invalidatesPriorToken_oneLiveLinkPerUser() {
        when(valueOps.get("pwdreset:user:alice")).thenReturn("old-hash");

        repository.issue("alice", Duration.ofMinutes(30));

        verify(redis).delete("pwdreset:tok:old-hash");
    }

    @Test
    void consume_returnsUsernameOnce_thenEmpty() {
        when(valueOps.get("pwdreset:user:alice")).thenReturn(null);
        String raw = repository.issue("alice", Duration.ofMinutes(30));
        String hash = PasswordResetTokenRepository.sha256Hex(raw);
        // GETDEL yields the username the first time, null afterwards (single-use).
        when(valueOps.getAndDelete("pwdreset:tok:" + hash)).thenReturn("alice", (String) null);

        Optional<String> first = repository.consume(raw);
        assertTrue(first.isPresent());
        assertEquals("alice", first.get());
        verify(redis).delete("pwdreset:user:alice");

        Optional<String> second = repository.consume(raw);
        assertTrue(second.isEmpty(), "A consumed token must not be usable again");
    }

    @Test
    void consume_unknownToken_returnsEmpty() {
        when(valueOps.getAndDelete(anyString())).thenReturn(null);
        assertTrue(repository.consume("not-a-real-token").isEmpty());
    }

    @Test
    void consume_nullOrBlank_returnsEmpty() {
        assertTrue(repository.consume(null).isEmpty());
        assertTrue(repository.consume("   ").isEmpty());
    }

    @Test
    void peekUsername_doesNotConsume() {
        when(valueOps.get("pwdreset:user:alice")).thenReturn(null);
        String raw = repository.issue("alice", Duration.ofMinutes(30));
        String hash = PasswordResetTokenRepository.sha256Hex(raw);
        when(valueOps.get("pwdreset:tok:" + hash)).thenReturn("alice");

        Optional<String> peek = repository.peekUsername(raw);

        assertTrue(peek.isPresent());
        assertEquals("alice", peek.get());
        // peek must never delete the token.
        verify(valueOps, never()).getAndDelete(anyString());
    }
}
