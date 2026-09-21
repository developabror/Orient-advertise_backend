package uz.orientadvertise.services.service;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.Event;
import uz.orientadvertise.services.domain.repository.DeviceRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SyncTimeoutMonitorTest {

    private DeviceRepository deviceRepository;
    private IncidentService incidentService;
    private SyncTimeoutMonitor monitor;

    @BeforeEach
    void setUp() throws Exception {
        deviceRepository = mock(DeviceRepository.class);
        incidentService = mock(IncidentService.class);
        monitor = new SyncTimeoutMonitor(deviceRepository, incidentService, null);
        Field f = SyncTimeoutMonitor.class.getDeclaredField("syncTimeoutMinutes");
        f.setAccessible(true);
        f.setLong(monitor, 30L);
        // Self-reference for the @Transactional(REQUIRES_NEW) escalate indirection. The unit
        // test points it at the same instance — that the proxy IS used (so clearSyncPending()
        // is actually written) is what DeviceMonitorTransactionIntegrationTest proves.
        Field selfField = SyncTimeoutMonitor.class.getDeclaredField("self");
        selfField.setAccessible(true);
        selfField.set(monitor, monitor);
    }

    @Test
    void scan_noStuckDevices_doesNothing() {
        when(deviceRepository.findBySyncPendingSinceLessThanAndDeletedAtIsNull(any())).thenReturn(List.of());

        monitor.scanForStuckSyncs();

        verify(incidentService, never()).processEvent(any());
    }

    @Test
    void escalate_emitsHighPrioritySyncTimeoutEvent_andClearsPendingState() {
        Device device = mock(Device.class);
        when(device.getId()).thenReturn(50L);
        when(device.getSyncPendingSince()).thenReturn(Instant.parse("2026-05-06T00:00:00Z"));
        when(device.getSyncPendingVersion()).thenReturn("v-stuck");
        when(deviceRepository.findById(50L)).thenReturn(Optional.of(device));

        monitor.escalate(50L);

        ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);
        verify(incidentService).processEvent(eventCaptor.capture());
        Event captured = eventCaptor.getValue();
        assertEquals("SYNC_TIMEOUT", captured.getEventType());
        assertEquals(Event.Priority.HIGH, captured.getPriority());

        // Pending markers must be cleared so the next minute's scan doesn't re-trigger.
        verify(device).clearSyncPending();
    }

    @Test
    void escalate_deletedDevice_doesNotCreateIncident() {
        // A device soft-deleted mid-sync still carries syncPendingSince (softDelete does not
        // clear it). The scan query skips deleted devices, but one deleted between the scan and
        // escalate() still arrives here — the isDeleted() guard must stop it from minting a
        // SYNC_TIMEOUT incident for a ghost device.
        Device device = mock(Device.class);
        when(device.isDeleted()).thenReturn(true);
        when(device.getSyncPendingSince()).thenReturn(Instant.parse("2026-05-06T00:00:00Z"));
        when(deviceRepository.findById(70L)).thenReturn(Optional.of(device));

        monitor.escalate(70L);

        verify(incidentService, never()).processEvent(any());
        verify(device, never()).clearSyncPending();
    }

    @Test
    void escalate_deviceNoLongerPending_skips() {
        Device device = mock(Device.class);
        when(device.getSyncPendingSince()).thenReturn(null);
        when(deviceRepository.findById(99L)).thenReturn(Optional.of(device));

        monitor.escalate(99L);

        verify(incidentService, never()).processEvent(any());
        verify(device, never()).clearSyncPending();
    }

    @Test
    void scan_oneFailingEscalation_doesNotBlockOthers() {
        Device good = mock(Device.class);
        when(good.getId()).thenReturn(60L);
        when(good.getSyncPendingSince()).thenReturn(Instant.parse("2026-05-06T00:00:00Z"));
        when(good.getSyncPendingVersion()).thenReturn("v-a");

        Device bad = mock(Device.class);
        when(bad.getId()).thenReturn(61L);

        when(deviceRepository.findBySyncPendingSinceLessThanAndDeletedAtIsNull(any())).thenReturn(List.of(bad, good));
        // bad device's escalate() will throw because findById returns empty → null device path
        when(deviceRepository.findById(61L)).thenThrow(new RuntimeException("db unavailable"));
        when(deviceRepository.findById(60L)).thenReturn(Optional.of(good));

        monitor.scanForStuckSyncs();

        // good still got escalated despite bad blowing up
        verify(incidentService, times(1)).processEvent(any());
    }

    @Test
    void scan_queriesWithTheConfiguredTimeout() {
        when(deviceRepository.findBySyncPendingSinceLessThanAndDeletedAtIsNull(any())).thenReturn(List.of());

        monitor.scanForStuckSyncs();

        ArgumentCaptor<Instant> threshold = ArgumentCaptor.forClass(Instant.class);
        verify(deviceRepository).findBySyncPendingSinceLessThanAndDeletedAtIsNull(threshold.capture());
        var expected = Instant.now().minus(java.time.Duration.ofMinutes(30));
        assertTrue(java.time.Duration.between(threshold.getValue(), expected).abs().getSeconds() < 5,
                "threshold must be now - 30 min, was " + threshold.getValue());
    }
}
