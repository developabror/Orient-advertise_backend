package uz.orientadvertise.services.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.orientadvertise.services.domain.model.HealthStatus;
import uz.orientadvertise.services.infra.storage.StorageLifecycleStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageLifecycleHealthIndicatorTest {

    private StorageLifecycleStatus status;
    private StorageLifecycleHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        status = new StorageLifecycleStatus();
        indicator = new StorageLifecycleHealthIndicator(status);
    }

    @Test
    void installed_isUp() {
        status.markInstalled("expire raw/* after 30 day(s) on [content-raw]");

        var result = indicator.check();

        assertEquals("storage-lifecycle", result.component());
        assertTrue(result.isUp());
    }

    @Test
    void deliberatelyDisabled_isUp_becauseItIsAChoiceNotABreakage() {
        status.markDisabled("raw expiry disabled (app.minio.raw-expiry-days=0)");

        assertTrue(indicator.check().isUp());
    }

    @Test
    void pending_isUp_soAMinioOutageIsNotDoubleReported() {
        // PENDING means we never got to try — either pre-ApplicationReadyEvent or MinIO was
        // degraded. In the latter case the MinIO failure is the real signal; flagging this too
        // would just be noise on the same root cause.
        assertTrue(indicator.check().isUp(), "fresh status starts PENDING");

        status.markPending("MinIO degraded at startup — lifecycle not installed");
        assertTrue(indicator.check().isUp());
    }

    @Test
    void failed_isDownAndCarriesTheReason() {
        // The regression guard: this state ran unnoticed for the life of the deployment behind a
        // single boot WARN, while the volume climbed to 96% full.
        status.markFailed("lifecycle install rejected: The XML you provided was not well-formed");

        var result = indicator.check();

        assertInstanceOf(HealthStatus.Status.Down.class, result.status());
        if (result.status() instanceof HealthStatus.Status.Down(var reason)) {
            assertTrue(reason.contains("well-formed"), reason);
        }
    }

    @Test
    void recoversOnASubsequentSuccessfulInstall() {
        status.markFailed("rejected");
        assertTrue(!indicator.check().isUp());

        status.markInstalled("expire raw/* after 30 day(s) on [content-raw]");
        assertTrue(indicator.check().isUp(), "a later success must clear the failure");
    }
}
