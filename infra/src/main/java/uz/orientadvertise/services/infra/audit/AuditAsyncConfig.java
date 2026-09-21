package uz.orientadvertise.services.infra.audit;

import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Async pools for fire-and-forget and long-running background work.
 *
 * <h2>Two traps fixed in v1.0.132 — do not reintroduce them</h2>
 *
 * <p><b>1. {@code maxPoolSize} was dead.</b> A {@link ThreadPoolTaskExecutor} only grows past
 * {@code corePoolSize} once {@code queue.offer()} <em>fails</em>. With a 500-deep queue the audit
 * pool was pinned at 2 threads and {@code maxPoolSize=5} was unreachable below ~505 tasks in
 * flight — a limit that reads like a safety valve but is really a cliff. Every pool here now sets
 * {@code core == max} with {@code allowCoreThreadTimeOut(true)}, so the width is exactly what it
 * says and idle threads still retire. Sizes are bound from properties so a bigger host can widen
 * them without a rebuild.
 *
 * <p><b>2. Queued work was discarded on shutdown.</b> Without
 * {@code waitForTasksToCompleteOnShutdown} a {@code docker compose up -d} threw away whatever was
 * queued. For audit rows that is lost history; for the transcodes that used to share this pool it
 * was a permanently stuck content file.
 *
 * <p><b>Transcodes no longer run here.</b> They own {@code TranscodeExecutor} (infra.storage), whose
 * width is planned from the host's CPU and memory budget. Sharing this pool meant a transcode could
 * be silently dropped by the audit rejection policy — the exact failure mode of the v1.0.132
 * incident, with zero diagnostic trail. The former {@code urgentTranscodeExecutor} is gone for the
 * same reason: a second transcode pool makes the real concurrency ceiling the sum of two widths,
 * which is how a memory budget gets blown, and its {@code CallerRunsPolicy} ran the entire pipeline
 * inline on the Tomcat request thread on overflow.
 */
@Configuration
@EnableAsync
public class AuditAsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(AuditAsyncConfig.class);

    /**
     * Fire-and-forget pool for audit rows, entity-change records, device events and outbound
     * notification sends. Rejection still drops the task — audit must never block a request, and a
     * lost audit row is not a lost business fact — but it is now <em>logged</em> rather than
     * swallowed by an empty lambda, so a saturated pool is diagnosable.
     */
    @Bean(name = "auditExecutor")
    public Executor auditExecutor(
            @Value("${app.async.audit.size:2}") int size,
            @Value("${app.async.audit.queue-capacity:500}") int queueCapacity) {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, size));
        executor.setMaxPoolSize(Math.max(1, size));
        executor.setQueueCapacity(Math.max(1, queueCapacity));
        executor.setAllowCoreThreadTimeOut(true);
        executor.setThreadNamePrefix("audit-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(15);
        executor.setRejectedExecutionHandler((runnable, exec) ->
                log.warn("Audit task dropped — pool saturated (queue={}); audit must never block a request",
                        exec.getQueue().size()));
        executor.initialize();
        return executor;
    }

    /**
     * Pool for long-running event/incident report aggregations. Separate from {@code auditExecutor}
     * (which is fire-and-forget) because reports are DB-bound and may take minutes for large
     * facilities; the {@code AbortPolicy} surfaces overflow as a job FAILED rather than a silent
     * drop, so the polling client gets a definitive answer.
     */
    @Bean(name = "reportExecutor")
    public Executor reportExecutor(
            @Value("${app.async.report.size:2}") int size,
            @Value("${app.async.report.queue-capacity:20}") int queueCapacity) {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, size));
        executor.setMaxPoolSize(Math.max(1, size));
        executor.setQueueCapacity(Math.max(1, queueCapacity));
        executor.setAllowCoreThreadTimeOut(true);
        executor.setThreadNamePrefix("report-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
