package uz.orientadvertise.services.service;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DATA-01: the defaults are load-bearing — they are what an untouched deployment actually deletes
 * by — and every one of them is overridable from the environment, because tuning retention on a box
 * must never need a rebuild.
 */
class RetentionPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RetentionCleanupService.RetentionConfig.class);

    @Test
    void defaults_areFourteenDayAuditAndNinetyDayPlaybackAndEvent() {
        runner.run(context -> {
            var props = context.getBean(RetentionProperties.class);
            // audit_log is short because nothing reads it and device traffic is no longer audited;
            // playback_log is proof-of-play and event is incident provenance, so both keep 90 days.
            assertThat(props.getAudit()).isEqualTo(Duration.ofDays(14));
            assertThat(props.getPlayback()).isEqualTo(Duration.ofDays(90));
            assertThat(props.getEvent()).isEqualTo(Duration.ofDays(90));
            assertThat(props.getBatchSize()).isEqualTo(1000);
            assertThat(props.getMaxRunDuration()).isEqualTo(Duration.ofMinutes(15));
            // A safety stop, not a work cap: the old 100 made playback_log undrainable.
            assertThat(props.getMaxBatchesPerRun()).isEqualTo(5000);
            assertThat(props.isGuardWindow()).isTrue();
        });
    }

    @Test
    void everyPropertyBindsFromItsConfigurationKey() {
        runner.withPropertyValues(
                "app.retention.audit=P3D",
                "app.retention.playback=P30D",
                "app.retention.event=P7D",
                "app.retention.batch-size=250",
                "app.retention.max-run-duration=PT90S",
                "app.retention.max-batches-per-run=12",
                "app.retention.guard-window=false").run(context -> {
            var props = context.getBean(RetentionProperties.class);
            assertThat(props.getAudit()).isEqualTo(Duration.ofDays(3));
            assertThat(props.getPlayback()).isEqualTo(Duration.ofDays(30));
            assertThat(props.getEvent()).isEqualTo(Duration.ofDays(7));
            assertThat(props.getBatchSize()).isEqualTo(250);
            assertThat(props.getMaxRunDuration()).isEqualTo(Duration.ofSeconds(90));
            assertThat(props.getMaxBatchesPerRun()).isEqualTo(12);
            assertThat(props.isGuardWindow()).isFalse();
        });
    }

    /** The documented operator lever is the env var, so bind one the way the container supplies it. */
    @Test
    void anEnvironmentVariableOverridesTheDefault() {
        runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource(
                                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                Map.of("APP_RETENTION_AUDIT", "P2D",
                                        "APP_RETENTION_BATCH_SIZE", "42",
                                        // the one hyphenated key, where relaxed binding could bite
                                        "APP_RETENTION_MAX_RUN_DURATION", "PT90S"))))
                .run(context -> {
                    var props = context.getBean(RetentionProperties.class);
                    assertThat(props.getAudit()).isEqualTo(Duration.ofDays(2));
                    assertThat(props.getBatchSize()).isEqualTo(42);
                    assertThat(props.getMaxRunDuration()).isEqualTo(Duration.ofSeconds(90));
                    assertThat(props.getPlayback()).isEqualTo(Duration.ofDays(90));  // untouched
                });
    }

    // ---------- units and fail-fast validation ----------

    /**
     * The footgun this closes: Spring Boot reads a bare duration as MILLISECONDS, so
     * {@code APP_RETENTION_PLAYBACK=90} — the obvious way to write "90 days" — would have bound as
     * 90 ms and the next nightly run would have deleted every proof-of-play row on the box.
     */
    @Test
    void aBareNumberMeansDaysForAWindow_andMinutesForTheRunBudget() {
        runner.withPropertyValues(
                "app.retention.playback=90",
                "app.retention.audit=14",
                "app.retention.event=7",
                "app.retention.max-run-duration=20").run(context -> {
            var props = context.getBean(RetentionProperties.class);
            assertThat(props.getPlayback()).isEqualTo(Duration.ofDays(90));
            assertThat(props.getAudit()).isEqualTo(Duration.ofDays(14));
            assertThat(props.getEvent()).isEqualTo(Duration.ofDays(7));
            // A timeout, not a window — days would be the absurd reading here.
            assertThat(props.getMaxRunDuration()).isEqualTo(Duration.ofMinutes(20));
        });
    }

    /**
     * Every value here bounds a DELETE, so a bad one must stop the app at startup rather than run
     * at 02:00 against the production tables. A zero or negative window puts the threshold at or
     * after {@code now} — "delete everything" — and for playback it would also make
     * {@code PlaybackLogService} reject every incoming report, so the data could not come back.
     */
    @ParameterizedTest
    @CsvSource({
            "app.retention.playback=PT0S,          app.retention.playback",
            "app.retention.playback=PT-24H,        app.retention.playback",
            "app.retention.audit=PT1H,             app.retention.audit",
            "app.retention.event=PT0S,             app.retention.event",
            "app.retention.max-run-duration=PT0S,  app.retention.max-run-duration",
            "app.retention.batch-size=0,           app.retention.batch-size",
            "app.retention.batch-size=-5,          app.retention.batch-size",
            "app.retention.max-batches-per-run=0,  app.retention.max-batches-per-run"})
    void aMisconfiguredBound_failsStartup_namingTheProperty(String property, String named) {
        runner.withPropertyValues(property.trim()).run(context ->
                assertThat(context).hasFailed()
                        .getFailure()
                        .hasStackTraceContaining(named.trim()));
    }

    @Test
    void theBoundaryValuesAreAccepted() {
        runner.withPropertyValues(
                "app.retention.audit=P1D",
                "app.retention.max-run-duration=PT1M",
                "app.retention.batch-size=1").run(context -> {
            assertThat(context).hasNotFailed();
            var props = context.getBean(RetentionProperties.class);
            assertThat(props.getAudit()).isEqualTo(Duration.ofDays(1));
            assertThat(props.getMaxRunDuration()).isEqualTo(Duration.ofMinutes(1));
            assertThat(props.getBatchSize()).isEqualTo(1);
        });
    }
}
