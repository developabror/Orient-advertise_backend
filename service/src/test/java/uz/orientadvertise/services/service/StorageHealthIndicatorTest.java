package uz.orientadvertise.services.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.orientadvertise.services.domain.model.HealthStatus;
import uz.orientadvertise.services.infra.storage.MinioHealthStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageHealthIndicatorTest {

    private MinioHealthStatus status;
    private StorageHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        status = new MinioHealthStatus();
        indicator = new StorageHealthIndicator(status);
    }

    @Test
    void up_isUp() {
        status.markUp();

        var result = indicator.check();

        assertEquals("storage", result.component());
        assertTrue(result.isUp());
    }

    /**
     * Read the Down reason, failing the test if the status is not Down. An
     * {@code if (x instanceof Down(var r))} that guards the assertions makes them vanish silently
     * when the shape changes — see tasks/lessons.md.
     */
    private static String downReason(HealthStatus health) {
        var down = assertInstanceOf(HealthStatus.Status.Down.class, health.status(),
                () -> "expected the storage component to be DOWN, was " + health.statusName());
        return down.reason();
    }

    @Test
    void degraded_isDown_andCarriesTheReasonAndSince() {
        status.markUp();   // a fresh status is already DEGRADED ("not probed yet")
        status.markDegraded("upload: ConnectException");

        String reason = downReason(indicator.check());

        assertTrue(reason.contains("upload: ConnectException"), reason);
        // "down" and "down since 04:12" are different operator decisions.
        assertTrue(reason.contains("since "), reason);
    }

    @Test
    void freshProcess_reportsDown_becauseStorageEndpointsReally503InThatWindow() {
        // Nothing has probed MinIO yet (pre-ApplicationRunner). Reporting UP here would be a lie
        // that lasts exactly as long as the window in which uploads actually fail.
        var result = indicator.check();

        assertFalse(result.isUp());
        assertTrue(downReason(result).contains("not probed"), downReason(result));
    }

    @Test
    void recoversWithoutARestart_whenTheProbeHealsTheLatch() {
        // The whole point of v1.0.144: this transition used to be reachable only by restarting.
        status.markUp();
        status.markDegraded("object stat: SocketTimeoutException");
        assertFalse(indicator.check().isUp());

        status.markUp();

        assertTrue(indicator.check().isUp());
    }
}
