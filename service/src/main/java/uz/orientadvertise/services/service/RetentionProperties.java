package uz.orientadvertise.services.service;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

import jakarta.validation.constraints.Positive;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

/**
 * Retention configuration, bound from {@code app.retention.*} and consumed by
 * {@link RetentionCleanupService} (what the nightly job deletes) and {@link PlaybackLogService}
 * (how old a playback report may be before it is refused).
 *
 * <p><b>Each table has its OWN window.</b> They used to share one 90-day constant, which meant the
 * cheapest table to keep set the price for the most expensive one:
 * <ul>
 *   <li>{@link #audit} — {@code audit_log}, 14 days. Every row holds a full HTTP request
 *       <i>and</i> response body, and <b>nothing reads the table</b>: no API, no UI, no report.
 *       Since v1.0.143 {@code AuditFilter} does not audit device-agent traffic at all, so what is
 *       left is operator/admin writes — a short forensic tail is what that is worth.</li>
 *   <li>{@link #playback} — {@code playback_log}, 90 days. This is proof-of-play: advertisers are
 *       billed from it and {@code StatsController} reports over it. Do not shorten it to save disk.
 *       {@code PlaybackLogService} rejects a report older than this same window, so the two cannot
 *       drift into "accepted tonight, deleted tomorrow".</li>
 *   <li>{@link #event} — {@code event}, 90 days. Incident provenance; events still referenced by an
 *       open incident are kept regardless.</li>
 * </ul>
 *
 * <p>{@link #maxRunDuration} is the real bound on one run — a wall-clock budget shared by all three
 * tables. {@link #maxBatchesPerRun} is only a safety stop against an endless loop (a delete that
 * never actually removes the rows it counted), which is why it is set high: the old cap of 100
 * batches silently made {@code playback_log} undrainable past ~26 always-on devices.
 *
 * <p><b>Every value here is a DELETE bound, so a typo destroys data rather than degrading it — the
 * app refuses to start on a bad one.</b> Two specific traps are closed:
 * <ul>
 *   <li><b>The bare-number unit.</b> Spring Boot reads an unsuffixed duration as MILLISECONDS, so
 *       {@code APP_RETENTION_PLAYBACK=90} — the obvious way to write "90 days" — would bind as 90 ms
 *       and the next nightly run would delete every proof-of-play row on the box. {@code @DurationUnit}
 *       makes a bare number mean days for the three retention windows (minutes for the run budget,
 *       where days would be the absurd reading). An explicit ISO-8601 value still wins.</li>
 *   <li><b>Zero, negative and tiny windows.</b> A threshold of {@code now} or later means "delete
 *       everything", and for {@code playback} it ALSO makes {@code PlaybackLogService} reject every
 *       incoming report, so the data is gone and cannot come back. One day is the floor; use a
 *       staging box, not a shorter window, to watch the job work.</li>
 * </ul>
 * Validation runs at binding time, so the failure names the property and happens at startup —
 * never at 02:00 against the production tables.
 */
@Validated
@ConfigurationProperties(prefix = "app.retention")
public class RetentionProperties {

    /** {@code audit_log} window. Short on purpose — see the class javadoc. */
    @DurationUnit(ChronoUnit.DAYS)
    @DurationMin(days = 1, message = "app.retention.audit must be at least 1 day")
    private Duration audit = Duration.ofDays(14);

    /** {@code playback_log} window. Proof-of-play; also the bound on an accepted report's age. */
    @DurationUnit(ChronoUnit.DAYS)
    @DurationMin(days = 1, message = "app.retention.playback must be at least 1 day")
    private Duration playback = Duration.ofDays(90);

    /** {@code event} window. Events held by an open incident survive it. */
    @DurationUnit(ChronoUnit.DAYS)
    @DurationMin(days = 1, message = "app.retention.event must be at least 1 day")
    private Duration event = Duration.ofDays(90);

    /** Rows deleted per transaction. Also the "was that a full batch?" test that keeps the drain going. */
    @Positive(message = "app.retention.batch-size must be greater than 0")
    private int batchSize = 1000;

    /**
     * Wall-clock budget for the WHOLE run. A table that runs out logs its backlog at INFO.
     * A bare number here means MINUTES, not days — this one is a timeout, not a window.
     */
    @DurationUnit(ChronoUnit.MINUTES)
    @DurationMin(minutes = 1, message = "app.retention.max-run-duration must be at least 1 minute")
    private Duration maxRunDuration = Duration.ofMinutes(15);

    /** Safety stop on batches per table per run. Deliberately high — the budget is the real limit. */
    @Positive(message = "app.retention.max-batches-per-run must be greater than 0")
    private int maxBatchesPerRun = 5000;

    /** Refuse to run outside the 01:00–04:00 maintenance window, even when triggered by hand. */
    private boolean guardWindow = true;

    public Duration getAudit() { return audit; }
    public void setAudit(Duration audit) { this.audit = audit; }

    public Duration getPlayback() { return playback; }
    public void setPlayback(Duration playback) { this.playback = playback; }

    public Duration getEvent() { return event; }
    public void setEvent(Duration event) { this.event = event; }

    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }

    public Duration getMaxRunDuration() { return maxRunDuration; }
    public void setMaxRunDuration(Duration maxRunDuration) { this.maxRunDuration = maxRunDuration; }

    public int getMaxBatchesPerRun() { return maxBatchesPerRun; }
    public void setMaxBatchesPerRun(int maxBatchesPerRun) { this.maxBatchesPerRun = maxBatchesPerRun; }

    public boolean isGuardWindow() { return guardWindow; }
    public void setGuardWindow(boolean guardWindow) { this.guardWindow = guardWindow; }
}
