package uz.orientadvertise.services.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@link Device#getSyncGroupId()} after the {@code sg-} tier was added to the
 * top of the derivation chain: {@code sg- > fac- > grp- > reg- > null}. Proves the new sync-group
 * tier wins over every fallback and that the existing fallbacks are preserved unchanged for
 * ungrouped devices.
 */
class DeviceSyncGroupIdTest {

    private static Region region(long id) {
        Region r = mock(Region.class);
        lenient().when(r.getId()).thenReturn(id);
        return r;
    }

    private static Facility facility(long id) {
        Facility f = mock(Facility.class);
        lenient().when(f.getId()).thenReturn(id);
        return f;
    }

    private static DeviceGroup deviceGroup(long id) {
        DeviceGroup g = mock(DeviceGroup.class);
        lenient().when(g.getId()).thenReturn(id);
        return g;
    }

    private static SyncGroup syncGroup(long id) {
        SyncGroup g = mock(SyncGroup.class);
        lenient().when(g.getId()).thenReturn(id);
        return g;
    }

    @Test
    void syncGroup_winsOverEveryFallback() {
        Device d = new Device(region(3L), facility(42L), "SN-1", "TV-1");
        d.setDeviceGroup(deviceGroup(7L));
        d.setSyncGroup(syncGroup(9L));
        assertEquals("sg-9", d.getSyncGroupId(),
                "an explicit sync group must sit at the top of the chain, above fac-/grp-/reg-");
    }

    @Test
    void noSyncGroup_fallsBackToFacility() {
        Device d = new Device(region(3L), facility(42L), "SN-2", "TV-2");
        d.setDeviceGroup(deviceGroup(7L));
        assertEquals("fac-42", d.getSyncGroupId());
    }

    @Test
    void noSyncGroupNoFacility_fallsBackToDeviceGroup() {
        Device d = new Device(region(3L), null, "SN-3", "TV-3");
        d.setDeviceGroup(deviceGroup(7L));
        assertEquals("grp-7", d.getSyncGroupId());
    }

    @Test
    void onlyRegion_fallsBackToRegion() {
        Device d = new Device(region(3L), null, "SN-4", "TV-4");
        assertEquals("reg-3", d.getSyncGroupId());
    }

    @Test
    void noAssociations_returnsNull() {
        Device d = new Device(null, null, "SN-5", "TV-5");
        assertNull(d.getSyncGroupId(), "no region at all ⇒ device free-runs solo (null)");
    }

    @Test
    void removingSyncGroup_revertsToDerivedFallback() {
        Device d = new Device(region(3L), facility(42L), "SN-6", "TV-6");
        d.setSyncGroup(syncGroup(9L));
        assertEquals("sg-9", d.getSyncGroupId());
        // Removal returns the device to the derived (non-null) fallback, not solo.
        d.setSyncGroup(null);
        assertEquals("fac-42", d.getSyncGroupId());
    }
}
