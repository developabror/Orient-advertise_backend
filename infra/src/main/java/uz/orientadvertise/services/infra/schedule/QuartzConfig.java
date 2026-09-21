package uz.orientadvertise.services.infra.schedule;

import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.quartz.SchedulerFactoryBeanCustomizer;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.quartz.SpringBeanJobFactory;

@Configuration
public class QuartzConfig {

    /**
     * SpringBeanJobFactory subclass that performs autowiring on Quartz job instances
     * so the job can use {@code @Autowired} fields.
     */
    public static class AutowiringJobFactory extends SpringBeanJobFactory {
        private final ApplicationContext context;

        public AutowiringJobFactory(ApplicationContext context) {
            this.context = context;
        }

        @Override
        protected Object createJobInstance(org.quartz.spi.TriggerFiredBundle bundle) throws Exception {
            var job = super.createJobInstance(bundle);
            context.getAutowireCapableBeanFactory().autowireBean(job);
            return job;
        }
    }

    @Bean
    public AutowiringJobFactory jobFactory(@Autowired ApplicationContext context) {
        return new AutowiringJobFactory(context);
    }

    @Bean
    public SchedulerFactoryBeanCustomizer schedulerFactoryBeanCustomizer(AutowiringJobFactory jobFactory) {
        return bean -> bean.setJobFactory(jobFactory);
    }

    /**
     * LOGIC-04: dayparting schedules are stored but NOT applied to playback — nothing that decides
     * what a device plays reads them — so evaluating them every minute only burned a full-table scan
     * and 1,440 INFO lines a day. Off unless {@code app.schedule.evaluation-enabled=true}, which the
     * future dayparting feature will turn on (together with {@link MissedRunCatchUp}).
     */
    @Bean
    @ConditionalOnProperty(name = "app.schedule.evaluation-enabled", havingValue = "true")
    public JobDetail scheduleEvaluationJobDetail() {
        return JobBuilder.newJob(ScheduleEvaluationJob.class)
                .withIdentity("scheduleEvaluation")
                .storeDurably()
                .build();
    }

    /**
     * Cron: every minute on the minute (second=0).
     * Format: sec min hour day-of-month month day-of-week
     */
    @Bean
    @ConditionalOnProperty(name = "app.schedule.evaluation-enabled", havingValue = "true")
    public Trigger scheduleEvaluationTrigger(
            @Qualifier("scheduleEvaluationJobDetail") JobDetail scheduleEvaluationJobDetail) {
        return TriggerBuilder.newTrigger()
                .forJob(scheduleEvaluationJobDetail)
                .withIdentity("scheduleEvaluationTrigger")
                .withSchedule(CronScheduleBuilder.cronSchedule("0 * * * * ?"))
                .build();
    }

    @Bean
    public JobDetail deviceHealthMonitorJobDetail() {
        return JobBuilder.newJob(DeviceHealthMonitorJob.class)
                .withIdentity("deviceHealthMonitor")
                .storeDurably()
                .build();
    }

    /**
     * 5-minute interval. Combined with @DisallowConcurrentExecution on the job class,
     * Quartz will skip a firing rather than overlap if a prior run is still in progress.
     */
    @Bean
    public Trigger deviceHealthMonitorTrigger(
            @Qualifier("deviceHealthMonitorJobDetail") JobDetail deviceHealthMonitorJobDetail) {
        return TriggerBuilder.newTrigger()
                .forJob(deviceHealthMonitorJobDetail)
                .withIdentity("deviceHealthMonitorTrigger")
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInMinutes(5)
                        .repeatForever())
                .startAt(java.util.Date.from(java.time.Instant.now().plusSeconds(60)))
                .build();
    }
}
