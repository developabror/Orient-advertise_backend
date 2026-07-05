package uz.orientadvertise.services.service;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.orientadvertise.services.common.exception.RateLimitExceededException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExportConcurrencyLimiterTest {

    private ExportConcurrencyLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new ExportConcurrencyLimiter();
    }

    @Test
    void firstAcquire_succeeds() {
        try (var slot = limiter.acquire("alice")) {
            assertEquals(1, limiter.activeCount("alice"));
        }
        assertEquals(0, limiter.activeCount("alice"));
    }

    @Test
    void twoConcurrentAcquires_succeed() {
        var s1 = limiter.acquire("alice");
        var s2 = limiter.acquire("alice");
        assertEquals(2, limiter.activeCount("alice"));
        s1.close();
        s2.close();
        assertEquals(0, limiter.activeCount("alice"));
    }

    @Test
    void thirdConcurrentAcquire_throws429() {
        var s1 = limiter.acquire("alice");
        var s2 = limiter.acquire("alice");
        try {
            assertThrows(RateLimitExceededException.class, () -> limiter.acquire("alice"));
        } finally {
            s1.close();
            s2.close();
        }
    }

    @Test
    void release_freesSlot_allowsNewAcquire() {
        var s1 = limiter.acquire("alice");
        var s2 = limiter.acquire("alice");
        s1.close();
        var s3 = limiter.acquire("alice");
        assertEquals(2, limiter.activeCount("alice"));
        s2.close();
        s3.close();
    }

    @Test
    void doubleClose_isIdempotent() {
        var slot = limiter.acquire("alice");
        slot.close();
        slot.close();
        assertEquals(0, limiter.activeCount("alice"));
    }

    @Test
    void perUserScoped_otherUserNotAffected() {
        var a1 = limiter.acquire("alice");
        var a2 = limiter.acquire("alice");
        // alice is at the limit; bob still gets 2 of his own.
        var b1 = limiter.acquire("bob");
        var b2 = limiter.acquire("bob");
        assertEquals(2, limiter.activeCount("alice"));
        assertEquals(2, limiter.activeCount("bob"));
        a1.close(); a2.close(); b1.close(); b2.close();
    }

    @Test
    void blankUsername_throws400() {
        assertThrows(IllegalArgumentException.class, () -> limiter.acquire(""));
        assertThrows(IllegalArgumentException.class, () -> limiter.acquire(null));
    }

    @Test
    void concurrentAcquires_neverExceedLimit() throws InterruptedException {
        // Race a swarm of 32 threads all trying to acquire under the same username.
        // Bounded CAS in acquire() must keep the active count ≤ 2 at all times.
        int threads = 32;
        var ready = new CountDownLatch(threads);
        var go = new CountDownLatch(1);
        var done = new CountDownLatch(threads);
        var rejected = new AtomicInteger();
        var accepted = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                ready.countDown();
                try {
                    go.await();
                    try (var slot = limiter.acquire("race")) {
                        accepted.incrementAndGet();
                        // Hold the slot briefly so the next thread sees the live count.
                        Thread.sleep(5);
                    }
                } catch (RateLimitExceededException e) {
                    rejected.incrementAndGet();
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            }).start();
        }

        ready.await();
        go.countDown();
        done.await();

        assertEquals(threads, accepted.get() + rejected.get());
        // After all threads return, the count must be back to zero.
        assertEquals(0, limiter.activeCount("race"));
    }
}
