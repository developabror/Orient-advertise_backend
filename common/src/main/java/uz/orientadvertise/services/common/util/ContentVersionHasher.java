package uz.orientadvertise.services.common.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Deterministic content-version hasher.
 *
 * Inputs: assignment ID, version_number bump counter, playlist ID, ordered list
 * of (contentFileId, processedStorageKey, effectiveDurationSeconds) tuples in playlist order.
 *
 * Output: SHA-256 hex digest. Same inputs → same hash. Any change in file order,
 * file replacement, version bump, or a per-item dwell/duration edit produces a
 * different hash.
 *
 * Including {@code versionNumber} guarantees a new hash even when a rollback
 * recreates the same file list — the counter monotonically increases. Including
 * {@code effectiveDurationSeconds} keeps the synchronized-playback schedule stable
 * per {@code contentVersion}: a pure dwell-time edit now yields a new version, so
 * offline/reconnecting devices re-sync and re-anchor to the new slot timings.
 */
public final class ContentVersionHasher {

    public record FileRef(Long contentFileId, String processedStorageKey, Integer effectiveDurationSeconds) {}

    private ContentVersionHasher() {
    }

    public static String hash(Long assignmentId, int versionNumber, Long playlistId, List<FileRef> orderedFiles) {
        var buf = new StringBuilder();
        buf.append("a=").append(assignmentId).append(';');
        buf.append("v=").append(versionNumber).append(';');
        buf.append("p=").append(playlistId).append(';');
        if (orderedFiles != null) {
            for (int i = 0; i < orderedFiles.size(); i++) {
                var ref = orderedFiles.get(i);
                buf.append('[').append(i).append(']')
                   .append(ref.contentFileId()).append(':')
                   .append(ref.processedStorageKey() == null ? "" : ref.processedStorageKey())
                   .append("#d=").append(ref.effectiveDurationSeconds() == null ? "" : ref.effectiveDurationSeconds())
                   .append(';');
            }
        }
        return sha256Hex(buf.toString());
    }

    private static String sha256Hex(String input) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            var digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
