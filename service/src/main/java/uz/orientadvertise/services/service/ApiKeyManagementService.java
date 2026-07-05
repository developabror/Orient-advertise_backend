package uz.orientadvertise.services.service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.model.ApiKey;
import uz.orientadvertise.services.domain.repository.ApiKeyRepository;

/**
 * Admin-only API key lifecycle. Keys are minted server-side with a CSPRNG and the
 * plaintext is returned ONCE at creation — the database stores only the SHA-256 hash,
 * so a lost key cannot be recovered (and an attacker who breaches the DB cannot
 * impersonate a customer integration).
 */
@Service
public class ApiKeyManagementService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyManagementService.class);
    private static final int RAW_KEY_BYTES = 32; // 256 bits → 43 url-safe Base64 chars

    private final ApiKeyRepository apiKeyRepository;
    private final SecureRandom random = new SecureRandom();

    public ApiKeyManagementService(ApiKeyRepository apiKeyRepository) {
        this.apiKeyRepository = apiKeyRepository;
    }

    @Transactional
    public CreatedKey create(String clientName) {
        if (clientName == null || clientName.isBlank()) {
            throw new IllegalArgumentException("clientName is required");
        }
        byte[] bytes = new byte[RAW_KEY_BYTES];
        random.nextBytes(bytes);
        String rawKey = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String hash = ApiKeyAuthenticationService.sha256Hex(rawKey);
        String prefix = ApiKeyAuthenticationService.prefixOf(rawKey);

        ApiKey saved = apiKeyRepository.save(new ApiKey(hash, prefix, clientName));
        log.info("Created API key [id={} prefix={} clientName={}]",
                saved.getId(), prefix, clientName);
        return new CreatedKey(saved.getId(), rawKey, prefix, clientName, saved.getCreatedAt().toString());
    }

    @Transactional
    public ApiKey revoke(Long id, String revokedBy) {
        ApiKey key = apiKeyRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ApiKey", id));
        key.revoke(revokedBy);
        log.info("Revoked API key [id={} prefix={} by={}]", id, key.getKeyPrefix(), revokedBy);
        return key;
    }

    @Transactional(readOnly = true)
    public List<ApiKey> listAll() {
        return apiKeyRepository.findAllByOrderByCreatedAtDesc();
    }

    /**
     * Returned once at creation. The {@code rawKey} is the plaintext that the customer
     * must store — it is never shown again and never reachable from any other endpoint.
     */
    public record CreatedKey(Long id, String rawKey, String prefix,
                              String clientName, String createdAt) {}
}
