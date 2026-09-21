package uz.orientadvertise.services.api;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionTemplate;
import uz.orientadvertise.services.Application;
import uz.orientadvertise.services.domain.event.CriticalIncidentBroadcaster;
import uz.orientadvertise.services.domain.event.CriticalIncidentBroadcaster.IncidentSummary;
import uz.orientadvertise.services.domain.event.DashboardEventBroadcaster;
import uz.orientadvertise.services.domain.event.DashboardEventBroadcaster.DeviceStatusPayload;
import uz.orientadvertise.services.service.DeviceHealthMonitor;
import uz.orientadvertise.services.service.DeviceHeartbeatService;
import uz.orientadvertise.services.service.IncidentService;
import uz.orientadvertise.services.service.SyncTimeoutMonitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * The incident pipeline through the real transaction manager — the layer every Mockito test of
 * these classes stubs away, and exactly where v1.0.140's three bugs lived:
 *
 * <ul>
 *   <li><b>LOGIC-02</b>: {@code SyncTimeoutMonitor.scanForStuckSyncs()} called {@code escalate()} on
 *       {@code this}, so its {@code REQUIRES_NEW} never applied and {@code clearSyncPending()} ran on a
 *       detached entity that was never written. The unit test's {@code verify(device).clearSyncPending()}
 *       passed; production re-escalated the same device every minute forever.</li>
 *   <li><b>LOGIC-03</b>: {@code DeviceHealthMonitor} did the same, so the critical-incident broadcast
 *       navigated a detached device's LAZY region and threw {@code LazyInitializationException} —
 *       swallowed, so the incident was saved but no operator was ever alerted live.</li>
 *   <li><b>LOGIC-01</b>: the heartbeat auto-resolved {@code DEVICE_OFFLINE} only when the STORED status
 *       went OFFLINE→other, and nothing ever stores OFFLINE.</li>
 * </ul>
 * Plus the review finding on the fix itself: the resolve must run AFTER the beat has committed, the
 * way {@code DeviceController.heartbeat} calls it — never nested in or joined to the beat's
 * transaction. The heartbeat cases therefore call both steps, exactly like the controller.
 *
 * <p>Fixture notes. All {@code @SpringBootTest} contexts share one H2 {@code testdb}, so rows are
 * seeded with fixed high ids through {@link JdbcTemplate} and every assertion is scoped to this
 * fixture's devices — other classes' devices may be escalated or swept by the same
 * {@code runHealthCheck()} call, which is fine. {@code scanForStuckSyncs} is also {@code @Scheduled}
 * and keeps firing in every cached context with the default 30-minute timeout; a pending marker only
 * 5 minutes old keeps them all away from this fixture's device. Test (a) lowers the timeout to
 * 1 minute on this context's monitor for its own duration only (restored in {@link #restoreTimeout}),
 * rather than through a property that would cache a context whose live scan uses 1 minute forever.
 * The monitors are called directly, never waited for.
 */
@SpringBootTest(classes = Application.class)
@ActiveProfiles("test")
class DeviceMonitorTransactionIntegrationTest {

    private static final long PROJECT = 9140L;
    private static final long REGION = 9140L;
    private static final long SYNC_DEVICE = 914001L;
    private static final long STALE_DEVICE = 914002L;
    private static final long RECOVERING_DEVICE = 914003L;
    private static final long SWEPT_DEVICE = 914004L;
    private static final long DUPLICATE_DEVICE = 914005L;
    private static final List<Long> DEVICES =
            List.of(SYNC_DEVICE, STALE_DEVICE, RECOVERING_DEVICE, SWEPT_DEVICE, DUPLICATE_DEVICE);

    @MockitoBean private CriticalIncidentBroadcaster criticalBroadcaster;
    @MockitoBean private DashboardEventBroadcaster dashboardBroadcaster;
    /** A spy (real behaviour) wrapped INSIDE the transactional proxy, so stubbing it can observe or fail the resolve's transaction. */
    @MockitoSpyBean private IncidentService incidentService;

    @Autowired private SyncTimeoutMonitor syncTimeoutMonitor;
    @Autowired private DeviceHealthMonitor healthMonitor;
    @Autowired private DeviceHeartbeatService heartbeatService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager txManager;

