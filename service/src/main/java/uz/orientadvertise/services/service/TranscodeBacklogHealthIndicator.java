package uz.orientadvertise.services.service;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uz.orientadvertise.services.common.util.DateUtils;
import uz.orientadvertise.services.domain.model.HealthStatus;
import uz.orientadvertise.services.domain.repository.ContentFileRepository;

/**
 * Reports whether uploads are actually being transcoded.
 *
 * <p>This is the metric whose absence let the v1.0.132 incident run for hours: two files sat in
 * {@code UPLOADED} forever, and nothing anywhere noticed. {@code GlobalExceptionHandler} forwards
 * only 500s to Telegram and this condition never throws, so no alarm was ever going to fire — the
 * failure surfaced when a user complained. A count of files stuck in {@code UPLOADED} past a
 * threshold would have surfaced it in minutes.
 *
 * <p>Surfaced through {@code GET /api/health} alongside the database check, so a stuck pipeline
 * shows up as {@code overallStatus: "DEGRADED"} rather than silence. It is reported as DOWN with a
 * count rather than a hard failure elsewhere on purpose: the application is serving traffic
 * perfectly well, it is the content pipeline that is not — and DEGRADED is exactly that
 * distinction.
 *
 * <p>A probe failure is itself reported DOWN rather than thrown: this endpoint is unauthenticated
 * and must never leak a JDBC error, and must never 500.
 */
@Component
public class TranscodeBacklogHealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(TranscodeBacklogHealthIndicator.class);
    private static final String COMPONENT = "transcode-backlog";

    private final ContentFileRepository contentFileRepository;
    private final Duration staleAfter;

    /**
     * @param staleAfter same property the sweeper alerts on, so the health surface and the log/
     *                   Telegram alert can never disagree about what "stuck" means
     */
    public TranscodeBacklogHealthIndicator(
            ContentFileRepository contentFileRepository,
            @Value("${app.video.sweeper.stale-alert-after:PT10M}") Duration staleAfter) {
        this.contentFileRepository = contentFileRepository;
        this.staleAfter = staleAfter;
    }

    public HealthStatus check() {
        try {
            long stuck = contentFileRepository.countStaleUploaded(Instant.now().minus(staleAfter));
            var status = stuck == 0
                    ? new HealthStatus.Status.Up()
                    : new HealthStatus.Status.Down(
                            "%d content file(s) stuck in UPLOADED for more than %s".formatted(stuck, staleAfter));
            return new HealthStatus(COMPONENT, status, DateUtils.nowIso());
        } catch (Exception e) {
            log.warn("Transcode backlog health check failed: {}", e.getMessage());
            return new HealthStatus(COMPONENT,
                    new HealthStatus.Status.Down("backlog query failed"), DateUtils.nowIso());
        }
    }
}
