package uz.orientadvertise.services.api;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uz.orientadvertise.services.Application;
import uz.orientadvertise.services.service.DeviceSyncService;
import uz.orientadvertise.services.service.DeviceSyncService.SyncPlan;
import uz.orientadvertise.services.service.FileStorageService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * VG-07 on the real wiring: {@code /sync} must not hold a pooled database connection while it talks
 * to object storage, and must not need a second one to write the anchor.
 *
 * <p>The test profile runs a pool of <b>3</b> with a 3-second connection timeout
 * ({@code application-test.yml}), so eight devices taking a brand-new campaign at once is the
 * production scenario in miniature: twenty-plus devices, a pool of twenty. Each storage check sleeps
 * 500 ms — a fraction of what a blackholed MinIO does on minio-java's 5-minute defaults.
 *
 * <p>On the old single-{@code @Transactional} shape this fails twice over: the storage stub asserts
 * it is called with no transaction attached (it was), and the anchor insert ran {@code REQUIRES_NEW},
 * so every request needed a second connection while still holding the first — three concurrent calls
 * were enough to deadlock the pool until its timeout, which surfaced as 500s for every other request
 * in the window.
 */
@SpringBootTest(classes = Application.class)
@ActiveProfiles("test")
class DeviceSyncConnectionPoolIntegrationTest {

    private static final int DEVICES = 8;
    private static final long PROJECT = 9300L;
    private static final long REGION = 9300L;
    private static final long PLAYLIST = 9300L;
    private static final long CONTENT = 9300L;
    private static final long ASSIGNMENT = 9300L;
    private static final long DEVICE_BASE = 930000L;

    /** Replaced wholesale: the real bean would need MinIO, and the stub is where the proof lives. */
    @MockitoBean private FileStorageService fileStorageService;

    @Autowired private DeviceSyncService syncService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final AtomicInteger insideTransaction = new AtomicInteger();

    @BeforeEach
    void seed() {
        when(fileStorageService.presignedProcessedUrl(anyString(), anyInt()))
                .thenReturn("https://minio.invalid/signed");
        when(fileStorageService.processedObjectExists(anyString())).thenAnswer(invocation -> {
            // The whole point: a storage round trip must not be made while a connection is held.
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                insideTransaction.incrementAndGet();
            }
            Thread.sleep(500);
            return true;
        });

        jdbcTemplate.update("DELETE FROM playback_sync_schedule WHERE assignment_id = ?", ASSIGNMENT);
        seedOnce("project", PROJECT, "INSERT INTO project (id, name, created_at, updated_at) "
                + "VALUES (?, 'SyncPoolProj', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        seedOnce("region", REGION, "INSERT INTO region (id, project_id, name, code, created_at, updated_at) "
                + "VALUES (?, 9300, 'SyncPoolRegion', 'SPR', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        seedOnce("playlist", PLAYLIST, "INSERT INTO playlist (id, project_id, name, created_at, updated_at) "
                + "VALUES (?, 9300, 'SyncPoolList', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        seedOnce("content_file", CONTENT, "INSERT INTO content_file (id, project_id, name, content_type, "
                + "size_bytes, storage_key, processed_storage_key, status, duration_seconds, created_at, updated_at) "
                + "VALUES (?, 9300, 'Clip', 'video/mp4', 1048576, 'raw/clip.mp4', 'processed/clip.mp4', "
                + "'READY', 30, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        seedOnce("playlist_item", CONTENT, "INSERT INTO playlist_item (id, playlist_id, content_file_id, "
                + "position, created_at) VALUES (?, 9300, 9300, 0, CURRENT_TIMESTAMP)");
        seedOnce("content_assignment", ASSIGNMENT, "INSERT INTO content_assignment (id, playlist_id, target_type, "
                + "target_id, priority, start_time, end_time, status, version_number, created_at, updated_at, "
                + "confirmed_at) VALUES (?, 9300, 'REGION', 9300, 1, CURRENT_TIMESTAMP - 1, "
                + "CURRENT_TIMESTAMP + 1, 'CONFIRMED', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        for (int i = 0; i < DEVICES; i++) {
            seedOnce("device", DEVICE_BASE + i, "INSERT INTO device (id, region_id, serial_number, name, status, "
                    + "created_at, updated_at) VALUES (?, 9300, 'SN-POOL-" + i + "', 'Pool TV " + i + "', 'ONLINE', "
                    + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        }
        insideTransaction.set(0);
    }

    @Test
    void eightDevicesTakingANewCampaignAtOnce_allSucceed_andShareOneAnchor() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(DEVICES);
        var startTogether = new CountDownLatch(1);
        try {
            List<Callable<SyncPlan>> calls = new ArrayList<>();
            for (int i = 0; i < DEVICES; i++) {
                long deviceId = DEVICE_BASE + i;
                calls.add(() -> {
                    startTogether.await(5, TimeUnit.SECONDS);
                    return syncService.computeSyncPlan(deviceId, null, Set.of());
                });
            }
            List<Future<SyncPlan>> futures = new ArrayList<>();
            for (Callable<SyncPlan> call : calls) {
                futures.add(pool.submit(call));
            }

            long startedAt = System.nanoTime();
            startTogether.countDown();
            var plans = new ArrayList<SyncPlan>();
            for (Future<SyncPlan> future : futures) {
                // A connection-pool deadlock surfaces here: every caller waits out the 3 s
                // connection timeout and then fails with CannotGetJdbcConnectionException.
                plans.add(future.get(30, TimeUnit.SECONDS));
            }
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

            assertEquals(DEVICES, plans.size());
            assertEquals(0, insideTransaction.get(),
                    "storage was called while a database transaction was open — that is the pool "
                            + "exhaustion of VG-07");
            assertTrue(elapsed.toMillis() < 10_000,
                    "8 concurrent syncs took " + elapsed.toMillis() + " ms; they should overlap, "
                            + "not queue behind a 3-connection pool");

            Long anchor = plans.get(0).anchorEpochMs();
            assertNotNull(anchor, "the first sync of a new campaign must anchor the cut-over");
            for (SyncPlan plan : plans) {
                assertEquals(anchor, plan.anchorEpochMs(),
                        "every member of the group must get the SAME anchor, whoever created it");
                assertEquals(1, plan.filesToAdd().size());
            }
            assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM playback_sync_schedule WHERE assignment_id = ?",
                    Integer.class, ASSIGNMENT), "exactly one anchor row, whoever won the race");
        } finally {
            pool.shutdownNow();
        }
    }

    private void seedOnce(String table, long id, String insertSql) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE id = ?", Integer.class, id);
        if (count == null || count == 0) {
            jdbcTemplate.update(insertSql, id);
        }
    }
}
