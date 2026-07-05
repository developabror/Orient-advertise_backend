package uz.orientadvertise.services.infra.storage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
            var pb = new ProcessBuilder(
                    ffprobePath,
                    "-v", "error",
                    "-show_entries", "stream=codec_type,codec_name:format=format_name",
                    "-show_entries", "stream_tags=encryption",
                    "-of", "default=noprint_wrappers=1",
                    file.toString());
            pb.redirectErrorStream(false);
            var process = pb.start();

            var stdout = readAll(process.getInputStream());
            var stderr = readAll(process.getErrorStream());

            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return InspectionResult.invalid("FFprobe timed out after 30s");
            }

            if (process.exitValue() != 0) {
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
            var pb = new ProcessBuilder(
                    ffprobePath,
                    "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    file.toString());
            pb.redirectErrorStream(false);
            var process = pb.start();

            var stdout = readAll(process.getInputStream()).trim();

            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("FFprobe duration extraction timed out");
                return 0;
            }

            if (process.exitValue() != 0 || stdout.isEmpty() || "N/A".equalsIgnoreCase(stdout)) {
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

    private static String readAll(InputStream in) throws IOException {
        try (var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            var sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }

    private static String truncate(String value) {
        if (value == null) return "";
        return value.length() > 200 ? value.substring(0, 200) : value;
    }
}
