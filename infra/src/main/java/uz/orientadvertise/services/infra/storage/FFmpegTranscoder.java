package uz.orientadvertise.services.infra.storage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uz.orientadvertise.services.domain.content.Transcoder;
import uz.orientadvertise.services.domain.content.VideoInspector;
import uz.orientadvertise.services.domain.content.VideoInspector.InspectionResult;
import uz.orientadvertise.services.domain.event.DashboardEventBroadcaster;
import uz.orientadvertise.services.domain.event.DashboardEventBroadcaster.ContentStatusPayload;
import uz.orientadvertise.services.common.util.Sha256;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.domain.repository.ContentFileRepository;
import uz.orientadvertise.services.domain.storage.StorageClient;

/**
 * Real ffmpeg-backed transcoder. Pipeline:
 *   1. Pull raw bytes from content-raw → temp file
 *   2. Inspect with FFprobe (corrupt / encrypted → INVALID)
 *   3. Transcode to H.264/AAC MP4 1080p max via ffmpeg
 *   4. Re-inspect output (corrupt output → FAILED)
 *   5. Extract container duration (zero/unreadable → INVALID)
 *   6. Upload result to content-processed
 *   <b>6b. Generate poster JPEG (best-effort) and upload to content-thumbnails</b>
 *   7. Mark READY with processed_storage_key (and thumbnail_storage_key if 6b succeeded)
 *
 * <p>If the ffmpeg binary is missing (e.g. CI without ffmpeg), the configured
 * {@code app.video.transcoder-enabled} flag short-circuits to mark READY with
 * the raw key as the processed key (no actual processing). The thumbnail step
 * also short-circuits in that mode — {@code thumbnail_storage_key} stays null.
 *
 * <p>The thumbnail step is intentionally non-fatal: a poster failure must NOT
 * flip the file out of READY. The processed MP4 is the load-bearing artifact;
 * the thumbnail is a UX nicety the listing/detail endpoints render with a null
 * fallback when missing.
 */
@Component
public class FFmpegTranscoder implements Transcoder {

    private static final Logger log = LoggerFactory.getLogger(FFmpegTranscoder.class);
    private static final String RAW_BUCKET = "content-raw";
    private static final String PROCESSED_BUCKET = "content-processed";
    private static final String THUMBNAIL_CONTENT_TYPE = "image/jpeg";

    private final ContentFileRepository contentFileRepository;
    private final StorageClient storageClient;
    private final VideoInspector videoInspector;
    private final DashboardEventBroadcaster dashboardBroadcaster;

    @Value("${app.video.ffmpeg-path:ffmpeg}")
    private String ffmpegPath;

    @Value("${app.video.transcoder-enabled:true}")
    private boolean transcoderEnabled;

    @Value("${app.minio.thumbnail-bucket:content-thumbnails}")
    private String thumbnailBucket;

    public FFmpegTranscoder(ContentFileRepository contentFileRepository,
                             StorageClient storageClient,
                             VideoInspector videoInspector,
                             DashboardEventBroadcaster dashboardBroadcaster) {
        this.contentFileRepository = contentFileRepository;
        this.storageClient = storageClient;
        this.videoInspector = videoInspector;
        this.dashboardBroadcaster = dashboardBroadcaster;
    }

    @Override
    @Async("urgentTranscodeExecutor")
    @Transactional
    public void transcodeAsyncUrgent(Long contentFileId) {
        log.info("Urgent transcode triggered [id={}] — front of queue", contentFileId);
        runPipeline(contentFileId);
    }

    @Override
    @Async("auditExecutor")
    @Transactional
    public void transcodeAsync(Long contentFileId) {
        runPipeline(contentFileId);
    }

