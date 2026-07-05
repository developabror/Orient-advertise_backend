package uz.orientadvertise.services.domain.model;

/**
 * Pure resolver for a device's <b>effective</b> audio volume — the value the device should
 * actually play at. Stateless and side-effect free, like {@link DeviceStatusEvaluator}, so it can
 * be unit-tested in isolation and reused from the heartbeat path and the DTO projections.
 *
 * <p>Inheritance: {@code effectiveVolume = device.desiredVolume ?? group.volume ?? DEFAULT_VOLUME}.
 * <ul>
 *   <li>{@code device.getDesiredVolume()} — the per-device override; {@code null} means "inherit".</li>
 *   <li>{@code group.getVolume()} — the group's volume; {@code null} means "no group default".</li>
 *   <li>{@link #DEFAULT_VOLUME} — the fallback when nothing is set.</li>
 * </ul>
 */
public final class DeviceVolumeResolver {

    public static final int DEFAULT_VOLUME = 100;

    private DeviceVolumeResolver() {
    }

    public static int resolveEffectiveVolume(Device d) {
        if (d.getDesiredVolume() != null) {
            return d.getDesiredVolume();
        }
        DeviceGroup g = d.getDeviceGroup();
        if (g != null && g.getVolume() != null) {
            return g.getVolume();
        }
        return DEFAULT_VOLUME;
    }
}
