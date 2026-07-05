package uz.orientadvertise.services.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.model.ApiKey;
import uz.orientadvertise.services.domain.repository.ApiKeyRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiKeyManagementServiceTest {

    private ApiKeyRepository repo;
    private ApiKeyManagementService service;

    @BeforeEach
    void setUp() {
        repo = mock(ApiKeyRepository.class);
        service = new ApiKeyManagementService(repo);
        when(repo.save(any(ApiKey.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void create_returnsRawKeyOnce_andStoresHash() {
        var created = service.create("Acme Inc.");

        assertNotNull(created.rawKey());
        // Url-safe Base64 of 32 random bytes → 43 chars (no padding).
        assertEquals(43, created.rawKey().length());
        // Prefix is the first 8 chars of the raw key.
        assertEquals(created.rawKey().substring(0, 8), created.prefix());
        assertEquals("Acme Inc.", created.clientName());
        verify(repo).save(any(ApiKey.class));
    }

    @Test
    void create_distinctCalls_yieldDistinctKeys() {
        // The CSPRNG must produce unique keys; collisions break the unique index
        // in the migration, so this is a non-negotiable correctness property.
        var k1 = service.create("Acme");
        var k2 = service.create("Beta");
        assertNotEquals(k1.rawKey(), k2.rawKey());
    }

    @Test
    void create_blankClientName_throws400() {
        assertThrows(IllegalArgumentException.class, () -> service.create(""));
        assertThrows(IllegalArgumentException.class, () -> service.create(null));
    }

    @Test
    void revoke_marksRevokedAndRecordsActor() {
        var key = mock(ApiKey.class);
        when(key.getKeyPrefix()).thenReturn("pfx12345");
        when(repo.findById(7L)).thenReturn(Optional.of(key));

        service.revoke(7L, "admin");

        verify(key).revoke("admin");
    }

    @Test
    void revoke_unknown_throws404() {
        when(repo.findById(99L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.revoke(99L, "admin"));
    }

    @Test
    void listAll_delegatesToRepo() {
        var k = mock(ApiKey.class);
        when(k.getCreatedAt()).thenReturn(Instant.now());
        when(repo.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(k));
        assertEquals(1, service.listAll().size());
    }
}