    private void runPipeline(Long contentFileId) {
        var file = contentFileRepository.findById(contentFileId).orElse(null);
        if (file == null) {
            log.warn("Transcode requested for missing content file [id={}]", contentFileId);
            return;
        }

        Path rawTemp = null;
        Path processedTemp = null;
        try {
            // 1. Download raw to temp
            rawTemp = Files.createTempFile("transcode-raw-", ".bin");
            try (var stream = storageClient.download(RAW_BUCKET, file.getStorageKey())) {
                Files.copy(stream, rawTemp, StandardCopyOption.REPLACE_EXISTING);
            }

            // 2. Inspect input
            try (var input = Files.newInputStream(rawTemp)) {
                if (videoInspector.inspect(input) instanceof InspectionResult.Invalid invalid) {
                    file.markInvalid(invalid.reason());
                    log.warn("Input rejected by FFprobe [id={}]: {}", contentFileId, invalid.reason());
                    broadcastTerminal(file);
                    return;
                }
            }

            file.setStatus(ContentFile.Status.TRANSCODING);
            log.info("Transcoding [id={}, src={}]", contentFileId, file.getStorageKey());
            // TRANSCODING is a progress hint — broadcast immediately so operators see work
            // start in real time. Terminal states broadcast after-commit (see broadcastTerminal).
            broadcastNow(file);

            // 3. Transcode (or short-circuit if disabled)
            processedTemp = Files.createTempFile("transcode-out-", ".mp4");
            if (transcoderEnabled) {
                runFfmpeg(rawTemp, processedTemp);
            } else {
                Files.copy(rawTemp, processedTemp, StandardCopyOption.REPLACE_EXISTING);
                log.debug("Transcoder disabled — copying raw bytes through");
            }

            // 4. Validate output
            try (var output = Files.newInputStream(processedTemp)) {
                if (videoInspector.inspect(output) instanceof InspectionResult.Invalid invalid) {
                    log.error("Transcode produced invalid output [id={}]: {}", contentFileId, invalid.reason());
                    file.setStatus(ContentFile.Status.FAILED);
                    broadcastTerminal(file);
                    return;
                }
            }

            // 5. Extract duration (container-level — VFR-safe).
            //    Duration of 0 means we couldn't read the container reliably → INVALID.
            int duration;
            try (var dur = Files.newInputStream(processedTemp)) {
                duration = videoInspector.extractDurationSeconds(dur);
            }
            if (duration <= 0) {
                file.markInvalid("Could not determine video duration (container reports 0 or unreadable)");
                log.warn("Marked INVALID due to zero duration [id={}]", contentFileId);
                broadcastTerminal(file);
                return;
            }
            file.setDurationSeconds(duration);

            // 6. Upload processed to content-processed
            var processedKey = "processed/" + UUID.randomUUID() + ".mp4";
            var size = Files.size(processedTemp);
            // Integrity checksum of the EXACT bytes we serve (the processed MP4), so the device
            // can verify the download cryptographically. SHA-256 hex; matches the size below.
            var checksum = Sha256.of(Files.newInputStream(processedTemp)).hex();
            try (var out = Files.newInputStream(processedTemp)) {
                storageClient.upload(PROCESSED_BUCKET, processedKey, out, size, "video/mp4");
            }

            // 6b. Generate poster JPEG (best-effort). A failure here MUST NOT flip the
            //     file out of READY — the processed MP4 has already been uploaded; the
            //     thumbnail is a UX nicety. Disabled-mode skips ffmpeg entirely so CI
            //     without an ffmpeg binary keeps passing (matches the step-3 short-circuit).
            String thumbnailKey = null;
            if (transcoderEnabled) {
                Path thumbTemp = null;
                try {
                    thumbTemp = Files.createTempFile("transcode-thumb-", ".jpg");
                    runFfmpegPoster(processedTemp, thumbTemp);
                    var thumbSize = Files.size(thumbTemp);
                    if (thumbSize > 0) {
                        thumbnailKey = "thumbnails/" + UUID.randomUUID() + ".jpg";
                        try (var thumbIn = Files.newInputStream(thumbTemp)) {
                            storageClient.upload(thumbnailBucket, thumbnailKey, thumbIn,
                                    thumbSize, THUMBNAIL_CONTENT_TYPE);
                        }
                        log.debug("Thumbnail uploaded [id={}, key={}, size={}]",
                                contentFileId, thumbnailKey, thumbSize);
                    } else {
                        log.warn("Thumbnail step produced empty file [id={}] — leaving thumbnailStorageKey null",
                                contentFileId);
                    }
                } catch (Exception thumbEx) {
                    // Swallow: file stays READY, key stays null. WARN so the operator can
                    // investigate without the file being treated as a transcode failure.
                    log.warn("Thumbnail generation failed [id={}]: {} — file remains READY without thumbnail",
                            contentFileId, thumbEx.getMessage());
                    thumbnailKey = null;
                } finally {
                    deleteQuietly(thumbTemp);
                }
            } else {
                log.debug("Transcoder disabled — skipping thumbnail generation [id={}]", contentFileId);
            }

            // 7. Mark READY. Record the PROCESSED object's size as the canonical size: it's
            //    the bytes a device downloads from the presigned processed-bucket URL, which
            //    differs from the original upload size set at ingest. Without this the device
            //    is told a size that never matches its download and rejects every file.
            file.setProcessedStorageKey(processedKey);
            file.setSizeBytes(size);
            file.setChecksum(checksum);
            if (thumbnailKey != null) {
                file.setThumbnailStorageKey(thumbnailKey);
            }
            file.setStatus(ContentFile.Status.READY);
            log.info("Transcode complete [id={}, processed={}, thumbnail={}, size={}, checksum={}]",
                    contentFileId, processedKey, thumbnailKey, size, checksum);
            broadcastTerminal(file);

        } catch (Exception e) {
            log.error("Transcode failed [id={}]: {}", contentFileId, e.getMessage(), e);
            file.setStatus(ContentFile.Status.FAILED);
            broadcastTerminal(file);
        } finally {
            deleteQuietly(rawTemp);
            deleteQuietly(processedTemp);
        }
    }

