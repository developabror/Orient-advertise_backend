package uz.orientadvertise.services.api.controller;

import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.orientadvertise.services.api.openapi.SensitiveEndpoint;
import uz.orientadvertise.services.domain.model.ApiKey;
import uz.orientadvertise.services.service.ApiKeyManagementService;
import uz.orientadvertise.services.service.ApiKeyManagementService.CreatedKey;

/**
 * Admin-only API key lifecycle. The plaintext key is returned ONCE at creation; every
 * subsequent endpoint exposes only the prefix and metadata. Revoke is irreversible —
 * a revoked key can never be re-activated; mint a new one instead.
 */
@Tag(name = "Admin", description = "ADMIN-only API key lifecycle")
@RestController
@RequestMapping("/api/admin/api-keys")
public class ApiKeyAdminController {

    private final ApiKeyManagementService managementService;

    public ApiKeyAdminController(ApiKeyManagementService managementService) {
        this.managementService = managementService;
    }

    @Operation(
            summary = "[SENSITIVE] Mint a new API key",
            description = """
                    Returns the plaintext key ONCE in `rawKey`. The database stores only the \
                    SHA-256 hash; a lost key cannot be recovered — issue a new one. The 8-char \
                    `prefix` is preserved in cleartext for log/UI identification.
                    """
    )
    @SensitiveEndpoint
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Key created — plaintext returned once",
                    content = @Content(examples = @ExampleObject(value = """
                            {
                              "id": 7,
                              "rawKey": "kx9r8s2L7uV3qPzWnD-aMbE6tFhYg1cJoxQpZsRvB4",
                              "prefix": "kx9r8s2L",
                              "clientName": "Acme Inc.",
                              "createdAt": "2026-05-06T10:30:00Z"
                            }
                            """))),
            @ApiResponse(responseCode = "400", description = "Blank or oversized client name"),
            @ApiResponse(responseCode = "403", description = "Caller is not ADMIN")
    })
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples =
            @ExampleObject(value = """
                    { "clientName": "Acme Inc." }
                    """)))
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CreatedKey> create(@Valid @RequestBody CreateRequest request) {
        var created = managementService.create(request.clientName());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(
            summary = "[SENSITIVE] Revoke an API key (irreversible)",
            description = """
                    Marks the key REVOKED. Effective immediately — the next request bearing \
                    this key returns 401 because the auth lookup filters on `status=ACTIVE` \
                    on every call (no in-memory cache widens the window). Cannot be undone; \
                    mint a replacement instead.
                    """
    )
    @SensitiveEndpoint
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Revoked"),
            @ApiResponse(responseCode = "404", description = "Unknown id"),
            @ApiResponse(responseCode = "409", description = "Already revoked")
    })
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiKeySummary> revoke(@PathVariable Long id) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        var by = auth != null ? auth.getName() : "unknown";
        var key = managementService.revoke(id, by);
        return ResponseEntity.ok(ApiKeySummary.from(key));
    }

    @Operation(
            summary = "List API keys (metadata only — no hash, no plaintext)",
            description = "Returns prefix + lifecycle metadata. Plaintext keys and SHA-256 "
                    + "hashes are never exposed by this endpoint."
    )
    @ApiResponses(@ApiResponse(responseCode = "200", description = "Key summaries"))
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<ApiKeySummary>> list() {
        return ResponseEntity.ok(managementService.listAll().stream()
                .map(ApiKeySummary::from).toList());
    }

    public record CreateRequest(
            @NotBlank @Size(min = 1, max = 200) String clientName) {}

    public record ApiKeySummary(Long id, String prefix, String clientName, String status,
                                  Instant createdAt, Instant revokedAt, String revokedBy) {
        public static ApiKeySummary from(ApiKey k) {
            return new ApiKeySummary(k.getId(), k.getKeyPrefix(), k.getClientName(),
                    k.getStatus().name(), k.getCreatedAt(), k.getRevokedAt(), k.getRevokedBy());
        }
    }
}
