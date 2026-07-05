package uz.orientadvertise.services.api.dto;

import uz.orientadvertise.services.domain.model.Device;

/**
 * Compact device projection used inside {@link DeviceGroupDetail} and {@link FacilityDetail}.
 * Deliberately narrow: the device-detail endpoint exposes the full surface (last-heartbeat,
 * IP, content version, etc.); this projection only carries what the member list needs.
 *
 * <p>{@code computedStatus} is the heartbeat-derived status (from {@code device_status_view}),
 * the same contract the device list exposes — never the raw, non-authoritative
 * {@code Device.status} column.
 */
public record DeviceSummary(
        Long id,
        String serialNumber,
        String name,
        String computedStatus,
        // Volume surface: reportedVolume = device's last self-reported value (null until first
        // heartbeat with volume); volumeOverride = per-device override (null = inherit);
        // effectiveVolume = the resolved target (override ?? group volume ?? 100). effectiveVolume
        // is resolved by the caller inside the tx (lazy group access) and passed in.
        Integer reportedVolume,
        Integer volumeOverride,
        int effectiveVolume
) {
    public static DeviceSummary from(Device device, Device.Status computedStatus, int effectiveVolume) {
        return new DeviceSummary(
                device.getId(),
                device.getSerialNumber(),
                device.getName(),
                computedStatus != null ? computedStatus.name() : null,
                device.getReportedVolume(),
                device.getDesiredVolume(),
                effectiveVolume);
    }
}