    /**
     * Broadcast the current content status immediately (used for the transient TRANSCODING
     * hint). Best-effort: a broadcast failure must never fail the transcode.
     */
    private void broadcastNow(ContentFile f) {
        safeBroadcast(payloadOf(f));
    }

    /**
     * Broadcast a TERMINAL status (READY / FAILED / INVALID) only after the surrounding
     * transaction commits, so a rolled-back transcode never emits a false terminal. The
     * payload is captured now (the entity won't change after a terminal set). When no
     * transaction is active (e.g. unit tests), fire immediately so the contract stays
     * observable.
     */
    private void broadcastTerminal(ContentFile f) {
        var payload = payloadOf(f);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safeBroadcast(payload);
                }
            });
        } else {
            safeBroadcast(payload);
        }
    }

    private static ContentStatusPayload payloadOf(ContentFile f) {
        var status = f.getStatus();
        return new ContentStatusPayload(f.getId(),
                status != null ? status.name() : null,
                f.getInvalidReason(), Instant.now());
    }

    private void safeBroadcast(ContentStatusPayload p) {
        try {
            dashboardBroadcaster.contentStatusChanged(p);
        } catch (Exception e) {
            log.warn("Content-status broadcast failed [id={}, status={}]: {}",
                    p.contentId(), p.status(), e.getMessage());
        }
    }

    /**
     * Generate a poster frame from the processed MP4. Picks the first frame past the
     * one-second mark — far enough in to skip black/title intros, cheap enough that the
     * seek is a fast-decode. Scales to 480-px wide while preserving aspect ratio.
     *
     * <p>Throws on non-zero exit (the caller catches and downgrades to a WARN).
     *
     * <p>Package-private (vs. private) so test subclasses can override and stub the
     * actual ffmpeg invocation — the orchestration around it is what unit tests assert.
     */
    void runFfmpegPoster(Path input, Path output) throws IOException, InterruptedException {
        var pb = new ProcessBuilder(
                ffmpegPath,
                "-y",
                "-ss", "00:00:01",
                "-i", input.toString(),
                "-vframes", "1",
                "-q:v", "5",
                "-vf", "scale=480:-1:force_original_aspect_ratio=decrease",
                output.toString());
        pb.redirectErrorStream(false);
        var process = pb.start();

        var stderr = drainAsync(process.getErrorStream());

        // Posters are cheap (single decoded frame); a 60s ceiling is generous and prevents
        // runaway processes from blocking the transcode executor on a pathological input.
        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("ffmpeg poster timed out after 60 seconds");
        }
        if (process.exitValue() != 0) {
            throw new IOException("ffmpeg poster failed (exit=" + process.exitValue()
                    + "): " + truncate(stderr.get()));
        }
    }

    /**
     * Package-private (vs. private) so test subclasses can override and stub the actual
     * ffmpeg invocation — the orchestration path is what we want to assert in unit tests.
     */
    void runFfmpeg(Path input, Path output) throws IOException, InterruptedException {
        // H.264 video, AAC audio, MP4 container, 1080p max, fast-start for streaming
        var pb = new ProcessBuilder(
                ffmpegPath,
                "-y",
                "-i", input.toString(),
                "-vf", "scale='min(1920,iw)':'min(1080,ih)':force_original_aspect_ratio=decrease",
                "-c:v", "libx264",
                "-preset", "medium",
                "-crf", "23",
                "-c:a", "aac",
                "-b:a", "128k",
                "-movflags", "+faststart",
                "-f", "mp4",
                output.toString());
        pb.redirectErrorStream(false);
        var process = pb.start();

        // Drain stderr (ffmpeg writes progress here) so the subprocess doesn't block on a full pipe
        var stderr = drainAsync(process.getErrorStream());

        boolean finished = process.waitFor(15, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("ffmpeg timed out after 15 minutes");
        }
        if (process.exitValue() != 0) {
            throw new IOException("ffmpeg failed (exit=" + process.exitValue() + "): " + truncate(stderr.get()));
        }
    }

    private static java.util.concurrent.atomic.AtomicReference<String> drainAsync(InputStream in) {
        var ref = new java.util.concurrent.atomic.AtomicReference<>("");
        var thread = new Thread(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                var sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (sb.length() < 4000) sb.append(line).append('\n');
                }
                ref.set(sb.toString());
            } catch (IOException ignored) {
            }
        });
        thread.setDaemon(true);
        thread.start();
        return ref;
    }

    private static String truncate(String value) {
        if (value == null) return "";
        return value.length() > 300 ? value.substring(0, 300) : value;
    }

    private static void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Failed to delete temp file {}: {}", path, e.getMessage());
        }
    }
}
