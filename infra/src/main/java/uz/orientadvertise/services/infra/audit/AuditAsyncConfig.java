package uz.orientadvertise.services.infra.audit;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
public class AuditAsyncConfig {

    @Bean(name = "auditExecutor")
    public Executor auditExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("audit-");
        executor.setRejectedExecutionHandler((runnable, exec) -> {
            // If queue is full, silently drop — audit must never block the request
        });
        executor.initialize();
        return executor;
    }

    /**
     * Dedicated pool for urgent transcodes — separate from {@code auditExecutor}
     * so urgent uploads don't queue behind backlog. Smaller queue + caller-runs
     * fallback so back-pressure is handled inline.
     */
    @Bean(name = "urgentTranscodeExecutor")
    public Executor urgentTranscodeExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("urgent-transcode-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * Pool for long-running event/incident report aggregations. Separate from
     * {@code auditExecutor} (which is fire-and-forget) and {@code urgentTranscodeExecutor}
     * (CPU-bound). Reports are DB-bound and may take minutes for large facilities; the
     * {@code AbortPolicy} surfaces overflow as a job FAILED rather than a silent drop,
     * so the polling client gets a definitive answer.
     */
    @Bean(name = "reportExecutor")
    public Executor reportExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("report-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
