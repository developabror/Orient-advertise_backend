package uz.orientadvertise.services.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Body for the volume-set endpoints — per-device override ({@code PUT /api/devices/{id}/volume}),
 * apply-to-all ({@code PUT /api/devices/volume}), and group volume
 * ({@code PUT /api/device-groups/{id}/volume}). Shared so the OpenAPI contract exposes a single
 * {@code SetVolumeRequest} schema across all three surfaces. Out-of-range values are rejected
 * with 400 at the request boundary.
 */
public record SetVolumeRequest(
        @NotNull(message = "volume is required")
        @Min(value = 0, message = "volume must be in [0, 100]")
        @Max(value = 100, message = "volume must be in [0, 100]")
        Integer volume) {
}
