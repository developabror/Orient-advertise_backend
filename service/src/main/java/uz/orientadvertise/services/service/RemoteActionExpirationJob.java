package uz.orientadvertise.services.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically marks expired PENDING actions as EXPIRED so a device coming online
 * after the deadline (e.g. 10-minute playlist control window) doesn't pick up stale
 * commands from a much earlier operator click.
 */
@Component
public class RemoteActionExpirationJob {

    private static final Logger log = LoggerFactory.getLogger(RemoteActionExpirationJob.class);

    private final RemoteActionService remoteActionService;

    public RemoteActionExpirationJob(RemoteActionService remoteActionService) {
        this.remoteActionService = remoteActionService;
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    public void expireStaleActions() {
        int expired = remoteActionService.expireStale();
        if (expired > 0) {
            log.info("Expired {} stale remote action(s)", expired);
        }
    }
}
