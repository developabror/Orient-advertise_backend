package uz.orientadvertise.services.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.orientadvertise.services.common.exception.IllegalConfigurationException;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.content.DevicePushChannel;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.EntityAuditLog;
import uz.orientadvertise.services.domain.model.RemoteSession;
import uz.orientadvertise.services.domain.model.RemoteSession.Status;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.RemoteSessionRepository;
import uz.orientadvertise.services.service.RemoteSessionService.AckStatus;
import uz.orientadvertise.services.service.RemoteSessionService.DeliveryChannel;
import uz.orientadvertise.services.service.exception.RemoteCapabilityUnsupportedException;
import uz.orientadvertise.services.service.exception.RemoteControlDisabledException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RemoteSessionServiceTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final Long DEVICE_ID = 12L;

    private RemoteSessionRepository sessionRepository;
    private DeviceRepository deviceRepository;
    private DevicePushChannel pushChannel;
    private EntityAuditService auditService;
    private RemoteProperties properties;
    private RemoteSessionService service;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(RemoteSessionRepository.class);
        deviceRepository = mock(DeviceRepository.class);
        pushChannel = mock(DevicePushChannel.class);
        auditService = mock(EntityAuditService.class);

        properties = new RemoteProperties();
        properties.setEnabled(true);
        properties.getRelay().setSigningSecret(SECRET);
        properties.getRelay().setAgentUrl("wss://relay.test/agent");
        properties.getRelay().setViewerUrl("wss://relay.test/viewer");

        service = newService();
    }

    private RemoteSessionService newService() {
        return new RemoteSessionService(sessionRepository, deviceRepository,
                new RemoteSessionTicketService(properties), pushChannel, auditService, properties);
    }

    /** A device mock whose capability getters default to "never reported". */
    private Device device(Boolean remoteSupported) {
        var device = mock(Device.class);
        when(device.getId()).thenReturn(DEVICE_ID);
        when(device.getName()).thenReturn("TV-1");
        when(device.getRemoteSupported()).thenReturn(remoteSupported);
        // NOTE: an unstubbed Integer-returning mock getter returns 0, not null — stub the width
        // explicitly so the effective-max-width clamp exercises the real "not reported" branch.
        when(device.getRemoteMaxWidth()).thenReturn(null);
        return device;
    }

    private RemoteSession persistedSession(Device device, Status status) {
        var session = new RemoteSession("rs_abc123", device, false, "operator1",
                Instant.now().plus(30, ChronoUnit.MINUTES));
        switch (status) {
            case PENDING -> { /* already */ }
            case ACTIVE -> session.markActive(1280, 720);
            case ENDED -> session.markEnded(RemoteSession.END_REASON_OPERATOR_STOP);
            case FAILED -> session.markFailed("boom");
            case EXPIRED -> session.markExpired();
        }
        return session;
    }

    private void stubStartablDevice(Device device) {
        when(deviceRepository.findByIdAndDeletedAtIsNull(DEVICE_ID)).thenReturn(Optional.of(device));
        when(sessionRepository.findFirstByDeviceIdAndStatusIn(eq(DEVICE_ID), any()))
                .thenReturn(Optional.empty());
        when(sessionRepository.save(any(RemoteSession.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    // ----- start: positive -----

    @Test
    void start_createsPendingSessionWithViewerTicketAndRandomKey() {
        var device = device(true);
        stubStartablDevice(device);
        when(pushChannel.isConnected(DEVICE_ID)).thenReturn(true);
        when(pushChannel.push(eq(DEVICE_ID), anyString())).thenReturn(true);

        var view = service.start(DEVICE_ID, false, "operator1");

        assertEquals(Status.PENDING.name(), view.status());
        assertEquals(DEVICE_ID, view.deviceId());
        assertEquals("wss://relay.test/viewer", view.relayUrl());
        assertNotNull(view.viewerTicket());
        assertFalse(view.viewOnly());
        // "rs_" + 16 random bytes as lowercase hex. Structure only — do NOT assert that the key
        // "does not contain" the device id: 32 random hex chars contain any given 2-char string
        // about 11% of the time, which makes such a test flaky, not strict.
        assertTrue(view.sessionId().matches("rs_[0-9a-f]{32}"), view.sessionId());
        assertTrue(view.sessionId().startsWith(RemoteSession.SESSION_KEY_PREFIX));
    }

    @Test
    void start_sessionKeysAreUnpredictableAndNeverDerivedFromTheDeviceId() {
        // Enumerable session ids were the fatal flaw in the original repeater design, so this is
        // the security-load-bearing property: repeated starts for the SAME device must produce
        // unrelated keys. 64 samples with zero collisions rules out any deterministic derivation
        // (sequential counter, hash of deviceId, timestamp truncation) without being flaky —
        // a genuine 128-bit CSPRNG collides here with probability ~1e-35.
        var device = device(true);
        stubStartablDevice(device);

        var keys = new java.util.HashSet<String>();
        for (int i = 0; i < 64; i++) {
            var key = service.start(DEVICE_ID, false, "operator1").sessionId();
            assertTrue(key.matches("rs_[0-9a-f]{32}"), key);
            assertTrue(keys.add(key), "duplicate session key on call " + i + ": " + key);
        }
        assertEquals(64, keys.size());
    }

    @Test
    void start_connectedDevice_deliveredViaWs() {
        var device = device(null);   // never reported ⇒ allowed
        stubStartablDevice(device);
        when(pushChannel.push(eq(DEVICE_ID), anyString())).thenReturn(true);

        assertEquals(DeliveryChannel.WS.name(), service.start(DEVICE_ID, false, "operator1").deliveredVia());
    }

    @Test
    void start_offlineDevice_is201WithDeliveredViaHeartbeat_notAnError() {
        // An offline device is NOT an error — the beat delivers the session within 2 minutes.
        var device = device(true);
        stubStartablDevice(device);
        when(pushChannel.push(eq(DEVICE_ID), anyString())).thenReturn(false);

        var view = service.start(DEVICE_ID, false, "operator1");

        assertEquals(DeliveryChannel.HEARTBEAT.name(), view.deliveredVia());
        assertEquals(Status.PENDING.name(), view.status());
    }

    @Test
    void start_pushThrows_stillSucceedsViaHeartbeat() {
        var device = device(true);
        stubStartablDevice(device);
        when(pushChannel.push(eq(DEVICE_ID), anyString())).thenThrow(new RuntimeException("socket blew up"));

        var view = service.start(DEVICE_ID, false, "operator1");

        assertEquals(DeliveryChannel.HEARTBEAT.name(), view.deliveredVia());
    }

    @Test
    void start_viewOnly_isCarriedIntoTheSessionAndTheView() {
        var device = device(true);
        stubStartablDevice(device);

        assertTrue(service.start(DEVICE_ID, true, "operator1").viewOnly());
    }

    @Test
    void start_writesAnAuditRowWithActorDeviceSessionAndViewOnly() {
        var device = device(true);
        stubStartablDevice(device);
        when(pushChannel.push(eq(DEVICE_ID), anyString())).thenReturn(true);

        var view = service.start(DEVICE_ID, true, "operator1");

        var payload = ArgumentCaptor.forClass(String.class);
        verify(auditService).logChange(eq("RemoteSession"), any(), eq(EntityAuditLog.Action.CREATE),
                eq("operator1"), any(), payload.capture());
        assertTrue(payload.getValue().contains(view.sessionId()));
        assertTrue(payload.getValue().contains("\"deviceId\":12"));
        assertTrue(payload.getValue().contains("\"viewOnly\":true"));
        assertFalse(payload.getValue().contains(view.viewerTicket()),
                "an audit trail must never become a credential store");
    }

    @Test
    void start_capabilityIsProjectedOnlyOnceReported() {
        var never = device(true);
        when(never.getRemoteCapsAt()).thenReturn(null);
        stubStartablDevice(never);
        assertNull(service.start(DEVICE_ID, false, "operator1").capability());

        var reported = device(true);
        var at = Instant.parse("2026-08-27T10:12:00Z");
        when(reported.getRemoteCapsAt()).thenReturn(at);
        when(reported.getRemoteInput()).thenReturn("ROOT");
        when(reported.getRemoteTransport()).thenReturn("SCRCPY_WS");
        when(reported.getRemoteMaxHeight()).thenReturn(720);
        stubStartablDevice(reported);

        var capability = service.start(DEVICE_ID, false, "operator1").capability();
        assertNotNull(capability);
        assertEquals("ROOT", capability.input());
        assertEquals(at, capability.reportedAt());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void start_conflictGuardOnlyConsidersNonTerminalStatuses() {
        // Pins the ONE thing every other test in this class stubs away with any(): which statuses
        // the "already live?" guard actually queries. Widening it to include ENDED/FAILED/EXPIRED
        // would permanently wedge a device — one crashed session and no operator could ever
        // connect to that box again — and no other assertion here would notice.
        var device = device(true);
        stubStartablDevice(device);

        service.start(DEVICE_ID, false, "operator1");

        ArgumentCaptor<Collection> statuses = ArgumentCaptor.forClass(Collection.class);
        verify(sessionRepository).findFirstByDeviceIdAndStatusIn(eq(DEVICE_ID), statuses.capture());
        assertEquals(List.of(Status.PENDING, Status.ACTIVE), List.copyOf(statuses.getValue()));
        assertFalse(statuses.getValue().contains(Status.EXPIRED),
                "an expired session must never block a fresh start");
        assertFalse(statuses.getValue().contains(Status.ENDED));
        assertFalse(statuses.getValue().contains(Status.FAILED));
    }

    // ----- start: negative -----

    @Test
    void start_secondSessionWhilePending_conflictsWithAHumanReadableMessage() {
        var device = device(true);
        when(deviceRepository.findByIdAndDeletedAtIsNull(DEVICE_ID)).thenReturn(Optional.of(device));
        when(sessionRepository.findFirstByDeviceIdAndStatusIn(eq(DEVICE_ID), any()))
                .thenReturn(Optional.of(persistedSession(device, Status.PENDING)));

        var e = assertThrows(IllegalStateException.class,
                () -> service.start(DEVICE_ID, false, "operator1"));

        // A 409 without a message is a silent failure — the frontend renders this verbatim.
        assertNotNull(e.getMessage());
        assertFalse(e.getMessage().isBlank());
        assertTrue(e.getMessage().contains("TV-1"), "the message must name the device");
        assertTrue(e.getMessage().contains(Status.PENDING.name()));
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void start_secondSessionWhileActive_alsoConflicts() {
        var device = device(true);
        when(deviceRepository.findByIdAndDeletedAtIsNull(DEVICE_ID)).thenReturn(Optional.of(device));
        when(sessionRepository.findFirstByDeviceIdAndStatusIn(eq(DEVICE_ID), any()))
                .thenReturn(Optional.of(persistedSession(device, Status.ACTIVE)));

        assertThrows(IllegalStateException.class, () -> service.start(DEVICE_ID, false, "operator1"));
    }

    @Test
    void start_capabilityReportedUnsupported_is422() {
        var device = device(false);
        when(deviceRepository.findByIdAndDeletedAtIsNull(DEVICE_ID)).thenReturn(Optional.of(device));

        var e = assertThrows(RemoteCapabilityUnsupportedException.class,
                () -> service.start(DEVICE_ID, false, "operator1"));
        assertTrue(e.getMessage().contains("TV-1"));
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void start_capabilityNeverReported_isAllowed() {
        // null = never reported, NOT "unsupported". The device simply may not act on it.
        var device = device(null);
        stubStartablDevice(device);

        assertEquals(Status.PENDING.name(), service.start(DEVICE_ID, false, "operator1").status());
    }

    @Test
    void start_unknownOrSoftDeletedDevice_is404() {
        when(deviceRepository.findByIdAndDeletedAtIsNull(DEVICE_ID)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.start(DEVICE_ID, false, "operator1"));
    }

    @Test
    void start_whenFeatureDisabled_is503AndTouchesNothing() {
        properties.setEnabled(false);
        service = newService();

        assertThrows(RemoteControlDisabledException.class, () -> service.start(DEVICE_ID, false, "operator1"));
        verify(deviceRepository, never()).findByIdAndDeletedAtIsNull(anyLong());
        verify(sessionRepository, never()).save(any());
    }

    // ----- ack -----

    @Test
    void ack_ready_movesPendingToActiveAndRecordsDimensions() {
        var device = device(true);
        var session = persistedSession(device, Status.PENDING);
        when(sessionRepository.findBySessionKey("rs_abc123")).thenReturn(Optional.of(session));

        var view = service.ack(DEVICE_ID, "rs_abc123", AckStatus.READY, 1280, 720, null, null);

        assertEquals(Status.ACTIVE.name(), view.status());
        assertEquals(Status.ACTIVE, session.getStatus());
        assertEquals(1280, session.getDeviceWidth());
        assertEquals(720, session.getDeviceHeight());
        assertNotNull(session.getStartedAt());
        assertNull(view.viewerTicket(), "an ack must never hand out a fresh viewer ticket");
    }

    @Test
    void ack_failed_recordsTheError() {
        var device = device(true);
        var session = persistedSession(device, Status.PENDING);
        when(sessionRepository.findBySessionKey("rs_abc123")).thenReturn(Optional.of(session));

        var view = service.ack(DEVICE_ID, "rs_abc123", AckStatus.FAILED, null, null, "su: not found", null);

        assertEquals(Status.FAILED.name(), view.status());
        assertEquals("su: not found", session.getError());
    }

    @Test
    void ack_ended_recordsTheReason_defaultingWhenBlank() {
        var device = device(true);
        var session = persistedSession(device, Status.ACTIVE);
        when(sessionRepository.findBySessionKey("rs_abc123")).thenReturn(Optional.of(session));

        service.ack(DEVICE_ID, "rs_abc123", AckStatus.ENDED, null, null, null, "RELAY_LOST");
        assertEquals("RELAY_LOST", session.getEndReason());

        var second = persistedSession(device, Status.ACTIVE);
        when(sessionRepository.findBySessionKey("rs_def456")).thenReturn(Optional.of(second));
        service.ack(DEVICE_ID, "rs_def456", AckStatus.ENDED, null, null, null, "  ");
        assertEquals(RemoteSession.END_REASON_DEVICE_ENDED, second.getEndReason());
    }

    @Test
    void ack_onTerminalSession_conflicts() {
        var device = device(true);
        var session = persistedSession(device, Status.ENDED);
        when(sessionRepository.findBySessionKey("rs_abc123")).thenReturn(Optional.of(session));

        var e = assertThrows(IllegalStateException.class,
                () -> service.ack(DEVICE_ID, "rs_abc123", AckStatus.READY, 1280, 720, null, null));
        assertFalse(e.getMessage().isBlank());
    }

    @Test
    void ack_forADifferentDevice_is404NotForbidden() {
        // 403 would confirm the session key exists. 404 leaks nothing.
        var owner = device(true);
        var session = persistedSession(owner, Status.PENDING);
        when(sessionRepository.findBySessionKey("rs_abc123")).thenReturn(Optional.of(session));

        assertThrows(ResourceNotFoundException.class,
                () -> service.ack(999L, "rs_abc123", AckStatus.READY, 1280, 720, null, null));
        assertEquals(Status.PENDING, session.getStatus(), "a rejected ack must not mutate state");
    }

    @Test
    void ack_unknownSessionKey_is404() {
        when(sessionRepository.findBySessionKey("rs_nope")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.ack(DEVICE_ID, "rs_nope", AckStatus.READY, 1280, 720, null, null));
    }

    // ----- stop -----

    @Test
    void stop_endsTheSessionPushesStopAndAudits() {
        var device = device(true);
        var session = persistedSession(device, Status.ACTIVE);
        when(sessionRepository.findBySessionKey("rs_abc123")).thenReturn(Optional.of(session));

        service.stop(DEVICE_ID, "rs_abc123", "operator1");

        assertEquals(Status.ENDED, session.getStatus());
        assertEquals(RemoteSession.END_REASON_OPERATOR_STOP, session.getEndReason());
        verify(pushChannel).push(eq(DEVICE_ID), anyString());
        verify(auditService).logChange(eq("RemoteSession"), any(), eq(EntityAuditLog.Action.UPDATE),
                eq("operator1"), any(), anyString());
    }

    @Test
    void stop_alreadyTerminal_isANoOpNotAnError() {
        var device = device(true);
        var session = persistedSession(device, Status.ENDED);
        var endedAt = session.getEndedAt();
        when(sessionRepository.findBySessionKey("rs_abc123")).thenReturn(Optional.of(session));

        service.stop(DEVICE_ID, "rs_abc123", "operator1");   // must not throw

        assertEquals(endedAt, session.getEndedAt(), "an idempotent stop must not re-stamp ended_at");
        verify(pushChannel, never()).push(anyLong(), anyString());
    }

    @Test
    void stop_isIdempotentAcrossRepeatedCalls() {
        var device = device(true);
        var session = persistedSession(device, Status.ACTIVE);
        when(sessionRepository.findBySessionKey("rs_abc123")).thenReturn(Optional.of(session));

        service.stop(DEVICE_ID, "rs_abc123", "operator1");
        service.stop(DEVICE_ID, "rs_abc123", "operator1");

        assertEquals(Status.ENDED, session.getStatus());
    }

    @Test
    void stop_forADifferentDevice_is404() {
        var device = device(true);
        when(sessionRepository.findBySessionKey("rs_abc123"))
                .thenReturn(Optional.of(persistedSession(device, Status.ACTIVE)));

        assertThrows(ResourceNotFoundException.class, () -> service.stop(999L, "rs_abc123", "operator1"));
    }

    @Test
    void stop_worksEvenWhenTheFeatureIsDisabled() {
        // Turning remote control off must not strand a box that is already streaming.
        properties.setEnabled(false);
        service = newService();
        var device = device(true);
        var session = persistedSession(device, Status.ACTIVE);
        when(sessionRepository.findBySessionKey("rs_abc123")).thenReturn(Optional.of(session));

        service.stop(DEVICE_ID, "rs_abc123", "operator1");

        assertEquals(Status.ENDED, session.getStatus());
    }

    // ----- current -----

    @Test
    void current_returnsTheLiveSessionWithoutAViewerTicket() {
        var device = device(true);
        when(sessionRepository.findFirstByDeviceIdAndStatusIn(eq(DEVICE_ID), any()))
                .thenReturn(Optional.of(persistedSession(device, Status.ACTIVE)));

        var view = service.current(DEVICE_ID).orElseThrow();

        assertEquals(Status.ACTIVE.name(), view.status());
        assertNull(view.viewerTicket(), "tickets are single-issue — a reconnecting viewer must POST again");
    }

    @Test
    void current_noLiveSession_isEmpty() {
        when(sessionRepository.findFirstByDeviceIdAndStatusIn(eq(DEVICE_ID), any()))
                .thenReturn(Optional.empty());

        assertTrue(service.current(DEVICE_ID).isEmpty());
    }

    // ----- desiredFor -----

    @Test
    void desiredFor_pendingSession_carriesAnAgentTicketAndTheAgentRelayUrl() {
        var device = device(true);
        when(sessionRepository.findFirstByDeviceIdAndStatusIn(eq(DEVICE_ID), any()))
                .thenReturn(Optional.of(persistedSession(device, Status.PENDING)));

        var desired = service.desiredFor(DEVICE_ID).orElseThrow();

        assertEquals("rs_abc123", desired.sessionId());
        assertEquals("wss://relay.test/agent", desired.relayUrl());
        assertNotNull(desired.agentTicket());
        assertEquals(1280, desired.maxWidth());
        assertEquals(15, desired.maxFps());
        assertEquals(2_000_000, desired.bitRate());
    }

    @Test
    void desiredFor_activeSession_isStillAdvertised() {
        var device = device(true);
        when(sessionRepository.findFirstByDeviceIdAndStatusIn(eq(DEVICE_ID), any()))
                .thenReturn(Optional.of(persistedSession(device, Status.ACTIVE)));

        assertTrue(service.desiredFor(DEVICE_ID).isPresent());
    }

    @Test
    void desiredFor_clampsMaxWidthDownToWhatTheDeviceReported() {
        var device = device(true);
        when(device.getRemoteMaxWidth()).thenReturn(720);
        when(sessionRepository.findFirstByDeviceIdAndStatusIn(eq(DEVICE_ID), any()))
                .thenReturn(Optional.of(persistedSession(device, Status.PENDING)));

        assertEquals(720, service.desiredFor(DEVICE_ID).orElseThrow().maxWidth());
    }

    @Test
    void desiredFor_neverWidensBeyondTheConfiguredCap() {
        var device = device(true);
        when(device.getRemoteMaxWidth()).thenReturn(3840);
        when(sessionRepository.findFirstByDeviceIdAndStatusIn(eq(DEVICE_ID), any()))
                .thenReturn(Optional.of(persistedSession(device, Status.PENDING)));

        assertEquals(1280, service.desiredFor(DEVICE_ID).orElseThrow().maxWidth());
    }

    @Test
    void desiredFor_noSession_isEmpty() {
        when(sessionRepository.findFirstByDeviceIdAndStatusIn(eq(DEVICE_ID), any()))
                .thenReturn(Optional.empty());

        assertTrue(service.desiredFor(DEVICE_ID).isEmpty());
    }

    @Test
    void desiredFor_sessionPastItsExpiry_isEmpty() {
        var device = device(true);
        var stale = new RemoteSession("rs_stale", device, false, "operator1",
                Instant.now().minus(1, ChronoUnit.MINUTES));
        when(sessionRepository.findFirstByDeviceIdAndStatusIn(eq(DEVICE_ID), any()))
                .thenReturn(Optional.of(stale));

        assertTrue(service.desiredFor(DEVICE_ID).isEmpty(),
                "a session past its hard ceiling must never be re-advertised");
    }

    @Test
    void desiredFor_whenFeatureDisabled_isEmptyWithoutTouchingTheDatabase() {
        properties.setEnabled(false);
        service = newService();

        assertTrue(service.desiredFor(DEVICE_ID).isEmpty());
        verify(sessionRepository, never()).findFirstByDeviceIdAndStatusIn(anyLong(), any());
    }

    // ----- startup configuration guards -----

    @Test
    void enabledWithABlankRelayUrl_failsAtConstruction() {
        // A blank URL would otherwise ship the literal string "null" to a TV-Box and burn an
        // afternoon of someone's debugging.
        properties.getRelay().setAgentUrl("  ");
        var agent = assertThrows(IllegalConfigurationException.class, this::newService);
        assertTrue(agent.getMessage().contains("app.remote.relay.agent-url"));

        properties.getRelay().setAgentUrl("wss://relay.test/agent");
        properties.getRelay().setViewerUrl(null);
        var viewer = assertThrows(IllegalConfigurationException.class, this::newService);
        assertTrue(viewer.getMessage().contains("app.remote.relay.viewer-url"));
    }

    @Test
    void enabledWithANonPositiveTtl_failsAtConstruction() {
        // PT0S would violate chk_remote_session_expiry (expires_at > issued_at) and surface as a
        // 500 on the operator's first click instead of a startup failure.
        properties.setSessionTtl(java.time.Duration.ZERO);
        assertThrows(IllegalConfigurationException.class, this::newService);

        properties.setSessionTtl(java.time.Duration.ofMinutes(-5));
        assertThrows(IllegalConfigurationException.class, this::newService);

        properties.setSessionTtl(null);
        assertThrows(IllegalConfigurationException.class, this::newService);
    }

    @Test
    void disabledWithNoRelayConfigAtAll_constructsFine() {
        // Ship-dark posture: a deployment that never turns the feature on needs no relay.
        properties.setEnabled(false);
        properties.getRelay().setAgentUrl(null);
        properties.getRelay().setViewerUrl(null);
        properties.getRelay().setSigningSecret(null);
        properties.setSessionTtl(null);

        assertNotNull(newService());
    }

    // ----- expireStale -----

    @Test
    void expireStale_flipsEveryStaleRowAndReportsTheCount() {
        var device = device(true);
        var pending = persistedSession(device, Status.PENDING);
        var active = persistedSession(device, Status.ACTIVE);
        when(sessionRepository.findByStatusInAndExpiresAtBefore(any(), any()))
                .thenReturn(List.of(pending, active));

        assertEquals(2, service.expireStale());
        assertEquals(Status.EXPIRED, pending.getStatus());
        assertEquals(Status.EXPIRED, active.getStatus());
    }

    @Test
    void expireStale_nothingStale_isZero() {
        when(sessionRepository.findByStatusInAndExpiresAtBefore(any(), any())).thenReturn(List.of());

        assertEquals(0, service.expireStale());
    }
}
