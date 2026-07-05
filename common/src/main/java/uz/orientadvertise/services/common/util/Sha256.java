package uz.orientadvertise.services.common.util;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import uz.orientadvertise.services.common.exception.IllegalConfigurationException;

/**
 * Streaming SHA-256 helper. Computes the digest while counting bytes in a single pass over a
 * bounded buffer, so it stays O(1) in memory even for large video objects — it never buffers
 * the whole stream (unlike {@code InputStream.readAllBytes()}).
 *
 * <p>Used to record the integrity {@code checksum} (SHA-256 hex) and the true byte size of a
 * processed content object: the transcoder hashes the freshly-produced MP4, and the legacy
 * reconciler hashes the object streamed back from storage — both via {@link #of(InputStream)},
 * which yields the hex and the byte count together.
 */
public final class Sha256 {

    private Sha256() {
    }

    /** SHA-256 hex digest (64 lowercase hex chars) plus the total number of bytes hashed. */
    public record Result(String hex, long bytes) {
    }

    /**
     * Streams {@code in} to completion, returning its lowercase SHA-256 hex and the number of
     * bytes read. The stream is always closed.
     */
    public static Result of(InputStream in) throws IOException {
        MessageDigest md = newDigest();
        long total = 0;
        byte[] buf = new byte[8192];
        try (in) {
            int read;
            while ((read = in.read(buf)) != -1) {
                md.update(buf, 0, read);
                total += read;
            }
        }
        return new Result(HexFormat.of().formatHex(md.digest()), total);
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in the JDK; if it's missing the JVM is broken (a config fault).
            throw new IllegalConfigurationException("SHA-256 unavailable", e);
        }
    }
}
