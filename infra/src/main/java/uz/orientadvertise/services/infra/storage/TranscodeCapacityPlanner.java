package uz.orientadvertise.services.infra.storage;

/**
 * Decides how many ffmpeg encodes may run at once <b>on the host the application actually finds
 * itself on</b>, rather than on a number tuned to one particular box.
 *
 * <p>Why this is not a constant: an encode's peak RSS is a property of x264's lookahead buffers, so
 * it is per-frame, not per-file — a 3 MB clip and a 50 MB clip both need roughly the same working
 * set (measured: ~218 MiB at {@code -preset veryfast}, ~438 MiB at {@code -preset medium}). Because
 * {@code ProcessBuilder.start()} makes ffmpeg a child of the JVM, that RSS is charged to the same
 * cgroup as the JVM. On a 700 MiB container two concurrent encodes are a guaranteed OOM kill, and
 * {@code oom_badness} picks the JVM over ffmpeg — you lose the backend, not the encode. On a
 * 16 GB / 8-core host the same arithmetic says seven encodes are fine. Hard-coding either number
 * caps the other, and lifting the cap would need a code change and a rebuild.
 *
 * <p>The planner is a pure function of numbers so it can be unit-tested for every host shape;
 * probing the actual host is {@link HostResources}' job.
 */
public final class TranscodeCapacityPlanner {

    private TranscodeCapacityPlanner() {
    }

    /**
     * @param configured   explicit override; {@code > 0} wins outright (still clamped to
     *                     {@code [1, maxCap]}), {@code <= 0} means "plan it for me"
     * @param cpus         CPUs visible to the JVM. {@code Runtime.availableProcessors()} is
     *                     container-aware (it honours the cgroup CPU quota), so this is the
     *                     scheduler's real width, not the physical host's
     * @param containerMemoryBytes total memory the process is allowed, {@code <= 0} if unknown
     * @param jvmMaxHeapBytes      {@code -Xmx} / {@code MaxRAMPercentage} result — memory the JVM
     *                             may claim and therefore memory ffmpeg cannot have
     * @param jobMemoryMb  budget for one encode; should exceed the measured peak for the configured
     *                     preset with headroom
     * @param reserveMb    non-heap JVM overhead to keep off the table (metaspace, code cache,
     *                     thread stacks, direct buffers, the OS page cache the JVM needs)
     * @param maxCap       upper bound so an enormous host does not spawn an absurd number of encodes
     * @return concurrency in {@code [1, maxCap]} — never 0, because a pipeline that cannot run is
     *         worse than one that runs slowly
     */
    public static int plan(int configured,
                           int cpus,
                           long containerMemoryBytes,
                           long jvmMaxHeapBytes,
                           int jobMemoryMb,
                           int reserveMb,
                           int maxCap) {
        int cap = Math.max(1, maxCap);
        if (configured > 0) {
            return Math.min(configured, cap);
        }

        // Leave one CPU for the web tier and the JVM's own threads; a single-CPU host still gets 1.
        int byCpu = Math.max(1, cpus - 1);

        // Unknown memory (no cgroup, no OS bean) must not silently license unlimited concurrency —
        // fall back to the CPU budget alone, which is the conservative of the two on small hosts.
        int byMemory = cap;
        if (containerMemoryBytes > 0 && jobMemoryMb > 0) {
            long available = containerMemoryBytes
                    - Math.max(0, jvmMaxHeapBytes)
                    - mib(Math.max(0, reserveMb));
            byMemory = (int) Math.max(1, available / mib(jobMemoryMb));
        }

        return Math.max(1, Math.min(cap, Math.min(byCpu, byMemory)));
    }

    private static long mib(long megabytes) {
        return megabytes * 1024L * 1024L;
    }
}
