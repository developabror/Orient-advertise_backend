package uz.orientadvertise.services.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uz.orientadvertise.services.common.util.DateUtils;
import uz.orientadvertise.services.common.util.DiskSpace;
import uz.orientadvertise.services.domain.model.HealthStatus;

/**
 * Free space on the volume that carries everything.
 *
 * <p>Added because subzero was found at <b>96% full — 386 MB free</b> with no alarm anywhere, on a
 * single 8.1 G volume shared by the container overlay (so every transcode scratch file), the MinIO
 * data directory, the Postgres data directory <i>and</i> {@code /swap.img}. When that fills,
 * Postgres, MinIO and the container overlay fail together — and the transcode pipeline had just
 * become able to drive the cgroup into swap, i.e. writing swap pages onto the same full disk.
 *
 * <p>The threshold is deliberately generous: {@code app.health.disk-warn-free-percent} defaults to
 * 15%, so it fires at 85% used — well before the point where an operator has no room to manoeuvre.
 * Reclaiming space takes time; a warning that arrives at 99% is a warning that arrives too late.
 *
 * <p>Reads the same {@code app.health.data-volume-path} as the Telegram {@code /health} command and
 * shares {@link DiskSpace}'s arithmetic with it, so the two surfaces cannot disagree.
 *
 * <p><b>Do not rename this to {@code DiskSpaceHealthIndicator}.</b> That is the bean name Spring
 * Boot's actuator auto-configuration uses, and its factory method is
 * {@code @ConditionalOnMissingBean(name = "diskSpaceHealthIndicator")} — matched by NAME, not type.
 * A component called that silently displaces Boot's contributor, and since this class is not a
 * {@code HealthContributor}, {@code /actuator/health} loses its {@code diskSpace} check entirely.
 * Verified: with the colliding name the actuator contributors were {@code [db, ping, redis, ssl]}.
 * That check drives the container's health state on the production host, so losing it would have
 * been a silent regression shipped inside a hardening release.
 */
@Component
public class DiskFreeHealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(DiskFreeHealthIndicator.class);
    private static final String COMPONENT = "disk";

    private final String dataVolumePath;
    private final int warnFreePercent;

    public DiskFreeHealthIndicator(
            @Value("${app.health.data-volume-path:.}") String dataVolumePath,
            @Value("${app.health.disk-warn-free-percent:15}") int warnFreePercent) {
        this.dataVolumePath = dataVolumePath;
        this.warnFreePercent = warnFreePercent;
    }

    public HealthStatus check() {
        try {
            DiskSpace space = DiskSpace.probe(dataVolumePath);
            var status = space.freePercent() >= warnFreePercent
                    ? new HealthStatus.Status.Up()
                    : new HealthStatus.Status.Down(
                            "low disk space — " + space.describe() + ", threshold " + warnFreePercent + "%");
            return new HealthStatus(COMPONENT, status, DateUtils.nowIso());
        } catch (Exception e) {
            // /api/health is unauthenticated: log the detail, return a generic reason.
            log.warn("Disk space health check failed for path {}: {}", dataVolumePath, e.getMessage());
            return new HealthStatus(COMPONENT,
                    new HealthStatus.Status.Down("disk probe failed"), DateUtils.nowIso());
        }
    }
}
