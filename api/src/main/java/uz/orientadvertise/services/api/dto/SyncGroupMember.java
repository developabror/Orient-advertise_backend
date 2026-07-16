package uz.orientadvertise.services.api.dto;

import uz.orientadvertise.services.domain.model.Device;

/**
 * Compact device projection inside {@link SyncGroupDetail}. Deliberately narrow: no volume
 * fields (an FE test asserts their absence — sync groups carry playback coordination only).
 *
 * <p><b>Field-name contract:</b> the status field is named {@code status} (NOT
 * {@code computedStatus} as in {@link DeviceSummary}). The FE sync-group detail response is an
 * unvalidated typed cast that renders {@code d.status}; emitting {@code computedStatus} would
 * show "undefined" with every test still green. The value is the heartbeat-derived computed
 * status (from {@code device_status_view}), never the raw, non-authoritative
 * {@link Device#getStatus()} column.
 */
public record SyncGroupMember(
        Long id,
        String serialNumber,
        String name,
        String status
) {
    public static SyncGroupMember from(Device device, Device.Status computedStatus) {
        return new SyncGroupMember(
                device.getId(),
                device.getSerialNumber(),
                device.getName(),
                computedStatus != null ? computedStatus.name() : null);
    }
}
