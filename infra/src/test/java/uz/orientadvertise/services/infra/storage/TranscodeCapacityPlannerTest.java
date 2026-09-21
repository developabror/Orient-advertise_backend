package uz.orientadvertise.services.infra.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The planner is what makes one image safe on a 700 MiB box and useful on a 16 GB one. Each case
 * below is a host shape the same artifact has to land on without a rebuild.
 */
class TranscodeCapacityPlannerTest {

    private static final int JOB_MB = 320;
    private static final int RESERVE_MB = 256;
    private static final int MAX_CAP = 8;

    private static long mib(long m) {
        return m * 1024L * 1024L;
    }

    private static int plan(int configured, int cpus, long memMib, long heapMib) {
        return TranscodeCapacityPlanner.plan(configured, cpus, mib(memMib), mib(heapMib),
                JOB_MB, RESERVE_MB, MAX_CAP);
    }

    @Test
    void todaysProductionBox_resolvesToOne() {
        // 1 vCPU, 700 MiB cgroup, 45% heap. Two concurrent encodes here are a guaranteed OOM kill,
        // and oom_badness picks the JVM (bigger) over ffmpeg — you lose the backend, not the encode.
        assertEquals(1, plan(0, 1, 700, 315));
    }

    @Test
    void resourcedHost_scalesUpWithoutACodeChange() {
        // 8 cores, 16 GB, 45% heap ≈ 7372 MiB. CPU is the binding constraint here, and one core is
        // left for the web tier. This is the whole point: same image, bigger number.
        assertEquals(7, plan(0, 8, 16384, 7372));
    }

    @Test
    void hugeHost_isClampedToMaxCap() {
        // 64 cores would otherwise plan 63 concurrent encodes. The cap exists so "adapts to the
        // host" never becomes "spawns an absurd number of ffmpeg processes".
        assertEquals(MAX_CAP, plan(0, 64, 131072, 32768));
    }

    @Test
    void memoryStarvedButManyCores_isBoundedByMemory() {
        // 16 cores but only 1 GB: CPU says 15, memory says (1024 - 512 - 256) / 320 = 0 → floor 1.
        // Trusting the CPU count alone here is exactly how you OOM a container.
        assertEquals(1, plan(0, 16, 1024, 512));
        // With 3 GB the memory budget opens up to (3072 - 1024 - 256) / 320 = 5.
        assertEquals(5, plan(0, 16, 3072, 1024));
    }

    @Test
    void unknownMemory_fallsBackToTheCpuBudget() {
        // No cgroup file and no OS bean. Unknown must not license unlimited concurrency, but it
        // also must not wedge a big host at 1 — the CPU budget is the conservative known quantity.
        assertEquals(3, TranscodeCapacityPlanner.plan(0, 4, 0, mib(512), JOB_MB, RESERVE_MB, MAX_CAP));
    }

    @Test
    void explicitConfiguration_overridesThePlanner() {
        // An operator who knows the box beats the heuristic — but never beats the hard cap.
        assertEquals(4, plan(4, 1, 700, 315));
        assertEquals(MAX_CAP, plan(99, 1, 700, 315));
    }

    @Test
    void neverReturnsZeroOrNegative_forAnyHostShape() {
        // A pipeline that cannot run at all is worse than one that runs slowly: the file would sit
        // in UPLOADED forever, which is the failure this whole release exists to remove.
        int[][] shapes = {{0, 0, 0}, {1, 1, 1}, {-1, -5, -5}, {0, 128, 4096}};
        for (int[] shape : shapes) {
            int planned = TranscodeCapacityPlanner.plan(shape[0], shape[1], mib(shape[2]), mib(shape[2]),
                    JOB_MB, RESERVE_MB, MAX_CAP);
            assertTrue(planned >= 1 && planned <= MAX_CAP,
                    "planned=" + planned + " for cpus=" + shape[1] + " mem=" + shape[2]);
        }
    }

    @Test
    void zeroMaxCap_isTreatedAsOne_ratherThanDisablingTranscoding() {
        assertEquals(1, TranscodeCapacityPlanner.plan(0, 8, mib(16384), mib(4096), JOB_MB, RESERVE_MB, 0));
    }

    @Test
    void raisingTheJobBudget_lowersConcurrency() {
        // Switching the preset from veryfast (~218 MiB) to medium (~438 MiB) means raising
        // job-memory-mb; the planner must respond by fitting fewer encodes into the same box.
        long mem = mib(4096);
        long heap = mib(1024);
        int atVeryfast = TranscodeCapacityPlanner.plan(0, 8, mem, heap, 320, RESERVE_MB, MAX_CAP);
        int atMedium = TranscodeCapacityPlanner.plan(0, 8, mem, heap, 512, RESERVE_MB, MAX_CAP);
        assertTrue(atMedium <= atVeryfast,
                "a bigger per-job budget must not increase concurrency (" + atMedium + " > " + atVeryfast + ")");
    }
}
