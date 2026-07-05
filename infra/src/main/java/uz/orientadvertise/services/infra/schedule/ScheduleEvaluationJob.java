package uz.orientadvertise.services.infra.schedule;

import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import uz.orientadvertise.services.domain.content.ScheduleEvaluator;

/**
 * Quartz job that calls {@link ScheduleEvaluator#evaluateNow()} once per minute.
 * Quartz instantiates this class via the Spring-aware job factory so {@code @Autowired}
 * dependencies are injected.
 */
public class ScheduleEvaluationJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(ScheduleEvaluationJob.class);

    @Autowired
    private ScheduleEvaluator evaluator;

    @Override
    public void execute(JobExecutionContext context) {
        try {
            var result = evaluator.evaluateNow();
            log.debug("Quartz tick: total={}, active={}, errors={}",
                    result.totalSchedules(), result.activeNow(), result.errors());
        } catch (Exception e) {
            log.warn("Schedule evaluation failed (non-critical): {}", e.getMessage());
        }
    }
}
