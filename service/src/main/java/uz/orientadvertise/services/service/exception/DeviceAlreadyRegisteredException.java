package uz.orientadvertise.services.service.exception;

/**
 * AUTH-02: a registration for a serial that is already registered, with no admin re-registration
 * window open. Extends {@link IllegalStateException} for the established 409 mapping.
 *
 * <p>The serial and device id travel in the structured fields only (for the caller's log line),
 * never in {@link #getMessage()}, which is returned to an unauthenticated client.
 */
public class DeviceAlreadyRegisteredException extends IllegalStateException {

    private final String serialNumber;
    private final Long deviceId;

    public DeviceAlreadyRegisteredException(String serialNumber, Long deviceId) {
        super("Device is already registered. An administrator must allow re-registration.");
        this.serialNumber = serialNumber;
        this.deviceId = deviceId;
    }

    public String getSerialNumber() { return serialNumber; }
    public Long getDeviceId() { return deviceId; }
}
