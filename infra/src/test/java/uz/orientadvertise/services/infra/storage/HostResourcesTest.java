package uz.orientadvertise.services.infra.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The probe is environment-dependent by nature (cgroup v2 → cgroup v1 → OS bean), so what is worth
 * pinning is that it degrades cleanly on <b>any</b> of them: it must never throw and never return a
 * value the planner would misread. A hard number here would only assert what the CI runner happens
 * to be — the arithmetic itself is covered by {@link TranscodeCapacityPlannerTest}.
 */
class HostResourcesTest {

    @Test
    void availableCpus_isAtLeastOne() {
        assertTrue(HostResources.availableCpus() >= 1);
    }

    @Test
    void maxHeapBytes_isPositiveAndNotTheUnlimitedSentinel() {
        long heap = HostResources.maxHeapBytes();
        assertTrue(heap > 0, "a running JVM always has a heap ceiling");
        assertTrue(heap < Long.MAX_VALUE, "Long.MAX_VALUE must be normalised to 'unknown', not passed on");
    }

    @Test
    void containerMemoryLimit_isNeverNegative_andNeverAnUnlimitedSentinel() {
        // 0 means "unknown", which the planner treats as "fall back to the CPU budget". What must
        // never come back is a negative number or cgroup v1's huge no-limit sentinel, either of
        // which would make the memory budget nonsense.
        long limit = HostResources.containerMemoryLimitBytes();
        assertTrue(limit >= 0, "unknown must be 0, never negative: " + limit);
        assertTrue(limit < Long.MAX_VALUE / 2, "an unlimited cgroup must read as unknown: " + limit);
    }

    @Test
    void probing_isRepeatableAndNeverThrows() {
        // Called once at startup, but a throw here would take the whole context down rather than
        // degrading to a conservative pool width.
        for (int i = 0; i < 3; i++) {
            HostResources.containerMemoryLimitBytes();
            HostResources.availableCpus();
            HostResources.maxHeapBytes();
        }
    }

    @Test
    void theProbeFeedsThePlannerToAUsableWidth() {
        // End-to-end on whatever host this runs on: probe → plan → a width that can actually work.
        int planned = TranscodeCapacityPlanner.plan(0,
                HostResources.availableCpus(),
                HostResources.containerMemoryLimitBytes(),
                HostResources.maxHeapBytes(),
                320, 256, 8);
        assertTrue(planned >= 1 && planned <= 8, "planned width out of range: " + planned);
    }
}
