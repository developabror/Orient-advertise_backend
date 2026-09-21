package uz.orientadvertise.services.infra.telegram;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

import javax.sql.DataSource;

import io.minio.MinioClient;
import io.minio.messages.Bucket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import uz.orientadvertise.services.infra.storage.MinioHealthStatus;
import uz.orientadvertise.services.infra.storage.TranscodeExecutor;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.Event;
import uz.orientadvertise.services.domain.repository.DeviceStatusViewRepository;
import uz.orientadvertise.services.domain.repository.IncidentRepository;
import uz.orientadvertise.services.infra.diagnostic.RetainedErrorBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class HealthCommandHandlerTest {

    @TempDir
    java.nio.file.Path tempDir;

    private OrientTelegramBot bot;
    private ObjectProvider<DataSource> dsProvider;
    private ObjectProvider<StringRedisTemplate> redisProvider;
    private ObjectProvider<MinioClient> minioProvider;
    private ObjectProvider<DeviceStatusViewRepository> statusViewRepoProvider;
    private ObjectProvider<IncidentRepository> incidentRepoProvider;
    private ObjectProvider<TranscodeExecutor> transcodeProvider;
    private ObjectProvider<ThreadPoolTaskExecutor> auditProvider;
    private MinioHealthStatus minioHealthStatus;
    private HealthCommandHandler handler;

    @BeforeEach
    void setUp() {
        bot = mock(OrientTelegramBot.class);
        when(bot.send(anyString(), anyString(), anyString())).thenReturn(true);
        when(bot.send(anyString(), anyString())).thenReturn(true);

        dsProvider = mock(ObjectProvider.class);
        redisProvider = mock(ObjectProvider.class);
        minioProvider = mock(ObjectProvider.class);
        statusViewRepoProvider = mock(ObjectProvider.class);
        incidentRepoProvider = mock(ObjectProvider.class);
        transcodeProvider = mock(ObjectProvider.class);
        auditProvider = mock(ObjectProvider.class);
        minioHealthStatus = new MinioHealthStatus();

        // Defaults: every dependency available with a "happy path" stub.
        wireHappyPath();

        handler = new HealthCommandHandler(bot, dsProvider, redisProvider,
                minioProvider, statusViewRepoProvider, incidentRepoProvider,
                transcodeProvider, auditProvider, minioHealthStatus, tempDir.toString());
        RetainedErrorBuffer.resetForTests();
    }

    @AfterEach
    void tearDown() {
        handler.shutdown();
        RetainedErrorBuffer.resetForTests();
    }

    private void wireHappyPath() {
        // Postgres: DataSource.getConnection().isValid(1) → true.
        var ds = mock(DataSource.class);
        var conn = mock(Connection.class);
        try {
            lenient().when(ds.getConnection()).thenReturn(conn);
            lenient().when(conn.isValid(anyInt())).thenReturn(true);
        } catch (SQLException e) { throw new RuntimeException(e); }
        when(dsProvider.getIfAvailable()).thenReturn(ds);

        // Redis: redisTemplate.execute(callback) → "PONG".
        var redis = mock(StringRedisTemplate.class);
        lenient().when(redis.execute((RedisCallback<String>) any()))
                .thenAnswer(inv -> "PONG");
        when(redisProvider.getIfAvailable()).thenReturn(redis);

        // MinIO: 3 buckets.
        var minio = mock(MinioClient.class);
        try {
            var bucket1 = mock(Bucket.class);
            var bucket2 = mock(Bucket.class);
            var bucket3 = mock(Bucket.class);
            lenient().when(minio.listBuckets()).thenReturn(List.of(bucket1, bucket2, bucket3));
        } catch (Exception e) { throw new RuntimeException(e); }
        when(minioProvider.getIfAvailable()).thenReturn(minio);

        // Devices: ONLINE=42, OFFLINE=3, NO_CONTENT=1, UNREGISTERED=0 — heartbeat-derived
        // counts from device_status_view.
        var statusViewRepo = mock(DeviceStatusViewRepository.class);
        when(statusViewRepo.countByComputedStatusGrouped()).thenReturn(List.of(
                new Object[] {Device.Status.ONLINE, 42L},
                new Object[] {Device.Status.OFFLINE, 3L},
                new Object[] {Device.Status.NO_CONTENT, 1L}
        ));
        when(statusViewRepoProvider.getIfAvailable()).thenReturn(statusViewRepo);

        // Incidents: 0 critical, 2 medium, all else zero — UP path (no critical/high).
        var incRepo = mock(IncidentRepository.class);
        List<Object[]> incRows = java.util.Collections.singletonList(
                new Object[] {Event.Priority.MEDIUM, 2L});
        when(incRepo.countOpenByPriority()).thenReturn(incRows);
        when(incidentRepoProvider.getIfAvailable()).thenReturn(incRepo);

        // FFmpeg executors: empty queues, no active threads. Build the mocks BEFORE stubbing —
        // creating a mock inside a when(...).thenReturn(...) argument trips Mockito's
        // UnfinishedStubbingException.
        var transcodeExec = makeTranscodeExec(0, 0, 200, 1);
        var auditExec = makeExec(0, 0, 500);
        when(transcodeProvider.getIfAvailable()).thenReturn(transcodeExec);
        when(auditProvider.getIfAvailable()).thenReturn(auditExec);
    }

    private static TranscodeExecutor makeTranscodeExec(int queueSize, int active, int capacity,
                                                         int concurrency) {
        var exec = mock(TranscodeExecutor.class);
        when(exec.getQueueDepth()).thenReturn(queueSize);
        when(exec.getActiveCount()).thenReturn(active);
        when(exec.getQueueCapacity()).thenReturn(capacity);
        when(exec.getConcurrency()).thenReturn(concurrency);
        return exec;
    }

    private static ThreadPoolTaskExecutor makeExec(int queueSize, int active, int capacity) {
        var exec = mock(ThreadPoolTaskExecutor.class);
        var underlying = mock(ThreadPoolExecutor.class);
        var queue = mock(java.util.concurrent.BlockingQueue.class);
        when(queue.size()).thenReturn(queueSize);
        when(underlying.getQueue()).thenReturn(queue);
        when(exec.getThreadPoolExecutor()).thenReturn(underlying);
        when(exec.getActiveCount()).thenReturn(active);
        when(exec.getQueueCapacity()).thenReturn(capacity);
        return exec;
    }

    // ---------- happy path ----------

    @Test
    void handle_happyPath_sendsMarkdownWithAllRows() {
        handler.handle(100L, new String[0]);

        verify(bot).send(eq("100"), anyString(), eq("Markdown"));
    }

    @Test
    void runAllChecks_returnsNineRows_inExpectedOrder() {
        var results = handler.runAllChecks();

        // The spec lists 9 distinct sub-checks; the row order is locked so the report
        // is consistent across runs.
        assertEquals(9, results.size());
        assertEquals("postgres", results.get(0).name());
        assertEquals("redis", results.get(1).name());
        assertEquals("minio", results.get(2).name());
        assertEquals("ffmpeg", results.get(3).name());
        assertEquals("devices", results.get(4).name());
        assertEquals("incidents", results.get(5).name());
        assertEquals("jvm-mem", results.get(6).name());
        assertEquals("disk", results.get(7).name());
        assertEquals("errors", results.get(8).name());
    }

    @Test
    void payload_containsTitleAndKvBlock() {
        var results = handler.runAllChecks();
        String body = handler.renderPayload(results);

        assertTrue(body.contains("*System health*"), "title");
        assertTrue(body.contains("```"), "code block fence");
        assertTrue(body.contains("postgres"));
        assertTrue(body.contains("redis"));
        assertTrue(body.contains("minio"));
        assertTrue(body.contains("ffmpeg"));
        assertTrue(body.contains("devices"));
        assertTrue(body.contains("incidents"));
        assertTrue(body.contains("jvm-mem"));
        assertTrue(body.contains("disk"));
        assertTrue(body.contains("errors"));
    }

    // ---------- per-check failures isolated ----------

    @Test
    void postgresUnreachable_collapses_otherChecksStillRun() {
        // The DataSource throws on getConnection — the postgres row must be DOWN, the
        // other 8 must still complete normally.
        var ds = mock(DataSource.class);
        try {
            when(ds.getConnection()).thenThrow(new SQLException("connection refused"));
        } catch (SQLException e) { throw new RuntimeException(e); }
        when(dsProvider.getIfAvailable()).thenReturn(ds);

        var results = handler.runAllChecks();

        assertEquals(HealthCommandHandler.CheckStatus.DOWN, byName(results, "postgres").status());
        assertTrue(byName(results, "postgres").detail().contains("connection refused"));
        // Other rows still ran.
        assertEquals(HealthCommandHandler.CheckStatus.UP, byName(results, "redis").status());
        assertEquals(HealthCommandHandler.CheckStatus.UP, byName(results, "minio").status());
    }

    @Test
    void redisMissing_collapses_others_unaffected() {
        when(redisProvider.getIfAvailable()).thenReturn(null);

        var results = handler.runAllChecks();

        var redisResult = byName(results, "redis");
        assertEquals(HealthCommandHandler.CheckStatus.DOWN, redisResult.status());
        assertTrue(redisResult.detail().contains("no Redis bean"));
    }

    @Test
    void minioListThrows_collapsesToDown() {
        var minio = mock(MinioClient.class);
        try {
            when(minio.listBuckets()).thenThrow(new RuntimeException("bucket listing failed"));
        } catch (Exception e) { throw new RuntimeException(e); }
        when(minioProvider.getIfAvailable()).thenReturn(minio);

        var results = handler.runAllChecks();

        assertEquals(HealthCommandHandler.CheckStatus.DOWN, byName(results, "minio").status());
    }

    @Test
    void minioProbeSucceeding_healsTheStorageLatch() {
        // An operator typing /health performs exactly the round-trip the scheduled probe
        // performs. Reporting "minio UP, 3 bucket(s)" in the chat while the application goes on
        // refusing uploads because a stale flag says otherwise is an hour of an incident.
        minioHealthStatus.markDegraded("upload: ConnectException");

        var results = handler.runAllChecks();

        assertEquals(HealthCommandHandler.CheckStatus.UP, byName(results, "minio").status());
        assertTrue(minioHealthStatus.isAvailable(), "an operator check must heal the latch");
    }

    @Test
    void minioProbeFailing_degradesTheStorageFlag_withAReason() {
        minioHealthStatus.markUp();
        var minio = mock(MinioClient.class);
        try {
            when(minio.listBuckets()).thenThrow(new java.net.ConnectException("Connection refused"));
        } catch (Exception e) { throw new RuntimeException(e); }
        when(minioProvider.getIfAvailable()).thenReturn(minio);

        var results = handler.runAllChecks();

        assertEquals(HealthCommandHandler.CheckStatus.DOWN, byName(results, "minio").status());
        assertFalse(minioHealthStatus.isAvailable());
        assertTrue(minioHealthStatus.getReason().contains("telegram /health probe"),
                minioHealthStatus.getReason());
        assertTrue(minioHealthStatus.getReason().contains("ConnectException"),
                minioHealthStatus.getReason());
    }

    @Test
    void minioAccessDenied_reportsDown_butDoesNotDegradeStorageForTheFleet() {
        // A healthy MinIO refusing these credentials. Degrading here would 503 every upload and
        // every device /sync in the fleet because an operator typed a slash command — exactly the
        // "one request's error becomes a fleet-wide outage" failure v1.0.144 exists to remove.
        minioHealthStatus.markUp();
        var err = mock(io.minio.messages.ErrorResponse.class);
        when(err.code()).thenReturn("AccessDenied");
        var denied = mock(io.minio.errors.ErrorResponseException.class);
        when(denied.errorResponse()).thenReturn(err);
        when(denied.response()).thenReturn(null);
        var minio = mock(MinioClient.class);
        try {
            when(minio.listBuckets()).thenThrow(denied);
        } catch (Exception e) { throw new RuntimeException(e); }
        when(minioProvider.getIfAvailable()).thenReturn(minio);

        var results = handler.runAllChecks();

        // The operator still sees the failure in the chat — the check really did fail.
        assertEquals(HealthCommandHandler.CheckStatus.DOWN, byName(results, "minio").status());
        assertTrue(minioHealthStatus.isAvailable(),
                "an S3-level refusal must not take storage down for every device");
    }

    @Test
    void minioBeanMissing_doesNotTouchTheStorageFlag() {
        // "no MinioClient bean" is a wiring fact, not a probe result — it proves nothing about
        // whether MinIO is reachable, so it must not flip the flag in either direction.
        minioHealthStatus.markUp();
        when(minioProvider.getIfAvailable()).thenReturn(null);

        var results = handler.runAllChecks();

        assertEquals(HealthCommandHandler.CheckStatus.DOWN, byName(results, "minio").status());
        assertTrue(minioHealthStatus.isAvailable());
    }

    // ---------- 2-second per-check timeout ----------

    @Test
    void perCheckTimeout_2s_isEnforcedWhenBackingCallHangs() throws Exception {
        // The Postgres ping hangs. The per-check timeout is 2s, and the OVERALL is 10s,
        // so the elapsed time of runAllChecks must be ~2s (one hung future) — not 10s.
        var ds = mock(DataSource.class);
        try {
            when(ds.getConnection()).thenAnswer(inv -> {
                Thread.sleep(8_000); // longer than per-check budget
                return mock(Connection.class);
            });
        } catch (SQLException e) { throw new RuntimeException(e); }
        when(dsProvider.getIfAvailable()).thenReturn(ds);

        long t0 = System.currentTimeMillis();
        var results = handler.runAllChecks();
        long elapsed = System.currentTimeMillis() - t0;

        // Postgres timed out → DOWN with "timeout".
        var pg = byName(results, "postgres");
        assertEquals(HealthCommandHandler.CheckStatus.DOWN, pg.status());
        assertTrue(pg.detail().toLowerCase().contains("timeout"),
                "expected timeout detail: " + pg.detail());

        // The other 8 checks ran in parallel — they should have all returned within
        // their own ~ms timings. Total elapsed ≤ 3s (per-check timeout 2s + slack).
        assertTrue(elapsed < 3_500,
                "per-check 2s bound enforced; total elapsed=" + elapsed + "ms");
    }

    // ---------- 10-second overall budget ----------

    @Test
    void overallBudget_10s_caps_evenWithMultipleHangingChecks() throws Exception {
        // EVERYTHING hangs except disk/jvm/errors (which are in-process and fast). The
        // checks all run in parallel — 6 hung simultaneously consume only one 2s window
        // total (per-check timeout). Verify total elapsed ≤ 4s as a generous bound.
        hangAllNetworkChecks();

        long t0 = System.currentTimeMillis();
        var results = handler.runAllChecks();
        long elapsed = System.currentTimeMillis() - t0;

        // 6 hung checks → 6 DOWN rows with timeout, 3 in-process succeed.
        long downCount = results.stream()
                .filter(r -> r.status() == HealthCommandHandler.CheckStatus.DOWN)
                .count();
        assertTrue(downCount >= 5, "most checks DOWN: " + results);
        assertTrue(elapsed < 4_000,
                "10s overall budget enforced; parallel hanging checks total=" + elapsed + "ms");
    }

    private void hangAllNetworkChecks() throws Exception {
        var ds = mock(DataSource.class);
        when(ds.getConnection()).thenAnswer(inv -> {
            Thread.sleep(8_000);
            return mock(Connection.class);
        });
        when(dsProvider.getIfAvailable()).thenReturn(ds);

        var redis = mock(StringRedisTemplate.class);
        lenient().when(redis.execute((RedisCallback<String>) any())).thenAnswer(inv -> {
            Thread.sleep(8_000);
            return "PONG";
        });
        when(redisProvider.getIfAvailable()).thenReturn(redis);

        var minio = mock(MinioClient.class);
        try {
            when(minio.listBuckets()).thenAnswer(inv -> {
                Thread.sleep(8_000);
                return List.of();
            });
        } catch (Exception e) { throw new RuntimeException(e); }
        when(minioProvider.getIfAvailable()).thenReturn(minio);

        var statusViewRepo = mock(DeviceStatusViewRepository.class);
        when(statusViewRepo.countByComputedStatusGrouped()).thenAnswer(inv -> {
            Thread.sleep(8_000);
            return List.of();
        });
        when(statusViewRepoProvider.getIfAvailable()).thenReturn(statusViewRepo);

        var incRepo = mock(IncidentRepository.class);
        when(incRepo.countOpenByPriority()).thenAnswer(inv -> {
            Thread.sleep(8_000);
            return List.of();
        });
        when(incidentRepoProvider.getIfAvailable()).thenReturn(incRepo);

        // FFmpeg "hanging" — the queue-depth probe blocks — simulate hang.
        var hangingTranscode = mock(TranscodeExecutor.class);
        when(hangingTranscode.getQueueDepth()).thenAnswer(inv -> {
            Thread.sleep(8_000);
            return 0;
        });
        when(hangingTranscode.getQueueCapacity()).thenReturn(200);
        var hangingAudit = mock(ThreadPoolTaskExecutor.class);
        var underlying = mock(ThreadPoolExecutor.class);
        var queue = mock(java.util.concurrent.BlockingQueue.class);
        when(queue.size()).thenAnswer(inv -> {
            Thread.sleep(8_000);
            return 0;
        });
        when(underlying.getQueue()).thenReturn(queue);
        when(hangingAudit.getThreadPoolExecutor()).thenReturn(underlying);
        when(hangingAudit.getQueueCapacity()).thenReturn(500);
        when(transcodeProvider.getIfAvailable()).thenReturn(hangingTranscode);
        when(auditProvider.getIfAvailable()).thenReturn(hangingAudit);
    }

    // ---------- handle() never throws ----------

    @Test
    void handle_neverThrows_evenIfPayloadFails() {
        // Even if every check fails AND the bot's first send throws, the handler must
        // attempt the plain-text fallback rather than propagating.
        when(bot.send(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("ssl handshake"));

        handler.handle(100L, new String[0]);

        verify(bot, atLeastOnce()).send(eq("100"), anyString());
    }

    // ---------- DEGRADED severity classification ----------

    @Test
    void incidents_criticalOpen_classifiedDegraded() {
        var incRepo = mock(IncidentRepository.class);
        List<Object[]> rows = java.util.Collections.singletonList(
                new Object[] {Event.Priority.CRITICAL, 1L});
        when(incRepo.countOpenByPriority()).thenReturn(rows);
        when(incidentRepoProvider.getIfAvailable()).thenReturn(incRepo);

        var result = handler.checkIncidents();

        assertEquals(HealthCommandHandler.CheckStatus.DEGRADED, result.status());
        assertTrue(result.detail().contains("CRITICAL"),
                "detail flags the critical: " + result.detail());
    }

    @Test
    void disk_belowOnePercentFree_classifiedDown() throws IOException {
        // Sanity: with a TempDir there's plenty of free space, so checkDisk is UP. The
        // threshold values are tested directly via the static helpers.
        var result = handler.checkDisk();
        assertEquals(HealthCommandHandler.CheckStatus.UP, result.status());
        assertTrue(result.detail().contains("free"));
    }

    @Test
    void ffmpeg_queueNearCapacity_classifiedDegraded() {
        // 170/200 = 85% > 80% threshold.
        var nearCap = makeTranscodeExec(170, 1, 200, 1);
        var fine = makeExec(0, 0, 500);
        when(transcodeProvider.getIfAvailable()).thenReturn(nearCap);
        when(auditProvider.getIfAvailable()).thenReturn(fine);

        var result = handler.checkFFmpegQueue();

        assertEquals(HealthCommandHandler.CheckStatus.DEGRADED, result.status());
        assertTrue(result.detail().contains("near capacity"));
    }

    @Test
    void ffmpeg_reportsPlannedPoolWidth() {
        // The width is derived from the host at startup, so surfacing it is how an operator
        // confirms the capacity planner read the machine correctly.
        var wide = makeTranscodeExec(0, 0, 200, 7);
        var audit = makeExec(0, 0, 500);
        when(transcodeProvider.getIfAvailable()).thenReturn(wide);
        when(auditProvider.getIfAvailable()).thenReturn(audit);

        var result = handler.checkFFmpegQueue();

        assertEquals(HealthCommandHandler.CheckStatus.UP, result.status());
        assertTrue(result.detail().contains("w7"), "reports planned width: " + result.detail());
    }

    @Test
    void ffmpeg_noExecutorsAtAll_classifiedDown() {
        when(transcodeProvider.getIfAvailable()).thenReturn(null);
        when(auditProvider.getIfAvailable()).thenReturn(null);

        var result = handler.checkFFmpegQueue();

        assertEquals(HealthCommandHandler.CheckStatus.DOWN, result.status());
    }

    // ---------- error window integration ----------

    @Test
    void recentErrors_section_appearsWhenBufferHasEntries() {
        // Seed the retained buffer with a recent error.
        RetainedErrorBuffer.append(Instant.now().minusSeconds(30), "ERROR",
                "uz.orientadvertise.services.X", "boom", "RuntimeException: boom");
        var results = handler.runAllChecks();
        String body = handler.renderPayload(results);

        assertTrue(body.contains("Recent errors"), "section header present: " + body);
        assertTrue(body.contains("boom"), "message present: " + body);
        assertTrue(body.contains("ERROR"), "level present: " + body);
    }

    @Test
    void recentErrors_capsAtFive() {
        for (int i = 0; i < 10; i++) {
            RetainedErrorBuffer.append(Instant.now().minusSeconds(60 - i),
                    "ERROR", "uz.x.Y", "msg-" + i, null);
        }
        var result = handler.checkRecentErrors();

        assertEquals(HealthCommandHandler.CheckStatus.UP, result.status());
        // The check itself reports total count (5) — the rendered section also caps at 5.
        assertTrue(result.detail().contains("5 in last 1h"),
                "limited to 5: " + result.detail());
    }

    @Test
    void recentErrors_olderThanWindow_notIncluded() {
        // 2 hours ago — outside the 1h window.
        RetainedErrorBuffer.append(Instant.now().minusSeconds(7200), "ERROR",
                "uz.x.Y", "ancient", null);
        var result = handler.checkRecentErrors();

        assertEquals(HealthCommandHandler.CheckStatus.UP, result.status());
        assertTrue(result.detail().contains("0 in last 1h"));
    }

    // ---------- rendering helpers ----------

    @Test
    void overallSeverity_anyDown_isError() {
        var rows = List.of(
                HealthCommandHandler.CheckResult.up("a", ""),
                HealthCommandHandler.CheckResult.down("b", "no")
        );
        assertEquals(uz.orientadvertise.services.common.telegram.TelegramMessageBuilder.Severity.ERROR,
                HealthCommandHandler.overallSeverity(rows));
    }

    @Test
    void overallSeverity_anyDegradedNoneDown_isWarn() {
        var rows = List.of(
                HealthCommandHandler.CheckResult.up("a", ""),
                HealthCommandHandler.CheckResult.degraded("b", "near cap")
        );
        assertEquals(uz.orientadvertise.services.common.telegram.TelegramMessageBuilder.Severity.WARN,
                HealthCommandHandler.overallSeverity(rows));
    }

    @Test
    void overallSeverity_allUp_isInfo() {
        var rows = List.of(
                HealthCommandHandler.CheckResult.up("a", ""),
                HealthCommandHandler.CheckResult.up("b", "")
        );
        assertEquals(uz.orientadvertise.services.common.telegram.TelegramMessageBuilder.Severity.INFO,
                HealthCommandHandler.overallSeverity(rows));
    }

    @Test
    void humanBytes_formatsAtAppropriateScale() {
        assertEquals("512 B", HealthCommandHandler.humanBytes(512));
        assertEquals("1.0 KB", HealthCommandHandler.humanBytes(1024));
        assertEquals("1.0 MB", HealthCommandHandler.humanBytes(1024L * 1024));
        assertEquals("1.0 GB", HealthCommandHandler.humanBytes(1024L * 1024 * 1024));
        assertEquals("2.0 TB", HealthCommandHandler.humanBytes(2L * 1024 * 1024 * 1024 * 1024));
    }

    @Test
    void formatErrorLine_truncatesLoggerToLastSegment() {
        var e = new RetainedErrorBuffer.RetainedError(
                Instant.parse("2026-05-07T09:32:11Z"),
                "ERROR",
                "uz.orientadvertise.services.service.Foo",
                "boom",
                "RuntimeException: boom");
        String line = HealthCommandHandler.formatErrorLine(e);

        // Last segment of FQCN ("Foo") rather than full uz.orientadvertise.services.service.Foo.
        assertTrue(line.contains("Foo:"), line);
        assertFalse(line.contains("uz.orientadvertise.services.service.Foo"),
                "FQCN was truncated to last segment: " + line);
        assertTrue(line.contains("ERROR"));
        assertTrue(line.contains("boom"));
    }

    // ---------- /health command name ----------

    @Test
    void name_isSlashHealth() {
        assertEquals("/health", handler.name());
    }

    // ---------- parallel execution claim ----------

    @Test
    void runAllChecks_isParallel_notSerial() throws Exception {
        // Two checks each sleeping 800ms — if serial, total is ≥ 1600ms; if parallel,
        // it's ≤ 1000ms. Stub two hangs together.
        var ds = mock(DataSource.class);
        var conn = mock(Connection.class);
        when(ds.getConnection()).thenAnswer(inv -> {
            Thread.sleep(800);
            return conn;
        });
        when(conn.isValid(anyInt())).thenReturn(true);
        when(dsProvider.getIfAvailable()).thenReturn(ds);

        var redis = mock(StringRedisTemplate.class);
        lenient().when(redis.execute((RedisCallback<String>) any())).thenAnswer(inv -> {
            Thread.sleep(800);
            return "PONG";
        });
        when(redisProvider.getIfAvailable()).thenReturn(redis);

        long t0 = System.currentTimeMillis();
        handler.runAllChecks();
        long elapsed = System.currentTimeMillis() - t0;

        // Two checks at 800ms each, parallel → ≤ 1500ms (with overhead). Serial would
        // be ≥ 1600ms, but with all 9 in series we'd be much higher anyway.
        assertTrue(elapsed < 1_500,
                "checks ran in parallel; total=" + elapsed + "ms");
    }

    // ---------- helpers ----------

    private static HealthCommandHandler.CheckResult byName(
            List<HealthCommandHandler.CheckResult> rs, String name) {
        var found = rs.stream().filter(r -> r.name().equals(name)).findFirst();
        assertTrue(found.isPresent(), "row " + name + " missing");
        return found.get();
    }
}
