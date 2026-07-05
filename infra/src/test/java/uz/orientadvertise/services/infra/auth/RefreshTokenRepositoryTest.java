package uz.orientadvertise.services.infra.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RefreshTokenRepositoryTest {

    private RedisTemplate<String, Object> redisTemplate;
    private ValueOperations<String, Object> valueOps;
    private SetOperations<String, Object> setOps;
    private RefreshTokenRepository repository;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        setOps = mock(SetOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        repository = new RefreshTokenRepository(redisTemplate);
    }

    @Test
    void store_returnsTokenId() {
        var tokenId = repository.store("user1", "family-123");
        assertNotNull(tokenId);
        assertFalse(tokenId.isBlank());
    }

    @Test
    void store_savesToRedisWithTtl() {
        repository.store("user1", "family-123");
        verify(valueOps).set(anyString(), any(RefreshTokenRepository.RefreshTokenData.class), eq(Duration.ofDays(7)));
    }

    @Test
    void store_addsToFamilySet() {
        repository.store("user1", "family-123");
        verify(setOps).add(eq("family:family-123"), anyString());
    }

    @Test
    void store_indexesFamilyUnderUser() {
        repository.store("user1", "family-123");
        // The username→families index lets a password change revoke ALL sessions.
        verify(setOps).add(eq("userfam:user1"), eq("family-123"));
    }

    @Test
    void invalidateAllForUser_killsEveryFamilyAndClearsIndex() {
        // user1 has two login families, each with one live token.
        when(setOps.members("userfam:user1")).thenReturn(Set.of("fam-1", "fam-2"));
        when(setOps.members("family:fam-1")).thenReturn(Set.of("tok-1"));
        when(setOps.members("family:fam-2")).thenReturn(Set.of("tok-2"));

        repository.invalidateAllForUser("user1");

        // Every token in every family is gone...
        verify(redisTemplate).delete("refresh:tok-1");
        verify(redisTemplate).delete("refresh:tok-2");
        // ...both family sets are gone...
        verify(redisTemplate).delete("family:fam-1");
        verify(redisTemplate).delete("family:fam-2");
        // ...and the user index itself is cleared.
        verify(redisTemplate).delete("userfam:user1");
    }

    @Test
    void invalidateAllForUser_noFamilies_isNoOpButClearsIndex() {
        when(setOps.members("userfam:ghost")).thenReturn(null);
        repository.invalidateAllForUser("ghost");
        verify(redisTemplate).delete("userfam:ghost");
    }

    @Test
    void find_returnsDataWhenPresent() {
        var data = new RefreshTokenRepository.RefreshTokenData("tok-1", "fam-1", "user1", 123L);
        when(valueOps.get("refresh:tok-1")).thenReturn(data);

        var result = repository.find("tok-1");
        assertTrue(result.isPresent());
        assertTrue(result.get().userId().equals("user1"));
    }

    @Test
    void find_returnsEmptyWhenMissing() {
        when(valueOps.get("refresh:missing")).thenReturn(null);
        var result = repository.find("missing");
        assertTrue(result.isEmpty());
    }

    @Test
    void delete_removesFromRedis() {
        repository.delete("tok-1");
        verify(redisTemplate).delete("refresh:tok-1");
    }

    @Test
    void invalidateFamily_deletesAllTokensInFamily() {
        when(setOps.members("family:fam-1")).thenReturn(Set.of("tok-1", "tok-2"));

        repository.invalidateFamily("fam-1");

        verify(redisTemplate).delete("refresh:tok-1");
        verify(redisTemplate).delete("refresh:tok-2");
        verify(redisTemplate).delete("family:fam-1");
    }
}