    private Long originalSyncTimeoutMinutes;

    /**
     * Seeds through {@link JdbcTemplate}, like {@code PlaybackBatchIdempotencyIntegrationTest}: the
     * schema is already migrated at context start. Devices are never deleted (the heartbeat's async
     * event emitter may still be inserting rows for them); their state is reset instead.
     */
    @BeforeEach
    void setUp() {
        seedOnce("project", PROJECT, "INSERT INTO project (id, name, created_at, updated_at) "
                + "VALUES (9140, 'MonitorTxProj', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        seedOnce("region", REGION, "INSERT INTO region (id, project_id, name, code, created_at, updated_at) "
                + "VALUES (9140, 9140, 'MonitorTxRegion', 'MTX', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        for (long id : DEVICES) {
            jdbcTemplate.update("DELETE FROM incident WHERE device_id = ?", id);
            jdbcTemplate.update("DELETE FROM event WHERE device_id = ?", id);
            seedOnce("device", id, "INSERT INTO device (id, region_id, serial_number, name, status, "
                    + "device_token, registered_at, created_at, updated_at) VALUES (" + id + ", 9140, "
                    + "'SN-MTX-" + id + "', 'Monitor TX " + id + "', 'NO_CONTENT', 'tok-mtx-" + id + "', "
                    + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            // Production never stores OFFLINE (the monitor is derive-only): a device with no
            // assignment beats as NO_CONTENT, so that is what an offline one still has stored.
            jdbcTemplate.update("UPDATE device SET status = 'NO_CONTENT', last_heartbeat_at = ?, "
                    + "sync_pending_since = NULL, sync_pending_version = NULL, content_mismatch_since = NULL, "
                    + "deleted_at = NULL WHERE id = ?", ts(Instant.now().minus(Duration.ofMinutes(1))), id);
        }
    }

    @AfterEach
    void restoreTimeout() {
        if (originalSyncTimeoutMinutes != null) {
            ReflectionTestUtils.setField(syncTimeoutTarget(), "syncTimeoutMinutes", originalSyncTimeoutMinutes);
            originalSyncTimeoutMinutes = null;
        }
    }

    // ---- (a) LOGIC-02 --------------------------------------------------------------------------

    @Test
    void syncTimeout_clearsThePendingMarker_soTheDeviceIsEscalatedOnce() {
        originalSyncTimeoutMinutes = (Long) ReflectionTestUtils.getField(syncTimeoutTarget(), "syncTimeoutMinutes");
        ReflectionTestUtils.setField(syncTimeoutTarget(), "syncTimeoutMinutes", 1L);
        jdbcTemplate.update("UPDATE device SET sync_pending_since = ?, sync_pending_version = 'v-stuck' "
                + "WHERE id = ?", ts(Instant.now().minus(Duration.ofMinutes(5))), SYNC_DEVICE);

        syncTimeoutMonitor.scanForStuckSyncs();

        assertNull(jdbcTemplate.queryForObject(
                        "SELECT sync_pending_since FROM device WHERE id = ?", Timestamp.class, SYNC_DEVICE),
                "clearSyncPending() must be committed — pre-fix it ran on a detached entity and was lost");
        int escalations = eventCount(SYNC_DEVICE, "SYNC_TIMEOUT");
        assertTrue(escalations >= 1, "the stuck sync must have been escalated");
        assertEquals(1, openIncidentCount(SYNC_DEVICE, "SYNC_TIMEOUT"));

        syncTimeoutMonitor.scanForStuckSyncs();

        assertEquals(escalations, eventCount(SYNC_DEVICE, "SYNC_TIMEOUT"),
                "a second scan must not re-escalate the same stuck sync");
    }

    // ---- (b) LOGIC-03 --------------------------------------------------------------------------

    @Test
    void healthCheck_opensOfflineIncident_andAlertsLiveWithTheDevicesProject() {
        makeStale(STALE_DEVICE);

        healthMonitor.runHealthCheck();

        // The sweep runs after escalation in the same pass: it must not close a still-stale device.
        assertEquals(1, openIncidentCount(STALE_DEVICE, "DEVICE_OFFLINE"));

        var summaries = ArgumentCaptor.forClass(IncidentSummary.class);
        verify(criticalBroadcaster, atLeastOnce()).broadcast(summaries.capture());
        var mine = summaries.getAllValues().stream().filter(s -> s.deviceId() == STALE_DEVICE).toList();
        assertEquals(1, mine.size(), "the CRITICAL incident must be broadcast live — pre-fix the project "
                + "lookup on a detached device threw LazyInitializationException and the alert was dropped");
        assertEquals(PROJECT, mine.getFirst().projectId());

        var statuses = ArgumentCaptor.forClass(DeviceStatusPayload.class);
        verify(dashboardBroadcaster, atLeastOnce()).deviceStatusChanged(statuses.capture());
        var offline = statuses.getAllValues().stream().filter(p -> p.deviceId() == STALE_DEVICE).toList();
        assertEquals(1, offline.size());
        assertEquals("OFFLINE", offline.getFirst().newStatus());
        assertEquals(PROJECT, offline.getFirst().projectId(), "dashboard routing key must be the project");
    }

    // ---- (c) LOGIC-01, heartbeat side ----------------------------------------------------------

    @Test
    void heartbeatAfterAnOutage_resolvesTheOfflineIncidentTheMonitorOpened() {
        makeStale(RECOVERING_DEVICE);
        healthMonitor.runHealthCheck();
        assertEquals(1, openIncidentCount(RECOVERING_DEVICE, "DEVICE_OFFLINE"));
        // At the instant the resolve starts, what does an independent transaction see of the beat?
        var beatSeenByResolve = new AtomicReference<Instant>();
        doAnswer(inv -> {
            beatSeenByResolve.compareAndSet(null, committedLastHeartbeat(RECOVERING_DEVICE));
            return inv.callRealMethod();
        }).when(incidentService).autoResolveOnRecovery(eq(RECOVERING_DEVICE), eq("DEVICE_OFFLINE"));
        Instant beforeBeat = Instant.now().minusSeconds(5);

        heartbeatLikeTheController(RECOVERING_DEVICE);

        assertTrue(beatSeenByResolve.get() != null && beatSeenByResolve.get().isAfter(beforeBeat),
                "the resolve must run after the beat committed (and released its connection); it saw "
                        + "last_heartbeat_at=" + beatSeenByResolve.get() + " — i.e. it ran inside the beat");
        assertEquals(0, openIncidentCount(RECOVERING_DEVICE, "DEVICE_OFFLINE"),
                "stored status stays NO_CONTENT across the outage — the beat must still see the recovery");
        assertEquals("system", jdbcTemplate.queryForObject("SELECT resolved_by FROM incident "
                + "WHERE device_id = ? AND event_type = 'DEVICE_OFFLINE'", String.class, RECOVERING_DEVICE));
    }

    // ---- (d) LOGIC-01, sweep side --------------------------------------------------------------

    @Test
    void healthCheck_sweepsTheOpenOfflineIncidentOfADeviceThatIsBeatingAgain() {
        // An incident the heartbeat never closed (e.g. opened before v1.0.140); the device's last
        // beat (1 minute ago, from setUp) is fresh.
        seedOpenOfflineIncident(SWEPT_DEVICE, 914_401L, 914_411L);

        healthMonitor.runHealthCheck();

        assertEquals("RESOLVED", incidentStatus(914_411L));
    }

    // ---- (e) duplicates + the beat still commits -----------------------------------------------

    @Test
    void heartbeat_resolvesEveryDuplicateOpenIncident_andTheBeatCommits() {
        makeStale(DUPLICATE_DEVICE);
        seedOpenOfflineIncident(DUPLICATE_DEVICE, 914_501L, 914_511L);
        seedOpenOfflineIncident(DUPLICATE_DEVICE, 914_502L, 914_512L);
        Instant beforeBeat = Instant.now().minusSeconds(5);

        heartbeatLikeTheController(DUPLICATE_DEVICE);

        assertCommittedBeat(DUPLICATE_DEVICE, beforeBeat);
        assertEquals("RESOLVED", incidentStatus(914_511L));
        assertEquals("RESOLVED", incidentStatus(914_512L));
    }

    // ---- (f) a failing resolve can never cost the beat ------------------------------------------

    @Test
    void heartbeat_commitsEvenWhenTheRecoveryResolveThrows() {
        // The spy sits inside IncidentService's transactional proxy, so this throw happens inside a
        // transaction. Post-commit, it rolls back only the resolve. Had the resolve joined the beat's
        // transaction, it would have marked the beat rollback-only and the beat would be lost.
        makeStale(DUPLICATE_DEVICE);
        seedOpenOfflineIncident(DUPLICATE_DEVICE, 914_521L, 914_531L);
        doThrow(new IllegalStateException("resolve failed")).when(incidentService)
                .autoResolveOnRecovery(eq(DUPLICATE_DEVICE), eq("DEVICE_OFFLINE"));
        Instant beforeBeat = Instant.now().minusSeconds(5);

        heartbeatLikeTheController(DUPLICATE_DEVICE);

        assertCommittedBeat(DUPLICATE_DEVICE, beforeBeat);
        assertEquals("OPEN", incidentStatus(914_531L), "left for the health monitor's recovery sweep");
    }

    /** Exactly what DeviceController.heartbeat does: the beat (its own transaction), then the resolve. */
    private void heartbeatLikeTheController(long deviceId) {
        var result = heartbeatService.processHeartbeat(deviceId);
        heartbeatService.resolveRecoveredIncidents(deviceId, result);
    }

    private void assertCommittedBeat(long deviceId, Instant beforeBeat) {
        Instant stored = jdbcTemplate.queryForObject("SELECT last_heartbeat_at FROM device WHERE id = ?",
                Timestamp.class, deviceId).toInstant();
        assertTrue(stored.isAfter(beforeBeat) && stored.isBefore(Instant.now().plusSeconds(5)),
                "the beat must commit, got last_heartbeat_at=" + stored);
    }

    /** last_heartbeat_at as a fresh transaction on its own connection sees it — committed data only. */
    private Instant committedLastHeartbeat(long deviceId) {
        var fresh = new TransactionTemplate(txManager);
        fresh.setPropagationBehavior(Propagation.REQUIRES_NEW.value());
        return fresh.execute(status -> jdbcTemplate.queryForObject(
                "SELECT last_heartbeat_at FROM device WHERE id = ?", Timestamp.class, deviceId).toInstant());
    }

    /** The monitor bean is a CGLIB proxy; its @Value field lives on the target. */
    private Object syncTimeoutTarget() {
        return AopTestUtils.getUltimateTargetObject(syncTimeoutMonitor);
    }

    private void makeStale(long deviceId) {
        jdbcTemplate.update("UPDATE device SET last_heartbeat_at = ? WHERE id = ?",
                ts(Instant.now().minus(Duration.ofMinutes(30))), deviceId);
    }

    private void seedOpenOfflineIncident(long deviceId, long eventId, long incidentId) {
        Timestamp openedAt = ts(Instant.now().minus(Duration.ofMinutes(20)));
        jdbcTemplate.update("INSERT INTO event (id, device_id, event_type, priority, payload, occurred_at, created_at) "
                + "VALUES (?, ?, 'DEVICE_OFFLINE', 'CRITICAL', '{}', ?, ?)", eventId, deviceId, openedAt, openedAt);
        jdbcTemplate.update("INSERT INTO incident (id, device_id, event_type, status, priority, description, "
                + "occurrence_count, first_event_id, last_event_id, opened_at, updated_at) "
                + "VALUES (?, ?, 'DEVICE_OFFLINE', 'OPEN', 'CRITICAL', 'seeded', 1, ?, ?, ?, ?)",
                incidentId, deviceId, eventId, eventId, openedAt, openedAt);
    }

    private void seedOnce(String table, long id, String insert) {
        Integer present = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE id = ?", Integer.class, id);
        if (present == null || present == 0) {
            jdbcTemplate.update(insert);
        }
    }

    private int openIncidentCount(long deviceId, String eventType) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM incident WHERE device_id = ? "
                + "AND event_type = ? AND status <> 'RESOLVED'", Integer.class, deviceId, eventType);
    }

    private int eventCount(long deviceId, String eventType) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM event WHERE device_id = ? AND event_type = ?",
                Integer.class, deviceId, eventType);
    }

    private String incidentStatus(long incidentId) {
        return jdbcTemplate.queryForObject("SELECT status FROM incident WHERE id = ?", String.class, incidentId);
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }
}
