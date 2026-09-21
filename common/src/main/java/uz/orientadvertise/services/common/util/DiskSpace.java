package uz.orientadvertise.services.common.util;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * A single free-space reading for one filesystem, plus the human formatting both health surfaces
 * render.
 *
 * <p>Lives in {@code common} because two modules must agree on the same number: the Telegram
 * {@code /health} command (infra) and the {@code /api/health} disk component (service). They read
 * the same {@code app.health.data-volume-path}; if they computed the percentage differently, an
 * operator comparing the two would be chasing a discrepancy that does not exist.
 *
 * <p>Why this matters on subzero specifically: <b>one</b> 8.1 G volume carries the container
 * overlay (so every transcode scratch file), the MinIO data volume, the Postgres data directory
 * <i>and</i> {@code /swap.img}. At 96% full, a single failure mode takes all four down together —
 * so the free-space number is not a nicety, it is the leading indicator for the whole box.
 */
public record DiskSpace(String path, long totalBytes, long usableBytes) {

    /**
     * @param path any path on the filesystem of interest; the reading covers its whole
     *             {@link FileStore}, not the subtree
     * @throws IOException if the path cannot be resolved to a filesystem
     */
    public static DiskSpace probe(String path) throws IOException {
        String resolved = path == null || path.isBlank() ? "." : path;
        FileStore store = Files.getFileStore(Paths.get(resolved));
        return new DiskSpace(resolved, store.getTotalSpace(), store.getUsableSpace());
    }

    /**
     * Free space as a whole percentage, floored. Guards against a zero total (some virtual
     * filesystems report one) rather than dividing by zero.
     */
    public int freePercent() {
        return (int) ((usableBytes * 100) / Math.max(1, totalBytes));
    }

    /** Operator-facing one-liner: {@code free=386.0 MB / total=8.1 GB (4% free, path=/)}. */
    public String describe() {
        return "free=%s / total=%s (%d%% free, path=%s)"
                .formatted(humanBytes(usableBytes), humanBytes(totalBytes), freePercent(), path);
    }

    /** Byte count at the largest scale that keeps it readable. Shared so both surfaces render alike. */
    public static String humanBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return "%.1f KB".formatted(kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return "%.1f MB".formatted(mb);
        double gb = mb / 1024.0;
        if (gb < 1024) return "%.1f GB".formatted(gb);
        return "%.1f TB".formatted(gb / 1024.0);
    }
}
