package uz.orientadvertise.services.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.orientadvertise.services.domain.content.DevicePushChannel;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.RemoteSession;
import uz.orientadvertise.services.domain.model.RemoteSession.Status;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.RemoteSessionRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The job is a <b>janitor, not the enforcement mechanism</b> — the device kills scrcpy at
 * {@code expiresAt} on its own clock. These tests only pin that stale rows are swept and that
 * terminal rows are left exactly as they were.
 */
class RemoteSessionExpirationJobTest {

    private RemoteSessionRepository sessionRepository;
    private RemoteSessionExpirationJob job;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(RemoteSessionRepository.class);
        var properties = new RemoteProperties();
        properties.setEnabled(true);
        properties.getRelay().setSigningSecret("0123456789abcdef0123456789abcdef");
        properties.getRelay().setAgentUrl("wss://relay.test/agent");
        properties.getRelay().setViewerUrl("wss://relay.test/viewer");
        var service = new RemoteSessionService(sessionRepository, mock(DeviceRepository.class),
                new RemoteSessionTicketService(properties), mock(DevicePushChannel.class),
                mock(EntityAuditService.class), properties);
        job = new RemoteSessionExpirationJob(service);
    }

    private static RemoteSession session(String key, Status status) {
        var device = mock(Device.class);
        when(device.getId()).thenReturn(7L);
        var s = new RemoteSession(key, device, false, "operator1",
                Instant.now().plus(1, ChronoUnit.MINUTES));
        if (status == Status.ACTIVE) {
            s.markActive(1280, 720);
        }
        return s;
    }

    // ----- positive -----

    @Test
    void pendingPastExpiry_becomesExpired() {
        var pending = session("rs_pending", Status.PENDING);
        when(sessionRepository.findByStatusInAndExpiresAtBefore(any(), any()))
                .thenReturn(List.of(pending));

        job.expireStaleSessions();

        assertEquals(Status.EXPIRED, pending.getStatus());
        assertEquals(RemoteSession.END_REASON_EXPIRED, pending.getEndReason());
    }

    @Test
    void activePastExpiry_becomesExpired() {
        var active = session("rs_active", Status.ACTIVE);
        when(sessionRepository.findByStatusInAndExpiresAtBefore(any(), any()))
                .thenReturn(List.of(active));

        job.expireStaleSessions();

        assertEquals(Status.EXPIRED, active.getStatus());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void theQueryOnlySelectsNonTerminalStatuses() {
        // The repository is what keeps terminal rows out of the sweep; pin the argument so a
        // future edit cannot widen it to ENDED/FAILED/EXPIRED and start rewriting history.
        when(sessionRepository.findByStatusInAndExpiresAtBefore(any(), any())).thenReturn(List.of());

        job.expireStaleSessions();

        ArgumentCaptor<Collection> statuses = ArgumentCaptor.forClass(Collection.class);
        verify(sessionRepository).findByStatusInAndExpiresAtBefore(statuses.capture(), any());
        assertEquals(List.of(Status.PENDING, Status.ACTIVE), List.copyOf(statuses.getValue()));
    }

    // ----- negative -----

    @Test
    void nothingStale_isASilentNoOp() {
        when(sessionRepository.findByStatusInAndExpiresAtBefore(any(), any())).thenReturn(List.of());

        job.expireStaleSessions();   // must not throw
    }

    @Test
    void terminalRowsThatSlipIntoTheResult_areSkippedNotRewritten() {
        // Defense in depth: the query already excludes terminal rows, but if one ever appeared
        // it must be skipped silently — never rewritten, and never allowed to abort the sweep
        // and leave the rest of the fleet's sessions unswept.
        var ended = session("rs_ended", Status.PENDING);
        ended.markEnded(RemoteSession.END_REASON_OPERATOR_STOP);
        var endedAt = ended.getEndedAt();
        var stale = session("rs_stale", Status.PENDING);
        when(sessionRepository.findByStatusInAndExpiresAtBefore(any(), any()))
                .thenReturn(List.of(ended, stale));

        job.expireStaleSessions();   // must not throw

        assertEquals(Status.ENDED, ended.getStatus());
        assertEquals(endedAt, ended.getEndedAt());
        assertEquals(RemoteSession.END_REASON_OPERATOR_STOP, ended.getEndReason());
        assertEquals(Status.EXPIRED, stale.getStatus(), "the rest of the sweep must still run");
    }

    @Test
    void aDeviceThatNeverAcks_endsUpExpiredNotStuckPendingForever() {
        var pending = session("rs_ghost", Status.PENDING);
        when(sessionRepository.findByStatusInAndExpiresAtBefore(any(), any()))
                .thenReturn(List.of(pending))
                .thenReturn(List.of());

        job.expireStaleSessions();
        job.expireStaleSessions();

        assertEquals(Status.EXPIRED, pending.getStatus());
        // That EXPIRED no longer blocks a fresh start is pinned where the guard actually runs —
        // RemoteSessionServiceTest.start_conflictGuardOnlyConsidersNonTerminalStatuses. Asserting
        // it here would only be asserting Mockito's default answer, not any production behaviour.
    }
}
