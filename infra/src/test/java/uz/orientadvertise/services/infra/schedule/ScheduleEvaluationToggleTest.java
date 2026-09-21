package uz.orientadvertise.services.infra.schedule;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LOGIC-04: schedules are stored but not applied to playback, so their per-minute evaluation job and
 * the startup catch-up must not exist unless explicitly enabled for the future dayparting feature.
 */
class ScheduleEvaluationToggleTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(QuartzConfig.class, MissedRunCatchUp.class);

    @Test
    void byDefault_noEvaluationJobTriggerOrCatchUp() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean("scheduleEvaluationJobDetail");
            assertThat(context).doesNotHaveBean("scheduleEvaluationTrigger");
            assertThat(context).doesNotHaveBean(MissedRunCatchUp.class);
            // The device health monitor is unaffected.
            assertThat(context).hasBean("deviceHealthMonitorTrigger");
        });
    }

    @Test
    void whenEnabled_theJobTriggerAndCatchUpAreRegistered() {
        runner.withPropertyValues("app.schedule.evaluation-enabled=true").run(context -> {
            assertThat(context).hasBean("scheduleEvaluationJobDetail");
            assertThat(context).hasBean("scheduleEvaluationTrigger");
            assertThat(context).hasSingleBean(MissedRunCatchUp.class);
        });
    }
}
