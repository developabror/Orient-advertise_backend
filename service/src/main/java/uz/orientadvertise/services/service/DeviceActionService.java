package uz.orientadvertise.services.service;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.DeviceActionType;
import uz.orientadvertise.services.domain.model.RemoteAction;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.RemoteActionRepository;

/**
 * Operator-issued single-device actions ({@code POST /service/devices/{id}/actions}).
 *
 * <p>Wraps {@link RemoteActionService#issue} with two extra guards:
 * <ul>
 *   <li><b>Per-device queue cap</b> — a device can have at most {@value #MAX_PENDING_PER_DEVICE}
 *       PENDING actions in flight. Beyond that the request is rejected with 409 to prevent
 *       a buggy operator console from spamming a device that's offline / not draining.</li>
 *   <li><b>Per-type uniqueness</b> — at most one PENDING action of any given type per
 *       device, enforced inside {@link RemoteActionService#issue}. A second REBOOT before
 *       the first is confirmed/expired returns 409.</li>
 * </ul>
 *
 * <p>Type-specific validation:
 * <ul>
 *   <li>{@code VOLUME_SET} requires a {@code volume} integer in [0, 100]</li>
 *   <li>{@code SYNC_CONTENT} requires the device to have a resolved playlist — there is
 *       nothing to sync otherwise, so it's rejected with 409 rather than queuing an action
 *       that would linger in the heartbeat's {@code pendingActions} forever</li>
 *   <li>Other types accept no parameters</li>
 * </ul>
 */
@Service
public class DeviceActionService {

    private static final Logger log = LoggerFactory.getLogger(DeviceActionService.class);

    public static final int MAX_PENDING_PER_DEVICE = 10;

    private final DeviceRepository deviceRepository;
    private final RemoteActionRepository remoteActionRepository;
    private final RemoteActionService remoteActionService;
    private final ContentVersionService contentVersionService;

    public DeviceActionService(DeviceRepository deviceRepository,
                                RemoteActionRepository remoteActionRepository,
                                RemoteActionService remoteActionService,
                                ContentVersionService contentVersionService) {
        this.deviceRepository = deviceRepository;
        this.remoteActionRepository = remoteActionRepository;
        this.remoteActionService = remoteActionService;
        this.contentVersionService = contentVersionService;
    }

    @Transactional
    public RemoteAction issueAction(Long deviceId, DeviceActionType type,
                                     Integer volume, String issuedBy) {
        var device = deviceRepository.findByIdAndDeletedAtIsNull(deviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Device", deviceId));

        requireSyncableContent(device, type);

        String payload = buildPayload(type, volume);

        long pending = remoteActionRepository.countPendingByDevice(deviceId);
        if (pending >= MAX_PENDING_PER_DEVICE) {
            throw new IllegalStateException(
                    "Device " + deviceId + " has " + pending + " pending actions; queue cap is "
                            + MAX_PENDING_PER_DEVICE);
        }

        // RemoteActionService.issue enforces "max 1 PENDING per (device, actionType)"
        // and returns 409 (IllegalStateException) on duplicate.
        var action = remoteActionService.issue(device, type.name(), payload, issuedBy);
        log.info("Issued device action [device={}, type={}, actionId={}, by={}]",
                deviceId, type, action.getId(), issuedBy);
        return action;
    }

    /**
     * A SYNC_CONTENT action is only meaningful when the device has content to sync.
     * {@link ContentVersionService#computeExpectedVersion} returns null exactly when the
     * device has no resolved assignment or no playlist — the same "has content" definition
     * the heartbeat uses — so we reuse it as the single source of truth. Rejecting here
     * (409) keeps a no-op SYNC_CONTENT from lingering in the heartbeat's pendingActions
     * until it expires.
     */
    private void requireSyncableContent(Device device, DeviceActionType type) {
        if (type != DeviceActionType.SYNC_CONTENT) {
            return;
        }
        if (contentVersionService.computeExpectedVersion(device, Instant.now()) == null) {
            throw new IllegalStateException("Device " + device.getId()
                    + " is not assigned to any playlist — assign a playlist before requesting a content sync.");
        }
    }

    private static String buildPayload(DeviceActionType type, Integer volume) {
        if (type == DeviceActionType.VOLUME_SET) {
            if (volume == null) {
                throw new IllegalArgumentException("volume is required for VOLUME_SET");
            }
            if (volume < 0 || volume > 100) {
                throw new IllegalArgumentException("volume must be in [0, 100], got " + volume);
            }
            return "{\"volume\":" + volume + "}";
        }
        return "{}";
    }
}
