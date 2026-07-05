package uz.orientadvertise.services.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;
import uz.orientadvertise.services.common.exception.RateLimitExceededException;

/**
 * Per-user concurrency limit for export jobs. Excel export reads + serializes thousands
 * of rows per request — without a cap, a single user with a flaky download client could
 * pin every worker thread by retrying mid-stream. Two simultaneous exports per user
 * leaves headroom while still letting them queue a second one for a different sheet.
 *
 * <p>The acquire/release pattern returns an {@link AutoCloseable} so callers can use
 * try-with-resources and not leak the slot on exception:
 * <pre>{@code
 *   try (var slot = limiter.acquire(username)) {
 *       writeExcel(out);
 *   }
 * }</pre>
 *
 * <p>State is in-memory ({@link ConcurrentHashMap}) — single-instance deploy is the
 * baseline. The map entry is removed when the count drops back to zero so usernames
 * don't accumulate forever.
 */
@Component
public class ExportConcurrencyLimiter {

    public static final int MAX_CONCURRENT_PER_USER = 2;

    private final Map<String, AtomicInteger> active = new ConcurrentHashMap<>();

    /**
     * Reserve a slot for {@code username}. Throws if the user is already at the limit.
     * The returned handle decrements on close — always pair with try-with-resources.
     */
    public Slot acquire(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username is required");
        }
        AtomicInteger counter = active.computeIfAbsent(username, k -> new AtomicInteger());
        // Bounded CAS: increment only if under the cap. Avoids the race where two
        // requests both see count=1, both increment to 2, and we end up at 3.
        while (true) {
            int current = counter.get();
            if (current >= MAX_CONCURRENT_PER_USER) {
                throw new RateLimitExceededException(
                        "Export limit reached: max " + MAX_CONCURRENT_PER_USER
                                + " concurrent exports per user");
            }
            if (counter.compareAndSet(current, current + 1)) {
                return new Slot(this, username);
            }
        }
    }

    int activeCount(String username) {
        var counter = active.get(username);
        return counter == null ? 0 : counter.get();
    }

    void releaseInternal(String username) {
        active.computeIfPresent(username, (k, counter) -> {
            int after = counter.decrementAndGet();
            return after <= 0 ? null : counter;
        });
    }

    public static class Slot implements AutoCloseable {
        private final ExportConcurrencyLimiter limiter;
        private final String username;
        private boolean released;

        Slot(ExportConcurrencyLimiter limiter, String username) {
            this.limiter = limiter;
            this.username = username;
        }

        @Override
        public void close() {
            if (released) return;
            released = true;
            limiter.releaseInternal(username);
        }
    }
}
