package uz.orientadvertise.services.infra.telegram;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramMetricsTest {

    private MeterRegistry registry;
    private TelegramMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new TelegramMetrics(registry);
    }

    @Test
    void registers_allTopLevelMeters_underTelegramNamespace() {
        // The spec requires the metrics live under the telegram.* namespace on
        // /actuator/metrics. Verify each meter is registered by name.
        assertNotNull(registry.find("telegram.sent").counter(), "telegram.sent");
        assertNotNull(registry.find("telegram.failed").counter(), "telegram.failed");
        assertNotNull(registry.find("telegram.failure_rate").gauge(), "telegram.failure_rate");
        assertNotNull(registry.find("telegram.last_failure_timestamp").gauge(),
                "telegram.last_failure_timestamp");
    }

    @Test
    void recordSuccess_incrementsSentCounter() {
        metrics.recordSuccess();
        metrics.recordSuccess();
        metrics.recordSuccess();
        assertEquals(3.0, metrics.sentTotal());
        assertEquals(0.0, metrics.failedTotal());
    }

    @Test
    void recordFailure_incrementsTotalAndPerReasonCounters() {
        metrics.recordFailure(TelegramMetrics.FailureReason.FORBIDDEN_403, "kicked");
        metrics.recordFailure(TelegramMetrics.FailureReason.TIMEOUT, "took 5s");
        metrics.recordFailure(TelegramMetrics.FailureReason.FORBIDDEN_403, "kicked again");

        assertEquals(3.0, metrics.failedTotal());
        // Per-reason breakdown — drives dashboards: "of 100 failures, 80 were 403s"
        Counter forbidden = registry.find("telegram.failed.by_reason")
                .tag("reason", "FORBIDDEN_403").counter();
        Counter timeout = registry.find("telegram.failed.by_reason")
                .tag("reason", "TIMEOUT").counter();
        assertNotNull(forbidden, "FORBIDDEN_403 tag registered");
        assertNotNull(timeout, "TIMEOUT tag registered");
        assertEquals(2.0, forbidden.count());
        assertEquals(1.0, timeout.count());
    }

    @Test
    void failureRate_isLifetimeAverage_overSentPlusFailed() {
        // 3 successes + 1 failure → 25% failure rate
        metrics.recordSuccess();
        metrics.recordSuccess();
        metrics.recordSuccess();
        metrics.recordFailure(TelegramMetrics.FailureReason.OTHER, "?");

        assertEquals(0.25, metrics.lifetimeFailureRate(), 0.0001);
        assertEquals(0.25, registry.find("telegram.failure_rate").gauge().value(), 0.0001);
    }

    @Test
    void failureRate_zeroSamples_returnsZero_notNaN() {
        // Division-by-zero defensive — gauge consumers (Prometheus, etc.) treat NaN
        // as missing data, which would lose the meter on dashboards.
        assertEquals(0.0, metrics.lifetimeFailureRate());
        assertEquals(0.0, registry.find("telegram.failure_rate").gauge().value());
    }

    @Test
    void lastFailureTimestamp_zeroBeforeFirstFailure() {
        assertEquals(0L, metrics.lastFailureEpochMillis());
        assertEquals(0.0, registry.find("telegram.last_failure_timestamp").gauge().value());
    }

    @Test
    void lastFailureTimestamp_updatedOnEachFailure() {
        long beforeMs = System.currentTimeMillis();
        metrics.recordFailure(TelegramMetrics.FailureReason.OTHER, "boom");
        long afterMs = System.currentTimeMillis();

        long ts = metrics.lastFailureEpochMillis();
        assertTrue(ts >= beforeMs && ts <= afterMs,
                "timestamp within recording window: " + ts);
    }

    @Test
    void lastFailureReason_capturesCategoryAndDetail() {
        metrics.recordFailure(TelegramMetrics.FailureReason.FORBIDDEN_403,
                "Forbidden: bot was kicked from the group chat");

        String reason = metrics.lastFailureReason();
        assertTrue(reason.contains("FORBIDDEN_403"), "category: " + reason);
        assertTrue(reason.contains("kicked from"), "detail preserved: " + reason);
    }

    @Test
    void lastFailureReason_truncatesLongDetail() {
        metrics.recordFailure(TelegramMetrics.FailureReason.OTHER, "x".repeat(500));
        String reason = metrics.lastFailureReason();
        assertTrue(reason.length() < 250, "long detail truncated: length=" + reason.length());
    }

    @Test
    void lastFailureReason_blankDetail_substitutesCategory() {
        metrics.recordFailure(TelegramMetrics.FailureReason.NETWORK, "");
        String reason = metrics.lastFailureReason();
        assertTrue(reason.contains("NETWORK"));
    }

    @Test
    void lastFailureReason_initiallyNone() {
        assertEquals("none", metrics.lastFailureReason());
    }
}
