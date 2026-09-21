package uz.orientadvertise.services.infra.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import uz.orientadvertise.services.domain.content.ScheduleEvaluator;

/**
 * If the application is restarted between cron ticks (e.g. deploy, crash recovery),
 * the per-minute Quartz job effectively misses one or more runs. This listener
 * triggers a single immediate evaluation as soon as the context is ready, so any
 * schedules that became active during downtime are picked up without waiting up
 * to a minute for the next cron fire.
 */
@Component
@ConditionalOnProperty(name = "app.schedule.evaluation-enabled", havingValue = "true") // LOGIC-04, see QuartzConfig
public class MissedRunCatchUp {

    private static final Logger log = LoggerFactory.getLogger(MissedRunCatchUp.class);

    private final ObjectProvider<ScheduleEvaluator> evaluatorProvider;

    public MissedRunCatchUp(ObjectProvider<ScheduleEvaluator> evaluatorProvider) {
        this.evaluatorProvider = evaluatorProvider;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void runOnce() {
        var evaluator = evaluatorProvider.getIfAvailable();
        if (evaluator == null) {
            log.debug("No ScheduleEvaluator bean — catch-up skipped (likely test context)");
            return;
        }
        try {
            log.info("Running schedule catch-up after startup (covers any missed cron runs)");
            var result = evaluator.evaluateNow();
            log.info("Catch-up complete: {}", result);
        } catch (Exception e) {
            log.warn("Schedule catch-up failed (non-critical): {}", e.getMessage());
        }
    }
}
