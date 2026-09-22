package uz.orientadvertise.services.infra.storage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uz.orientadvertise.services.domain.content.VideoInspector;

@Component
public class FFprobeVideoInspector implements VideoInspector {

    private static final Logger log = LoggerFactory.getLogger(FFprobeVideoInspector.class);

    @Value("${app.video.ffprobe-path:ffprobe}")
    private String ffprobePath;

    @Value("${app.video.inspector-enabled:true}")
    private boolean enabled;

    @Value("${app.video.ffprobe-timeout:PT30S}")
    private Duration timeout = Duration.ofSeconds(30);

    /** Output readers. Virtual threads: they only ever block on a pipe. */
    private static final ExecutorService DRAINS = Executors.newVirtualThreadPerTaskExecutor();

    /** Retained per stream; the rest is read and discarded so the pipe never fills. */
    private static final int MAX_CAPTURE_CHARS = 16_384;

    record ProbeOutput(int exitCode, String stdout, String stderr) {}

    /**
     * Run ffprobe with both output streams read on their own threads, so the timeout is real.
     *
     * <p>This used to read stdout to the end, then stderr, then {@code waitFor(30s)}. Reading to the
     * end blocks for as long as ffprobe runs, so the 30-second timeout could never fire and a hung
     * ffprobe held the transcode worker forever; and a corrupt file that makes ffprobe write more
     * than a pipe's worth of errors deadlocked it, because stderr was only read after stdout closed.
     * On a single-width pool one such upload stalled every transcode behind it.
     *
     * @return the output, or {@code null} if ffprobe did not finish within the timeout (it is killed)
     */
    ProbeOutput runProbe(List<String> command) throws IOException, InterruptedException {
        var process = new ProcessBuilder(command).start();
        Future<String> stdout = DRAINS.submit(() -> readCapped(process.getInputStream()));
        Future<String> stderr = DRAINS.submit(() -> readCapped(process.getErrorStream()));
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            return null;
        }
        try {
            // The process has exited, so both pipes are at EOF; the bound only guards a leaked pipe.
            return new ProbeOutput(process.exitValue(),
                    stdout.get(5, TimeUnit.SECONDS), stderr.get(5, TimeUnit.SECONDS));
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new IOException("Could not read ffprobe output", e);
        }
    }

    private static String readCapped(InputStream in) throws IOException {
        try (var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            var sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (sb.length() < MAX_CAPTURE_CHARS) {
                    sb.append(line).append('\n');
                }
            }
            return sb.toString();
        }
    }

    @Override
    public InspectionResult inspect(InputStream data) {
        if (!enabled) {
            log.debug("FFprobe inspection disabled — accepting video as valid");
            return InspectionResult.valid();
        }

        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("video-inspect-", ".bin");
            Files.copy(data, tempFile, StandardCopyOption.REPLACE_EXISTING);
            return runFfprobe(tempFile);
        } catch (IOException e) {
            log.warn("Failed to write temp file for inspection: {}", e.getMessage());
            return InspectionResult.invalid("Could not buffer video for inspection");
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    log.warn("Failed to delete temp file {}: {}", tempFile, e.getMessage());
                }
            }
        }
    }

    private InspectionResult runFfprobe(Path file) {
        try {
            var output = runProbe(List.of(
                    ffprobePath,
                    "-v", "error",
                    "-show_entries", "stream=codec_type,codec_name:format=format_name",
                    "-show_entries", "stream_tags=encryption",
                    "-of", "default=noprint_wrappers=1",
                    file.toString()));
            if (output == null) {
                return InspectionResult.invalid("FFprobe timed out after " + timeout.toSeconds() + "s");
            }
            var stdout = output.stdout();
            var stderr = output.stderr();

            if (output.exitCode() != 0) {
                return InspectionResult.invalid("FFprobe failed: " + truncate(stderr));
            }

            // No video stream → invalid
            if (!stdout.contains("codec_type=video")) {
                return InspectionResult.invalid("No video stream detected");
            }

            // Encrypted/password-protected video → invalid
            if (stdout.toLowerCase().contains("encryption") || stderr.toLowerCase().contains("encrypted")) {
                return InspectionResult.invalid("Video is encrypted or password-protected");
            }

            return InspectionResult.valid();
        } catch (IOException e) {
            // ffprobe binary missing — treat as inspection unavailable, mark valid
            // (production should set inspector-enabled=false explicitly if no ffprobe)
            log.warn("FFprobe not available ({}): {}", ffprobePath, e.getMessage());
            return InspectionResult.valid();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return InspectionResult.invalid("FFprobe interrupted");
        }
    }

    @Override
    public int extractDurationSeconds(InputStream data) {
        if (!enabled) {
            log.debug("FFprobe disabled — duration extraction skipped (returning 0)");
            return 0;
        }

        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("video-duration-", ".bin");
            Files.copy(data, tempFile, StandardCopyOption.REPLACE_EXISTING);
            return runFfprobeDuration(tempFile);
        } catch (IOException e) {
            log.warn("Failed to write temp file for duration extraction: {}", e.getMessage());
            return 0;
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    log.warn("Failed to delete temp file {}: {}", tempFile, e.getMessage());
                }
            }
        }
    }

    /**
     * Reads container-level format duration. This is VFR-safe — frame-count-based
     * duration is wrong for variable-frame-rate streams, but the container records
     * the actual end timestamp.
     */
    private int runFfprobeDuration(Path file) {
        try {
            var output = runProbe(List.of(
                    ffprobePath,
                    "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    file.toString()));
            if (output == null) {
                log.warn("FFprobe duration extraction timed out after {}s", timeout.toSeconds());
                return 0;
            }
            var stdout = output.stdout().trim();

            if (output.exitCode() != 0 || stdout.isEmpty() || "N/A".equalsIgnoreCase(stdout)) {
                return 0;
            }

            try {
                var seconds = Double.parseDouble(stdout);
                return seconds > 0 ? (int) Math.round(seconds) : 0;
            } catch (NumberFormatException e) {
                log.warn("Could not parse duration from ffprobe output: {}", stdout);
                return 0;
            }
        } catch (IOException e) {
            log.warn("FFprobe not available for duration ({}): {}", ffprobePath, e.getMessage());
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        }
    }

    private static String truncate(String value) {
        if (value == null) return "";
        return value.length() > 200 ? value.substring(0, 200) : value;
    }
}
