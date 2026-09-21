package uz.orientadvertise.services.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sweeps {@code PENDING}/{@code ACTIVE} remote sessions past their {@code expires_at} into
 * {@code EXPIRED}, modelled on {@link RemoteActionExpirationJob}.
 *
 * <p><b>This is a janitor, not the enforcement mechanism.</b> The device is contractually
 * required to kill scrcpy at {@code expiresAt} on its <em>own</em> clock
 * ({@code ANDROID_DEVICE_FLOW_SPEC} §9.3, {@code REMOTE_CONTROL_CONTRACT} §7 rule 4), so a dead,
 * wedged, or partitioned backend can never leave a box streaming. All this job does is keep the
 * operator console and the "one live session per device" rule honest by clearing rows nobody
 * ever acked. If it stops running, sessions linger in the table — no box keeps streaming.
 */
@Component
public class RemoteSessionExpirationJob {

    private static final Logger log = LoggerFactory.getLogger(RemoteSessionExpirationJob.class);

    private final RemoteSessionService remoteSessionService;

    public RemoteSessionExpirationJob(RemoteSessionService remoteSessionService) {
        this.remoteSessionService = remoteSessionService;
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    public void expireStaleSessions() {
        int expired = remoteSessionService.expireStale();
        if (expired > 0) {
            log.info("Expired {} stale remote session(s)", expired);
        }
    }
}
