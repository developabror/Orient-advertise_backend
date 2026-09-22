package uz.orientadvertise.services.service;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.RemoteAction;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.RemoteActionRepository;
import uz.orientadvertise.services.service.RemoteActionService.DeviceConfirmOutcome;
import uz.orientadvertise.services.service.RemoteActionService.DeviceConfirmStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RemoteActionDeviceConfirmTest {

    private RemoteActionRepository repository;
    private RemoteActionService service;

    @BeforeEach
    void setUp() {
        repository = mock(RemoteActionRepository.class);
        service = new RemoteActionService(repository, mock(DeviceRepository.class),
                mock(org.springframework.context.ApplicationEventPublisher.class));
    }

    @Test
    void unknownActionId_returnsUnknown_doesNotThrow() {
        // Edge case requirement: confirm for unknown action ID — log and return 200.
        when(repository.findById(999L)).thenReturn(Optional.empty());

        var result = service.processDeviceConfirmation(1L, 999L,
                DeviceConfirmStatus.SUCCESS, "{}");

        assertEquals(DeviceConfirmOutcome.UNKNOWN, result.outcome());
    }

    @Test
    void wrongDeviceForAction_treatedAsUnknown() {
        var device = mockDevice(2L);
        var action = realAction(99L, device, RemoteAction.Status.PENDING, false);
        when(repository.findById(99L)).thenReturn(Optional.of(action));

        // Caller claims device 1 but action belongs to device 2.
        var result = service.processDeviceConfirmation(1L, 99L,
                DeviceConfirmStatus.SUCCESS, "{}");

        assertEquals(DeviceConfirmOutcome.UNKNOWN, result.outcome());
    }

    @Test
    void onTimeSuccess_marksConfirmed() {
        var device = mockDevice(1L);
        var action = realAction(10L, device, RemoteAction.Status.PENDING, false);
        when(repository.findById(10L)).thenReturn(Optional.of(action));

        var result = service.processDeviceConfirmation(1L, 10L,
                DeviceConfirmStatus.SUCCESS, "{\"ok\":true}");

        assertEquals(DeviceConfirmOutcome.CONFIRMED, result.outcome());
        assertEquals(RemoteAction.Status.CONFIRMED, action.getStatus());
    }

    @Test
    void lateSuccess_pendingButPastDeadline_marksConfirmedLate() {
        // Edge case requirement: late confirmation after timeout = CONFIRMED_LATE.
        var device = mockDevice(1L);
        var action = realAction(11L, device, RemoteAction.Status.PENDING, true);
        when(repository.findById(11L)).thenReturn(Optional.of(action));

        var result = service.processDeviceConfirmation(1L, 11L,
                DeviceConfirmStatus.SUCCESS, "{}");

        assertEquals(DeviceConfirmOutcome.CONFIRMED_LATE, result.outcome());
        assertEquals(RemoteAction.Status.CONFIRMED_LATE, action.getStatus());
    }

    @Test
    void lateSuccess_alreadyExpiredByJob_marksConfirmedLate() {
        // The expiration job has already flipped PENDING → EXPIRED. A late device
        // confirm should still record the eventual delivery.
        var device = mockDevice(1L);
        var action = realAction(12L, device, RemoteAction.Status.PENDING, true);
        action.markExpired();
        when(repository.findById(12L)).thenReturn(Optional.of(action));

        var result = service.processDeviceConfirmation(1L, 12L,
                DeviceConfirmStatus.SUCCESS, "{}");

        assertEquals(DeviceConfirmOutcome.CONFIRMED_LATE, result.outcome());
        assertEquals(RemoteAction.Status.CONFIRMED_LATE, action.getStatus());
    }

    @Test
    void failedReport_marksFailed_evenIfLate() {
        // FAILED takes precedence — late FAILED is still FAILED, not CONFIRMED_LATE.
        var device = mockDevice(1L);
        var action = realAction(13L, device, RemoteAction.Status.PENDING, true);
        when(repository.findById(13L)).thenReturn(Optional.of(action));

        var result = service.processDeviceConfirmation(1L, 13L,
                DeviceConfirmStatus.FAILED, "exit code 1");

        assertEquals(DeviceConfirmOutcome.FAILED, result.outcome());
        assertEquals(RemoteAction.Status.FAILED, action.getStatus());
    }

    @Test
    void duplicateConfirm_returnsAlreadyFinalized_withoutMutating() {
        var device = mockDevice(1L);
        var action = realAction(14L, device, RemoteAction.Status.PENDING, false);
        action.confirm("first");
        when(repository.findById(14L)).thenReturn(Optional.of(action));

        var result = service.processDeviceConfirmation(1L, 14L,
                DeviceConfirmStatus.SUCCESS, "second");

        assertEquals(DeviceConfirmOutcome.ALREADY_FINALIZED, result.outcome());
        assertEquals(RemoteAction.Status.CONFIRMED, action.getStatus());
        assertEquals("first", action.getResult(), "duplicate confirm must not overwrite");
    }

    @Test
    void duplicateConfirmAfterFailure_returnsAlreadyFinalized() {
        var device = mockDevice(1L);
        var action = realAction(15L, device, RemoteAction.Status.PENDING, false);
        action.markFailed("disk error");
        when(repository.findById(15L)).thenReturn(Optional.of(action));

        var result = service.processDeviceConfirmation(1L, 15L,
                DeviceConfirmStatus.SUCCESS, "retry-success");

        assertEquals(DeviceConfirmOutcome.ALREADY_FINALIZED, result.outcome());
        assertEquals(RemoteAction.Status.FAILED, action.getStatus());
    }

    private static Device mockDevice(Long id) {
        Device d = mock(Device.class);
        when(d.getId()).thenReturn(id);
        return d;
    }

    /**
     * Build a real RemoteAction (not a mock) so {@code isExpired()} and the lifecycle
     * mutators behave authentically. Reflection patches the deadline for the late case.
     */
    private static RemoteAction realAction(Long id, Device device, RemoteAction.Status status,
                                            boolean pastDeadline) {
        var action = new RemoteAction(device, "REBOOT", "{}", "alice");
        try {
            Field idField = RemoteAction.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(action, id);
            if (pastDeadline) {
                Field exp = RemoteAction.class.getDeclaredField("expiresAt");
                exp.setAccessible(true);
                exp.set(action, Instant.now().minusSeconds(60));
            }
            if (status != RemoteAction.Status.PENDING) {
                Field st = RemoteAction.class.getDeclaredField("status");
                st.setAccessible(true);
                st.set(action, status);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return action;
    }
}
