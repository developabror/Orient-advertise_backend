package uz.orientadvertise.services.infra.diagnostic;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetainedErrorBufferTest {

    @BeforeEach
    @AfterEach
    void resetBuffer() {
        RetainedErrorBuffer.resetForTests();
    }

    @Test
    void append_thenRecent_returnsNewestFirst() {
        Instant t1 = Instant.now().minusSeconds(30);
        Instant t2 = Instant.now().minusSeconds(20);
        Instant t3 = Instant.now().minusSeconds(10);
        RetainedErrorBuffer.append(t1, "WARN", "uz.x", "first", null);
        RetainedErrorBuffer.append(t2, "ERROR", "uz.y", "second", "RuntimeException: oops");
        RetainedErrorBuffer.append(t3, "ERROR", "uz.z", "third", null);

        List<RetainedErrorBuffer.RetainedError> recent =
                RetainedErrorBuffer.recent(Duration.ofMinutes(5), 10);

        assertEquals(3, recent.size());
        assertEquals("third", recent.get(0).message());   // newest first
        assertEquals("second", recent.get(1).message());
        assertEquals("first", recent.get(2).message());
    }

    @Test
    void recent_filtersByWindow() {
        // Events older than the window are excluded even if they're still in the ring.
        RetainedErrorBuffer.append(Instant.now().minusSeconds(7200), "ERROR", "uz.a",
                "ancient", null);
        RetainedErrorBuffer.append(Instant.now().minusSeconds(60), "ERROR", "uz.b",
                "fresh", null);

        List<RetainedErrorBuffer.RetainedError> recent =
                RetainedErrorBuffer.recent(Duration.ofMinutes(5), 10);

        assertEquals(1, recent.size());
        assertEquals("fresh", recent.get(0).message());
    }

    @Test
    void recent_capsAtLimit() {
        // 10 events, ask for 3 → 3 newest only.
        for (int i = 0; i < 10; i++) {
            RetainedErrorBuffer.append(Instant.now().minusSeconds(10 - i),
                    "ERROR", "uz.x", "msg-" + i, null);
        }

        List<RetainedErrorBuffer.RetainedError> recent =
                RetainedErrorBuffer.recent(Duration.ofMinutes(5), 3);

        assertEquals(3, recent.size());
        assertEquals("msg-9", recent.get(0).message());
        assertEquals("msg-8", recent.get(1).message());
        assertEquals("msg-7", recent.get(2).message());
    }

    @Test
    void capacity_evictsOldestFirst() {
        // Fill past capacity; oldest events must be evicted first.
        for (int i = 0; i < RetainedErrorBuffer.CAPACITY + 20; i++) {
            RetainedErrorBuffer.append(Instant.now(), "ERROR", "uz.x", "msg-" + i, null);
        }

        assertEquals(RetainedErrorBuffer.CAPACITY, RetainedErrorBuffer.size(),
                "size capped at CAPACITY");

        // The 20 oldest were evicted — the recent set won't include them.
        List<RetainedErrorBuffer.RetainedError> recent =
                RetainedErrorBuffer.recent(Duration.ofMinutes(5), RetainedErrorBuffer.CAPACITY + 1);
        assertEquals(RetainedErrorBuffer.CAPACITY, recent.size());
        // The newest is the very last one appended.
        assertEquals("msg-" + (RetainedErrorBuffer.CAPACITY + 19), recent.get(0).message());
    }

    @Test
    void recent_emptyOrNonsenseInputs_returnsEmpty() {
        // Defensive guards.
        assertEquals(0, RetainedErrorBuffer.recent(null, 5).size());
        assertEquals(0, RetainedErrorBuffer.recent(Duration.ofMinutes(5), 0).size());
        assertEquals(0, RetainedErrorBuffer.recent(Duration.ofMinutes(5), -1).size());
    }

    @Test
    void recent_emptyBuffer_returnsEmpty() {
        // Quiet service is the success case — no errors recorded means no rows.
        assertEquals(0, RetainedErrorBuffer.recent(Duration.ofMinutes(5), 10).size());
    }

    @Test
    void append_nullsAreNormalized() {
        // Defensive: a misbehaving caller passing nulls must not produce a buffer entry
        // that crashes downstream readers.
        RetainedErrorBuffer.append(null, null, null, null, null);
        var entries = RetainedErrorBuffer.recent(Duration.ofMinutes(5), 5);
        assertEquals(1, entries.size());
        var e = entries.get(0);
        assertNotNull(e.timestamp());        // defaulted to now
        assertEquals("ERROR", e.level());    // defaulted
        assertEquals("?", e.logger());
        assertEquals("", e.message());
        assertNull(e.exception());           // exception is allowed to be null
    }

    @Test
    void append_neverThrows_evenOnConcurrentInteraction() throws Exception {
        // The buffer is on the appender's hot path; throws here would poison logging.
        // Stress-write from many threads while a reader walks the snapshot — must stay
        // self-consistent.
        int writers = 8;
        int perWriter = 200;
        var done = new CountDownLatch(writers);
        var pool = Executors.newFixedThreadPool(writers);
        for (int w = 0; w < writers; w++) {
            final int wi = w;
            pool.submit(() -> {
                try {
                    for (int i = 0; i < perWriter; i++) {
                        RetainedErrorBuffer.append(Instant.now(), "ERROR",
                                "uz.x.W" + wi, "msg-" + i, null);
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        // Read while writes are flying.
        for (int i = 0; i < 50; i++) {
            RetainedErrorBuffer.recent(Duration.ofMinutes(5), 50);
        }
        assertTrue(done.await(5, TimeUnit.SECONDS));
        pool.shutdownNow();

        // Capacity always held.
        assertTrue(RetainedErrorBuffer.size() <= RetainedErrorBuffer.CAPACITY);
    }

    @Test
    void size_reflectsCurrentBuffer() {
        assertEquals(0, RetainedErrorBuffer.size());
        RetainedErrorBuffer.append(Instant.now(), "ERROR", "uz.x", "a", null);
        RetainedErrorBuffer.append(Instant.now(), "ERROR", "uz.x", "b", null);
        assertEquals(2, RetainedErrorBuffer.size());
    }
}
