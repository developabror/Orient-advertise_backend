package uz.orientadvertise.services.domain.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import uz.orientadvertise.services.domain.model.ApiKey;

public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {

    /**
     * Look up by hash AND status. The status filter is what makes revocation immediate:
     * a REVOKED key with the same hash returns empty, so the filter rejects with 401 on
     * the very next request. Index {@code idx_api_key_hash_status} covers this lookup.
     */
    Optional<ApiKey> findByKeyHashAndStatus(String keyHash, ApiKey.Status status);

    List<ApiKey> findAllByOrderByCreatedAtDesc();
}
