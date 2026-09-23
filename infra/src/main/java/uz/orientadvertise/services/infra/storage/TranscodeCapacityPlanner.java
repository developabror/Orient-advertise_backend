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
     * @return concurrency in {@code [1, maxCap]}, or <b>0</b> when the memory budget is negative —
     *         the heap plus the reserve already exceed the container, so an encode has nothing to
     *         run in (VG-17). It used to floor at 1 even then, on the reasoning that a slow pipeline
     *         beats a stopped one; but the encode is a CHILD of the JVM, charged to the same cgroup,
     *         and {@code oom_badness} kills the parent, so starting it costs the whole backend.
     *         Refusing is visible (ERROR at startup, health DOWN, uploads stay UPLOADED); an OOM
     *         kill looks like a random restart. A merely TIGHT budget still returns 1 — see the
     *         body. An explicit {@code app.video.transcode.concurrency} overrides all of this.
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
            // VG-17. Two different situations used to collapse into "1":
            //   available <= 0  — the heap plus the reserve already exceed the container, so an
            //                     encode has literally nothing to run in. Starting one puts an
            //                     ffmpeg child in the JVM's cgroup and oom_badness kills the JVM:
            //                     the backend dies, not the encode. Refuse, loudly (0).
            //   0 < available < one job — tight, not impossible. The budget subtracts the WHOLE
            //                     max heap, which is rarely all resident, so today's 700 MiB
            //                     production box lands here and does encode successfully at
            //                     -preset veryfast (~218 MiB peak). Allow one, and let the
            //                     executor warn that there is no headroom.
            byMemory = available <= 0 ? 0 : (int) Math.max(1, available / mib(jobMemoryMb));
        }

        int planned = Math.min(cap, Math.min(byCpu, byMemory));
        return Math.max(0, planned);
    }

    /**
     * True when one encode is allowed but the arithmetic leaves it no headroom — the caller should
     * say so at startup, because this is the shape that OOM-kills as soon as the preset gets
     * heavier or the heap grows.
     */
    public static boolean isTightOnMemory(long containerMemoryBytes, long jvmMaxHeapBytes,
                                          int jobMemoryMb, int reserveMb) {
        if (containerMemoryBytes <= 0 || jobMemoryMb <= 0) {
            return false;   // unknown memory is not a claim about headroom
        }
        long available = containerMemoryBytes - Math.max(0, jvmMaxHeapBytes) - mib(Math.max(0, reserveMb));
        return available > 0 && available < mib(jobMemoryMb);
    }

    private static long mib(long megabytes) {
        return megabytes * 1024L * 1024L;
    }
}
