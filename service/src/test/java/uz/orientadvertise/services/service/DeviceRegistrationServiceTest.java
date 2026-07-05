package uz.orientadvertise.services.service;

import java.util.Optional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.orientadvertise.services.common.exception.IllegalConfigurationException;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.Region;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.RegionRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceRegistrationServiceTest {

    private DeviceRepository deviceRepository;
    private RegionRepository regionRepository;
    private DeviceEventService deviceEventService;
    private EntityManager entityManager;
    private DeviceRegistrationService service;

    @BeforeEach
    void setUp() {
        deviceRepository = mock(DeviceRepository.class);
        regionRepository = mock(RegionRepository.class);
        deviceEventService = mock(DeviceEventService.class);
        entityManager = mock(EntityManager.class);
        service = new DeviceRegistrationService(deviceRepository, regionRepository, deviceEventService, entityManager, -1L);
    }

    @Test
    void register_newDevice_createsAndReturnsCredentials() {
        when(deviceRepository.findBySerialNumberAndDeletedAtIsNull("SN-NEW")).thenReturn(Optional.empty());
        var region = mock(Region.class);
        when(regionRepository.findById(-1L)).thenReturn(Optional.of(region));
        when(deviceRepository.save(any(Device.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service.register("SN-NEW", "My TV");

        assertTrue(result.newRegistration());
        assertNotNull(result.deviceToken());
        assertTrue(result.deviceToken().startsWith("dtk_"));
        assertEquals("SN-NEW", result.serialNumber());
        verify(deviceRepository).save(any(Device.class));
        verify(entityManager, never()).createNativeQuery(anyString());
    }

    @Test
    void register_existingUnregisteredDevice_completesRegistration() {
        var region = mock(Region.class);
        var device = new Device(region, null, "SN-EXIST", "D1");
        // Device exists but not registered (no token)
        when(deviceRepository.findBySerialNumberAndDeletedAtIsNull("SN-EXIST")).thenReturn(Optional.of(device));

        var result = service.register("SN-EXIST", "Updated Name");

        assertTrue(result.newRegistration());
        assertNotNull(result.deviceToken());
        assertTrue(device.isRegistered());
        assertEquals("Updated Name", device.getName());
        verify(deviceRepository, never()).save(any()); // Existing entity, managed by JPA
    }

    @Test
    void register_alreadyRegisteredDevice_upsertsWithNewToken() {
        var region = mock(Region.class);
        var device = new Device(region, null, "SN-REREG", "D1");
        device.register("dtk_old_token"); // Already registered
        when(deviceRepository.findBySerialNumberAndDeletedAtIsNull("SN-REREG")).thenReturn(Optional.of(device));

        var result = service.register("SN-REREG", null);

        assertFalse(result.newRegistration(), "Re-registration should not be flagged as new");
        assertNotNull(result.deviceToken());
        assertFalse(result.deviceToken().equals("dtk_old_token"), "Token should be refreshed on re-registration");
    }

    @Test
    void register_reRegistration_clearsStaleContentVersionState() {
        // Reinstall / factory-reset keeps the serialNumber but wipes the local content store.
        // The server must drop its stale confirmed-version record so the WS connect-replay and
        // heartbeat both see the device as needing a sync (regression: device stuck empty).
        var region = mock(Region.class);
        var device = new Device(region, null, "SN-WIPE", "D1");
        device.register("dtk_old");
        device.setCurrentContentVersion("bd0c39");
        device.markSyncPending("bd0c39");
        when(deviceRepository.findBySerialNumberAndDeletedAtIsNull("SN-WIPE")).thenReturn(Optional.of(device));

        service.register("SN-WIPE", null);

        assertNull(device.getCurrentContentVersion(), "Re-registration must clear stale confirmed version");
        assertNull(device.getSyncPendingVersion());
        assertNull(device.getSyncPendingSince());
    }

    @Test
    void register_newDeviceWithNullName_usesDefaultName() {
        when(deviceRepository.findBySerialNumberAndDeletedAtIsNull("SN-NO-NAME")).thenReturn(Optional.empty());
        var region = mock(Region.class);
        when(regionRepository.findById(-1L)).thenReturn(Optional.of(region));
        when(deviceRepository.save(any(Device.class))).thenAnswer(inv -> {
            var d = (Device) inv.getArgument(0);
            assertEquals("Device-SN-NO-NAME", d.getName());
            return d;
        });

        service.register("SN-NO-NAME", null);
    }

    @Test
    void register_newDevice_whenSentinelMissing_lazilyReSeedsAndProceeds() {
        when(deviceRepository.findBySerialNumberAndDeletedAtIsNull("SN-NO-SENTINEL")).thenReturn(Optional.empty());
        var seededRegion = mock(Region.class);
        // First call returns empty (row deleted); second call (after re-seed) returns the row.
        when(regionRepository.findById(-1L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(seededRegion));
        var query = mock(Query.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.executeUpdate()).thenReturn(1);
        when(deviceRepository.save(any(Device.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service.register("SN-NO-SENTINEL", "Lazy");

        assertTrue(result.newRegistration());
        assertNotNull(result.deviceToken());
        verify(entityManager, times(2)).createNativeQuery(anyString());
        verify(entityManager).flush();
        verify(deviceRepository).save(any(Device.class));
    }

    @Test
    void register_newDevice_whenCustomConfiguredRegionMissing_throwsConfigFault() {
        // A misconfigured app.device.default-region-id is a deployment/config fault, not an
        // operator conflict — it must surface as IllegalConfigurationException (→ HTTP 500),
        // NOT an IllegalStateException (→ 409). The sentinel (-1) path self-heals instead.
        var customService = new DeviceRegistrationService(
                deviceRepository, regionRepository, deviceEventService, entityManager, 99L);
        when(deviceRepository.findBySerialNumberAndDeletedAtIsNull("SN-CUSTOM")).thenReturn(Optional.empty());
        when(regionRepository.findById(99L)).thenReturn(Optional.empty());

        // assertThrows requires the EXACT new type. The compiler also guarantees it is not an
        // IllegalStateException (unrelated types) — which is precisely why the conflict handler
        // can't map it to 409.
        var ex = assertThrows(IllegalConfigurationException.class, () -> customService.register("SN-CUSTOM", null));
        assertEquals("Default region not found", ex.getMessage());
        verify(entityManager, never()).createNativeQuery(anyString());
        verify(deviceRepository, never()).save(any());
    }
}
