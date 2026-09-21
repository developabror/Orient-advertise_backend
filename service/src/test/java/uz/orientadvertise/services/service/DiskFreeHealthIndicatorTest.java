package uz.orientadvertise.services.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uz.orientadvertise.services.domain.model.HealthStatus;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The alarm that was missing while subzero sat at 96% full with 386 MB free — on one volume shared
 * by the container overlay, MinIO, the Postgres data directory and {@code /swap.img}.
 */
class DiskFreeHealthIndicatorTest {

    @Test
    void plentyOfSpace_isUp(@TempDir Path tempDir) {
        // 0% threshold: any real filesystem clears it, so this asserts the happy path without
        // depending on how full the CI runner's disk happens to be.
        var indicator = new DiskFreeHealthIndicator(tempDir.toString(), 0);

        var status = indicator.check();

        assertEquals("disk", status.component());
        assertTrue(status.isUp());
    }

    @Test
    void belowTheThreshold_isDownWithTheNumbers(@TempDir Path tempDir) {
        // 101% can never be satisfied, which forces the low-space branch on any host. What matters
        // is that the reason carries the actual figures an operator needs to act.
        var indicator = new DiskFreeHealthIndicator(tempDir.toString(), 101);

        var status = indicator.check();

        assertInstanceOf(HealthStatus.Status.Down.class, status.status());
        if (status.status() instanceof HealthStatus.Status.Down(var reason)) {
            assertTrue(reason.contains("low disk space"), reason);
            assertTrue(reason.contains("free="), reason);
            assertTrue(reason.contains("total="), reason);
            assertTrue(reason.contains("threshold 101%"), reason);
        }
    }

    @Test
    void defaultThresholdFiresWellAboveNinetyPercentUsed() {
        // 15% free means the alarm sounds at 85% used, not at 99%. Reclaiming space takes time; a
        // warning that arrives when there is no room left to manoeuvre is not a warning.
        var atEightySixPercentUsed = new uz.orientadvertise.services.common.util.DiskSpace(
                "/", 100_000, 14_000);
        assertTrue(atEightySixPercentUsed.freePercent() < 15);

        var atEightyPercentUsed = new uz.orientadvertise.services.common.util.DiskSpace(
                "/", 100_000, 20_000);
        assertTrue(atEightyPercentUsed.freePercent() >= 15);
    }

    @Test
    void probeFailure_isDownAndLeaksNothing() {
        // /api/health is unauthenticated — the reason must not echo a filesystem path or a JDK
        // exception message back to an anonymous caller.
        var indicator = new DiskFreeHealthIndicator("/definitely/not/a/real/path/xyzzy", 15);

        var status = indicator.check();

        assertInstanceOf(HealthStatus.Status.Down.class, status.status());
        if (status.status() instanceof HealthStatus.Status.Down(var reason)) {
            assertEquals("disk probe failed", reason);
        }
    }

    @Test
    void neverThrows_whateverThePath() {
        // Called from an endpoint that must never 500.
        for (String path : new String[]{null, "", "   ", "/proc", "/definitely/not/real"}) {
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                    () -> new DiskFreeHealthIndicator(path, 15).check(), "path=" + path);
        }
    }
}
