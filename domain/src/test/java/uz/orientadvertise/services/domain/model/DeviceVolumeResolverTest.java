package uz.orientadvertise.services.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DeviceVolumeResolver} — covers the three resolution branches of
 * {@code effectiveVolume = device.desiredVolume ?? group.volume ?? DEFAULT_VOLUME}.
 * Pure logic, so {@link Device}/{@link DeviceGroup} are mocked rather than persisted.
 */
class DeviceVolumeResolverTest {

    @Test
    void deviceOverrideWins_evenWhenGroupHasVolume() {
        DeviceGroup group = mock(DeviceGroup.class);
        when(group.getVolume()).thenReturn(80);
        Device device = mock(Device.class);
        when(device.getDesiredVolume()).thenReturn(30);
        when(device.getDeviceGroup()).thenReturn(group);

        assertEquals(30, DeviceVolumeResolver.resolveEffectiveVolume(device),
                "Per-device override takes precedence over the group volume");
    }

    @Test
    void deviceOverrideWins_withNoGroup() {
        Device device = mock(Device.class);
        when(device.getDesiredVolume()).thenReturn(30);
        when(device.getDeviceGroup()).thenReturn(null);

        assertEquals(30, DeviceVolumeResolver.resolveEffectiveVolume(device),
                "Override is honoured regardless of group presence");
    }

    @Test
    void inheritsGroupVolume_whenNoOverride() {
        DeviceGroup group = mock(DeviceGroup.class);
        when(group.getVolume()).thenReturn(55);
        Device device = mock(Device.class);
        when(device.getDesiredVolume()).thenReturn(null);
        when(device.getDeviceGroup()).thenReturn(group);

        assertEquals(55, DeviceVolumeResolver.resolveEffectiveVolume(device),
                "No override → inherit the group volume");
    }

    @Test
    void fallsBackToDefault_whenNoOverrideAndNoGroup() {
        Device device = mock(Device.class);
        when(device.getDesiredVolume()).thenReturn(null);
        when(device.getDeviceGroup()).thenReturn(null);

        assertEquals(DeviceVolumeResolver.DEFAULT_VOLUME,
                DeviceVolumeResolver.resolveEffectiveVolume(device),
                "No override and no group → DEFAULT_VOLUME (100)");
    }

    @Test
    void fallsBackToDefault_whenNoOverrideAndGroupVolumeNull() {
        DeviceGroup group = mock(DeviceGroup.class);
        when(group.getVolume()).thenReturn(null);
        Device device = mock(Device.class);
        when(device.getDesiredVolume()).thenReturn(null);
        when(device.getDeviceGroup()).thenReturn(group);

        assertEquals(DeviceVolumeResolver.DEFAULT_VOLUME,
                DeviceVolumeResolver.resolveEffectiveVolume(device),
                "Group present but no group default → DEFAULT_VOLUME (100)");
    }

    @Test
    void defaultVolumeConstant_is100() {
        assertEquals(100, DeviceVolumeResolver.DEFAULT_VOLUME);
    }
}
