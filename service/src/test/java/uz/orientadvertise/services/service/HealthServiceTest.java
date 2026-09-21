package uz.orientadvertise.services.service;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.orientadvertise.services.domain.model.HealthStatus;
import uz.orientadvertise.services.infra.health.DatabaseHealthIndicator;
import uz.orientadvertise.services.infra.storage.StorageLifecycleStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HealthServiceTest {

    private DatabaseHealthIndicator dbIndicator;
    private TranscodeBacklogHealthIndicator backlogIndicator;
    private DiskFreeHealthIndicator diskIndicator;
    private StorageLifecycleHealthIndicator lifecycleIndicator;
    private StorageHealthIndicator storageIndicator;
    private HealthService healthService;

    @BeforeEach
    void setUp() {
        dbIndicator = mock(DatabaseHealthIndicator.class);
        backlogIndicator = mock(TranscodeBacklogHealthIndicator.class);
        diskIndicator = mock(DiskFreeHealthIndicator.class);
        lifecycleIndicator = mock(StorageLifecycleHealthIndicator.class);
        storageIndicator = mock(StorageHealthIndicator.class);
        when(backlogIndicator.check()).thenReturn(up("transcode-backlog"));
        when(diskIndicator.check()).thenReturn(up("disk"));
        when(lifecycleIndicator.check()).thenReturn(up("storage-lifecycle"));
        when(storageIndicator.check()).thenReturn(up("storage"));
        healthService = new HealthService(dbIndicator, backlogIndicator, diskIndicator,
                lifecycleIndicator, storageIndicator);
    }

    private static HealthStatus up(String component) {
        return new HealthStatus(component, new HealthStatus.Status.Up(), "2024-01-01T00:00:00Z");
    }

    private static HealthStatus down(String component, String reason) {
        return new HealthStatus(component, new HealthStatus.Status.Down(reason), "2024-01-01T00:00:00Z");
    }

    @Test
    void checkAll_returnsAllComponentsUp() {
        when(dbIndicator.check()).thenReturn(
                new HealthStatus("database", new HealthStatus.Status.Up(), "2024-01-01T00:00:00Z")
        );

        var result = healthService.checkAll();

        assertEquals(6, result.size());
        assertEquals(List.of("application", "database", "transcode-backlog", "disk",
                        "storage-lifecycle", "storage"),
                result.stream().map(HealthStatus::component).toList());
        assertTrue(result.stream().allMatch(HealthStatus::isUp));
    }

    @Test
    void checkAll_reflectsDatabaseDown() {
        when(dbIndicator.check()).thenReturn(
                new HealthStatus("database", new HealthStatus.Status.Down("conn refused"), "2024-01-01T00:00:00Z")
        );

        var result = healthService.checkAll();

        assertEquals(6, result.size());
        assertInstanceOf(HealthStatus.Status.Down.class, result.get(1).status());

        // JDK 21 record pattern — extract reason from Down
        if (result.get(1).status() instanceof HealthStatus.Status.Down(var reason)) {
            assertEquals("conn refused", reason);
        }
    }

    @Test
    void checkAll_reflectsTranscodeBacklog() {
        // A stuck transcode pipeline must be visible on /api/health as DEGRADED — the metric whose
        // absence let two production uploads sit unnoticed until a user complained.
        when(dbIndicator.check()).thenReturn(
                new HealthStatus("database", new HealthStatus.Status.Up(), "2024-01-01T00:00:00Z"));
        when(backlogIndicator.check()).thenReturn(new HealthStatus("transcode-backlog",
                new HealthStatus.Status.Down("2 content file(s) stuck in UPLOADED for more than PT10M"),
                "2024-01-01T00:00:00Z"));

        var result = healthService.checkAll();

        assertEquals(6, result.size());
        assertTrue(result.get(1).isUp(), "database stays UP — only the pipeline is degraded");
        assertInstanceOf(HealthStatus.Status.Down.class, result.get(2).status());
        if (result.get(2).status() instanceof HealthStatus.Status.Down(var reason)) {
            assertTrue(reason.contains("2 content file(s)"), reason);
        }
    }

    @Test
    void checkAll_reflectsLowDiskSpace() {
        // Subzero sat at 96% full with no alarm anywhere. One volume carries the container overlay,
        // MinIO, the Postgres data directory and /swap.img — when it fills, all four go together.
        when(dbIndicator.check()).thenReturn(up("database"));
        when(diskIndicator.check()).thenReturn(
                down("disk", "low disk space — free=386.0 MB / total=8.1 GB (4% free, path=/), threshold 15%"));

        var result = healthService.checkAll();

        assertTrue(result.get(1).isUp(), "the database is fine — the volume under it is not");
        assertInstanceOf(HealthStatus.Status.Down.class, result.get(3).status());
    }

    @Test
    void checkAll_reflectsAFailedStorageLifecycleInstall() {
        // Previously invisible past one boot WARN, which is how the raw bucket grew unbounded.
        when(dbIndicator.check()).thenReturn(up("database"));
        when(lifecycleIndicator.check()).thenReturn(
                down("storage-lifecycle", "lifecycle install rejected: The XML you provided was not well-formed"));

        var result = healthService.checkAll();

        assertInstanceOf(HealthStatus.Status.Down.class, result.get(4).status());
    }

    @Test
    void checkAll_reflectsAnUnreachableMinio_withItsReason() {
        // MinIO was absent from /api/health entirely until v1.0.144 — the one dependency whose
        // loss 503s every upload, fails every transcode and blocks every device sync, and the
        // health surface said nothing about it.
        when(dbIndicator.check()).thenReturn(up("database"));
        when(storageIndicator.check()).thenReturn(
                down("storage", "upload: ConnectException (since 2026-09-20T04:12:00Z)"));

        var result = healthService.checkAll();

        assertEquals(6, result.size());
        var storage = result.getLast();
        assertEquals("storage", storage.component());
        // Read the reason out of the assertion, never inside an `if (… instanceof …)` that can
        // skip every assertion in silence (tasks/lessons.md).
        var down = assertInstanceOf(HealthStatus.Status.Down.class, storage.status());
        assertTrue(down.reason().contains("ConnectException"), down.reason());
        assertTrue(down.reason().contains("since"), down.reason());
        assertTrue(result.get(1).isUp(), "the database is fine — object storage is not");
    }

    @Test
    void checkApplication_returnsUp() {
        var result = healthService.checkApplication();

        assertEquals("application", result.component());
        assertTrue(result.isUp());
    }
}
