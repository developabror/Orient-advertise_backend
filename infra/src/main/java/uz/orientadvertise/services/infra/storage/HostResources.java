package uz.orientadvertise.services.infra.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads what the host is actually willing to give this process. Kept separate from
 * {@link TranscodeCapacityPlanner} so the planning arithmetic stays a pure, fully testable function
 * and only the (unavoidably environment-dependent) probing lives here.
 *
 * <p>Memory is resolved in descending order of truthfulness:
 * <ol>
 *   <li><b>cgroup v2</b> {@code /sys/fs/cgroup/memory.max} — what {@code docker run -m} / a compose
 *       {@code mem_limit} actually enforces, and the number the OOM killer uses.</li>
 *   <li><b>cgroup v1</b> {@code /sys/fs/cgroup/memory/memory.limit_in_bytes} — same, older kernels.
 *       An unlimited cgroup reports a sentinel near {@code Long.MAX_VALUE}, which is rejected.</li>
 *   <li><b>OS total memory</b> via the JMX bean — correct when running unconstrained on a VM.</li>
 * </ol>
 * Every step degrades quietly: an unreadable file or a JVM without the {@code com.sun} bean returns
 * {@code 0} ("unknown"), which the planner treats as "fall back to the CPU budget" rather than as a
 * licence for unbounded concurrency.
 */
public final class HostResources {

    private static final Logger log = LoggerFactory.getLogger(HostResources.class);

    static final Path CGROUP_V2_MEMORY_MAX = Path.of("/sys/fs/cgroup/memory.max");
    static final Path CGROUP_V1_MEMORY_LIMIT = Path.of("/sys/fs/cgroup/memory/memory.limit_in_bytes");

    /**
     * cgroup v1 reports "no limit" as a huge sentinel (typically {@code Long.MAX_VALUE} rounded down
     * to the page size). Anything at or above this is treated as unlimited, not as a real budget.
     */
    private static final long UNLIMITED_SENTINEL = Long.MAX_VALUE / 2;

    private HostResources() {
    }

    /** CPUs visible to this JVM. Container-aware: honours the cgroup CPU quota, not the host's core count. */
    public static int availableCpus() {
        return Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    /** Maximum heap the JVM may claim — memory ffmpeg therefore cannot have. */
    public static long maxHeapBytes() {
        long max = Runtime.getRuntime().maxMemory();
        return max == Long.MAX_VALUE ? 0L : max;
    }

    /** @return the enforced memory budget in bytes, or {@code 0} when it cannot be determined. */
    public static long containerMemoryLimitBytes() {
        long v2 = readCgroupLimit(CGROUP_V2_MEMORY_MAX);
        if (v2 > 0) return v2;

        long v1 = readCgroupLimit(CGROUP_V1_MEMORY_LIMIT);
        if (v1 > 0) return v1;

        return osTotalMemoryBytes();
    }

    private static long readCgroupLimit(Path path) {
        try {
            if (!Files.isReadable(path)) return 0L;
            String raw = Files.readString(path, StandardCharsets.UTF_8).trim();
            // cgroup v2 writes the literal string "max" for an unconstrained cgroup.
            if (raw.isEmpty() || "max".equalsIgnoreCase(raw)) return 0L;
            long value = Long.parseLong(raw);
            return (value <= 0 || value >= UNLIMITED_SENTINEL) ? 0L : value;
        } catch (IOException | RuntimeException e) {
            log.debug("Could not read cgroup memory limit from {}: {}", path, e.toString());
            return 0L;
        }
    }

    private static long osTotalMemoryBytes() {
        try {
            var bean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof com.sun.management.OperatingSystemMXBean sun) {
                long total = sun.getTotalMemorySize();
                return total > 0 ? total : 0L;
            }
        } catch (RuntimeException | LinkageError e) {
            log.debug("OperatingSystemMXBean unavailable for memory sizing: {}", e.toString());
        }
        return 0L;
    }
}
