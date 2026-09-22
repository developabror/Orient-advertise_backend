package uz.orientadvertise.services.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.RemoteAction;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.RemoteActionRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RemoteActionServiceTest {

    private RemoteActionRepository repository;
    private org.springframework.context.ApplicationEventPublisher eventPublisher;
    private RemoteActionService service;

    @BeforeEach
    void setUp() {
        repository = mock(RemoteActionRepository.class);
        eventPublisher = mock(org.springframework.context.ApplicationEventPublisher.class);
        service = new RemoteActionService(repository, mock(DeviceRepository.class), eventPublisher);
    }

    // VG-04: the device is told the moment the action is committed, not at its next heartbeat.
    @Test
    void issue_publishesTheActionForAnImmediatePush() {
        var device = mockDevice(1L);
        when(repository.findPendingByDeviceAndType(1L, "REBOOT")).thenReturn(List.of());
        when(repository.save(any(RemoteAction.class))).thenAnswer(inv -> inv.getArgument(0));

        var action = service.issue(device, "REBOOT", "{}", "admin");

        verify(eventPublisher).publishEvent(new RemoteActionIssuedEvent(
                action.getId(), 1L, "REBOOT", action.getIssuedAt()));
    }

    @Test
    void issue_rejectedDuplicate_publishesNothing() {
        var device = mockDevice(1L);
        var existing = mock(RemoteAction.class);
        when(existing.getId()).thenReturn(99L);
        when(repository.findPendingByDeviceAndType(1L, "REBOOT")).thenReturn(List.of(existing));

        assertThrows(IllegalStateException.class, () -> service.issue(device, "REBOOT", "{}", "admin"));

        org.mockito.Mockito.verifyNoInteractions(eventPublisher);
    }

    @Test
    void issue_noPendingDuplicate_createsAction() {
        var device = mockDevice(1L);
        when(repository.findPendingByDeviceAndType(1L, "REBOOT")).thenReturn(List.of());
        when(repository.save(any(RemoteAction.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service.issue(device, "REBOOT", "{}", "admin");

        assertNotNull(result);
        assertEquals(RemoteAction.Status.PENDING, result.getStatus());
        assertEquals("REBOOT", result.getActionType());
        assertNotNull(result.getExpiresAt());
        // Verify 5-minute timeout
        var diff = Duration.between(result.getIssuedAt(), result.getExpiresAt());
        assertEquals(5, diff.toMinutes());
    }

    @Test
    void issue_pendingDuplicateExists_throwsIllegalState() {
        var device = mockDevice(1L);
        var existing = mock(RemoteAction.class);
        when(existing.getId()).thenReturn(99L);
        when(repository.findPendingByDeviceAndType(1L, "REBOOT")).thenReturn(List.of(existing));

        assertThrows(IllegalStateException.class, () ->
                service.issue(device, "REBOOT", "{}", "admin"),
                "Duplicate PENDING action of same type per device must be blocked");
    }

    @Test
    void issue_differentActionType_allowed() {
        var device = mockDevice(1L);
        when(repository.findPendingByDeviceAndType(1L, "UPDATE_CONTENT")).thenReturn(List.of());
        when(repository.save(any(RemoteAction.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service.issue(device, "UPDATE_CONTENT", "{}", "admin");

        assertEquals("UPDATE_CONTENT", result.getActionType());
    }

    @Test
    void confirm_pendingAction_movesToConfirmed() {
        var device = mockDevice(1L);
        var action = new RemoteAction(device, "REBOOT", "{}", "admin");
        when(repository.findById(1L)).thenReturn(Optional.of(action));

        var result = service.confirm(1L, "{\"ok\":true}");

        assertEquals(RemoteAction.Status.CONFIRMED, result.getStatus());
        assertNotNull(result.getConfirmedAt());
        assertEquals("{\"ok\":true}", result.getResult());
    }

    @Test
    void confirm_expiredAction_throwsAndMarksExpired() {
        var device = mockDevice(1L);
        var action = new RemoteAction(device, "REBOOT", "{}", "admin");
        // Manually set expires_at to the past via reflection-like approach
        // Since we can't set fields directly, we create a mock
        var expiredAction = mock(RemoteAction.class);
        when(expiredAction.isExpired()).thenReturn(true);
        when(expiredAction.getStatus()).thenReturn(RemoteAction.Status.PENDING);
        when(repository.findById(2L)).thenReturn(Optional.of(expiredAction));

        assertThrows(IllegalStateException.class, () -> service.confirm(2L, "{}"));
        verify(expiredAction).markExpired();
    }

    @Test
    void expireStale_marksExpiredActions() {
        var device1 = mockDevice(1L);
        var device2 = mockDevice(2L);
        var action1 = mock(RemoteAction.class);
        var action2 = mock(RemoteAction.class);
        when(action1.getId()).thenReturn(1L);
        when(action1.getDevice()).thenReturn(device1);
        when(action1.getActionType()).thenReturn("REBOOT");
        when(action2.getId()).thenReturn(2L);
        when(action2.getDevice()).thenReturn(device2);
        when(action2.getActionType()).thenReturn("UPDATE");
        when(repository.findExpired(any(Instant.class))).thenReturn(List.of(action1, action2));

        int count = service.expireStale();

        assertEquals(2, count);
        verify(action1).markExpired();
        verify(action2).markExpired();
    }

    @Test
    void confirm_nonPendingAction_throws() {
        var action = mock(RemoteAction.class);
        when(action.isExpired()).thenReturn(false);
        when(action.getStatus()).thenReturn(RemoteAction.Status.CONFIRMED);
        when(repository.findById(3L)).thenReturn(Optional.of(action));

        assertThrows(IllegalStateException.class, () -> service.confirm(3L, "{}"));
    }

    private Device mockDevice(Long id) {
        var device = mock(Device.class);
        when(device.getId()).thenReturn(id);
        return device;
    }
}
