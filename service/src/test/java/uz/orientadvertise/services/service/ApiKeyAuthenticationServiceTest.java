package uz.orientadvertise.services.service;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.orientadvertise.services.domain.model.ApiKey;
import uz.orientadvertise.services.domain.repository.ApiKeyRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiKeyAuthenticationServiceTest {

    private ApiKeyRepository repo;
    private ApiKeyAuthenticationService service;

    @BeforeEach
    void setUp() {
        repo = mock(ApiKeyRepository.class);
        service = new ApiKeyAuthenticationService(repo);
    }

    @Test
    void authenticate_validActiveKey_returnsKey() {
        var key = mock(ApiKey.class);
        String hash = ApiKeyAuthenticationService.sha256Hex("test-raw-key");
        when(repo.findByKeyHashAndStatus(eq(hash), eq(ApiKey.Status.ACTIVE)))
                .thenReturn(Optional.of(key));

        var result = service.authenticate("test-raw-key");

        assertTrue(result.isPresent());
    }

    @Test
    void authenticate_revokedKey_returnsEmpty_immediately() {
        // Edge case: revoked key returns empty on the very next request because the
        // status filter excludes it from the lookup. No cache widens the window.
        String hash = ApiKeyAuthenticationService.sha256Hex("test-raw-key");
        when(repo.findByKeyHashAndStatus(eq(hash), eq(ApiKey.Status.ACTIVE)))
                .thenReturn(Optional.empty());

        var result = service.authenticate("test-raw-key");

        assertTrue(result.isEmpty());
    }

    @Test
    void authenticate_unknownKey_returnsEmpty() {
        when(repo.findByKeyHashAndStatus(org.mockito.ArgumentMatchers.anyString(),
                eq(ApiKey.Status.ACTIVE))).thenReturn(Optional.empty());
        assertTrue(service.authenticate("unknown").isEmpty());
    }

    @Test
    void authenticate_nullOrBlank_returnsEmpty() {
        assertTrue(service.authenticate(null).isEmpty());
        assertTrue(service.authenticate("").isEmpty());
        assertTrue(service.authenticate("   ").isEmpty());
    }

    @Test
    void sha256Hex_isDeterministicAndCorrectLength() {
        // SHA-256 is 32 bytes → 64 hex chars. Determinism guarantees lookup stability.
        String a = ApiKeyAuthenticationService.sha256Hex("hello");
        String b = ApiKeyAuthenticationService.sha256Hex("hello");
        assertEquals(a, b);
        assertEquals(64, a.length());
    }

    @Test
    void prefixOf_returnsFirst8Chars() {
        assertEquals("abcd1234",
                ApiKeyAuthenticationService.prefixOf("abcd1234efghijkl"));
    }

    @Test
    void prefixOf_shortKey_returnsKeyAsIs() {
        assertEquals("short", ApiKeyAuthenticationService.prefixOf("short"));
    }
}
