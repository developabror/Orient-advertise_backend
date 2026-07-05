package uz.orientadvertise.services.api.ws;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uz.orientadvertise.services.domain.content.SyncDispatcher;

/**
 * Batches WebSocket sync notifications when an assignment change affects many
 * devices at once. Without this, confirming an assignment for a 1000-device
 * region would trigger 1000 simultaneous WebSocket sends in the same instant.
 *
 * Strategy:
 *   - Split devices into batches of {@value DEFAULT_BATCH_SIZE}
 *   - Stagger batch dispatches by {@code app.sync.batch-stagger-ms} (default 100 ms)
 *   - Single ScheduledExecutorService — the dispatcher itself never blocks the caller
 */
@Component
public class BatchedSyncDispatcher implements SyncDispatcher, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(BatchedSyncDispatcher.class);
    private static final int DEFAULT_BATCH_SIZE = 50;

    private final DeviceWebSocketHandler handler;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                var t = new Thread(r, "sync-dispatcher");
                t.setDaemon(true);
                return t;
            });

    @Value("${app.sync.batch-size:50}")
    private int batchSize;

    @Value("${app.sync.batch-stagger-ms:100}")
    private long batchStaggerMs;

    public BatchedSyncDispatcher(DeviceWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public DispatchResult dispatchSyncToDevices(Collection<Long> deviceIds, String reason) {
        if (deviceIds == null || deviceIds.isEmpty()) {
            return new DispatchResult(0, 0, 0, 0, 0);
        }

        var ids = new ArrayList<>(deviceIds);
        int effectiveBatchSize = batchSize > 0 ? batchSize : DEFAULT_BATCH_SIZE;
        int batchCount = (ids.size() + effectiveBatchSize - 1) / effectiveBatchSize;

        var aggregateSent = new java.util.concurrent.atomic.AtomicInteger();
        var aggregateSkipped = new java.util.concurrent.atomic.AtomicInteger();
        var aggregateFailed = new java.util.concurrent.atomic.AtomicInteger();

        for (int b = 0; b < batchCount; b++) {
            int from = b * effectiveBatchSize;
            int to = Math.min(from + effectiveBatchSize, ids.size());
            List<Long> batch = ids.subList(from, to);
            long delay = (long) b * batchStaggerMs;

            scheduler.schedule(() -> {
                try {
                    var msg = """
                            {"type":"SYNC_CONTENT","reason":"%s"}""".formatted(reason);
                    var pushed = handler.pushToDevices(batch, msg);
                    aggregateSent.addAndGet(pushed.sent());
                    aggregateSkipped.addAndGet(pushed.skipped());
                    aggregateFailed.addAndGet(pushed.failed());
                } catch (Exception e) {
                    log.warn("Sync batch failed: {}", e.getMessage());
                    aggregateFailed.addAndGet(batch.size());
                }
            }, delay, TimeUnit.MILLISECONDS);
        }

        log.info("Scheduled {} sync batch(es) for {} device(s) (stagger={}ms, reason={})",
                batchCount, ids.size(), batchStaggerMs, reason);

        // Returns immediately with planned counts; sent/skipped/failed are aggregated
        // asynchronously as batches fire. For monitoring, expose these via metrics in production.
        return new DispatchResult(ids.size(), batchCount,
                aggregateSent.get(), aggregateSkipped.get(), aggregateFailed.get());
    }

    @Override
    public void destroy() {
        scheduler.shutdownNow();
    }
}
