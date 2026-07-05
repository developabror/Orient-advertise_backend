package uz.orientadvertise.services.infra.storage;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.orientadvertise.services.domain.model.ContentAssignment;
import uz.orientadvertise.services.domain.repository.ContentAssignmentRepository;

/**
 * Sweeps DRAFT content assignments older than the configured threshold
 * (default 1 hour). Runs every 10 minutes via {@link Scheduled}.
 *
 * Drafts represent in-flight assignment creation that the operator never
 * completed via {@code /confirm}. Leaving them around forever would leak
 * resources and clutter the listing UI.
 */
@Component
public class DraftAssignmentCleaner {

    private static final Logger log = LoggerFactory.getLogger(DraftAssignmentCleaner.class);

    private final ContentAssignmentRepository assignmentRepository;

    @Value("${app.assignments.draft-ttl-minutes:60}")
    private long draftTtlMinutes;

    public DraftAssignmentCleaner(ContentAssignmentRepository assignmentRepository) {
        this.assignmentRepository = assignmentRepository;
    }

    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT5M")
    @Transactional
    public void cleanup() {
        try {
            var threshold = Instant.now().minus(Duration.ofMinutes(draftTtlMinutes));
            var expired = assignmentRepository.findExpiredDrafts(threshold);
            if (expired.isEmpty()) {
                return;
            }
            for (ContentAssignment a : expired) {
                a.softDelete();
            }
            log.info("Auto-cleaned {} expired DRAFT assignment(s) older than {}m",
                    expired.size(), draftTtlMinutes);
        } catch (Exception e) {
            log.warn("Draft cleanup failed (non-critical): {}", e.getMessage());
        }
    }
}
