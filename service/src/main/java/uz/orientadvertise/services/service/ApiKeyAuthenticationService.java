package uz.orientadvertise.services.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.orientadvertise.services.common.exception.IllegalConfigurationException;
import uz.orientadvertise.services.domain.model.ApiKey;
import uz.orientadvertise.services.domain.repository.ApiKeyRepository;

/**
 * Validates an {@code X-API-Key} header against the {@code api_key} table.
 *
 * <p><b>Revocation is immediate.</b> Every request hashes the inbound key and looks up
 * by {@code (key_hash, status=ACTIVE)} — a key revoked one millisecond ago returns
 * empty here, so the auth filter rejects with 401 on the very next request. There is
 * intentionally <em>no in-memory cache</em>: per-key load is bounded to 100 req/hr
 * (see {@link ApiKeyRateLimiter}) so the indexed lookup is cheap, and any cache would
 * widen the revocation window.
 *
 * <p>Hash is SHA-256 — fast enough for per-request lookup. BCrypt would be ~100×
 * slower and is unnecessary because the secret is high-entropy random (not a password).
 */
@Service
public class ApiKeyAuthenticationService {

    public static final int KEY_PREFIX_LENGTH = 8;

    private final ApiKeyRepository apiKeyRepository;

    public ApiKeyAuthenticationService(ApiKeyRepository apiKeyRepository) {
        this.apiKeyRepository = apiKeyRepository;
    }

    @Transactional(readOnly = true)
    public Optional<ApiKey> authenticate(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return Optional.empty();
        }
        String hash = sha256Hex(rawKey);
        return apiKeyRepository.findByKeyHashAndStatus(hash, ApiKey.Status.ACTIVE);
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in the Java standard library; this never fires. If it ever
            // does, it's a broken JVM (a 500), not an operator-correctable conflict (a 409).
            throw new IllegalConfigurationException("SHA-256 unavailable", e);
        }
    }

    public static String prefixOf(String rawKey) {
        if (rawKey == null || rawKey.length() < KEY_PREFIX_LENGTH) {
            return rawKey == null ? "" : rawKey;
        }
        return rawKey.substring(0, KEY_PREFIX_LENGTH);
    }
}
