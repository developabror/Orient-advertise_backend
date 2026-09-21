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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
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
 *   1. Take the lease ({@code beginTranscode}) — the start guard
 *   2. Pull raw bytes from content-raw → temp file
 *   3. Inspect with FFprobe (corrupt / encrypted → INVALID)
 *   4. Transcode to H.264/AAC MP4 via ffmpeg
 *   5. Re-inspect output (corrupt output → FAILED)
 *   6. Extract container duration (zero/unreadable → INVALID)
 *   7. Upload result to content-processed
 *   <b>7b. Generate poster JPEG (best-effort) and upload to content-thumbnails</b>
 *   8. Commit the terminal state
 *
 * <h2>Transaction shape (v1.0.132) — read this before touching it</h2>
 * This class runs with <b>no transaction of its own</b>. Every database touch is a single
 * self-committing statement on {@link ContentFileRepository}, giving three short transactions —
 * lease, (nothing), terminal — around a run that can take minutes.
 *
 * <p>It used to be one {@code @Transactional} spanning the whole body, which had three separate
 * consequences, all of them live in production:
 * <ul>
 *   <li>A Hikari connection was pinned for the entire encode. With
 *       {@code leak-detection-threshold: 30000} in the prod profile, <em>every</em> real transcode
 *       emitted an "Apparent connection leak detected" stack trace — pure false positives that
 *       train operators to ignore leak warnings.</li>
 *   <li>{@code TRANSCODING} was never observable. The status was set on a managed entity and every
 *       exit path overwrote it with a terminal value before commit, so the row went
 *       {@code UPLOADED → READY|FAILED|INVALID} in one commit. The recovery component's
 *       {@code status='TRANSCODING'} predicate could therefore never match, and the API reported
 *       {@code UPLOADED} for the whole encode while the live feed said {@code TRANSCODING}.</li>
 *   <li>The "Transcode complete" INFO was logged <em>before</em> commit, so a commit failure
 *       produced a success log followed by a silent rollback to {@code UPLOADED} and an orphaned
 *       object in MinIO.</li>
 * </ul>
 * Because each repository statement commits when it returns, the success log and the terminal
 * broadcast below are after-commit <em>by construction</em>.
 *
 * <p><b>Dispatch contract.</b> The caller must already have won the row with
 * {@link ContentFileRepository#claimForTranscode}. {@link #beginTranscode} re-checks that claim, so
 * a duplicate or stale dispatch degrades to a logged no-op rather than a second encode.
 *
 * <p>If the ffmpeg binary is missing (e.g. CI without ffmpeg), the configured
 * {@code app.video.transcoder-enabled} flag short-circuits to mark READY with the raw bytes copied
 * through. The thumbnail step also short-circuits in that mode.
 *
 * <p>The thumbnail step is intentionally non-fatal: a poster failure must NOT flip the file out of
 * READY. The processed MP4 is the load-bearing artifact; the thumbnail is a UX nicety the
 * listing/detail endpoints render with a null fallback when missing.
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
    private final TranscodeExecutor transcodeExecutor;

    @Value("${app.video.ffmpeg-path:ffmpeg}")
    private String ffmpegPath;

    @Value("${app.video.transcoder-enabled:true}")
    private boolean transcoderEnabled;

    @Value("${app.minio.thumbnail-bucket:content-thumbnails}")
    private String thumbnailBucket;

    /**
     * Encoder knobs. All configurable on purpose: the preset alone moves peak RSS by 2× (measured on
     * the same 13 s 1080p25 input: {@code medium} 438 MiB / 44.9 s, {@code veryfast} 218 MiB /
     * 14.3 s) and the output resolution is the only other lever that matters. A small host keeps the
     * defaults; a resourced one raises them from the environment without a rebuild.
     *
     * <p>Deliberately absent: {@code -threads} / {@code -filter_threads}. On a 1-vCPU host x264
     * already auto-selects {@code threads=1 lookahead_threads=1 sliced_threads=0}, and setting them
     * explicitly changed peak RSS by 136 kB out of 431 MB. Only preset, {@code rc-lookahead} and
     * resolution move the number.
     */
    @Value("${app.video.preset:veryfast}")
    private String preset;

    @Value("${app.video.crf:23}")
    private int crf;

    @Value("${app.video.max-width:1920}")
    private int maxWidth;

    @Value("${app.video.max-height:1080}")
    private int maxHeight;

    @Value("${app.video.audio-bitrate:128k}")
    private String audioBitrate;

    /**
     * Hard ceiling on one encode. Now genuinely reachable — at roughly 6× realtime on a small box a
     * 5-minute source exceeds 15 minutes, gets {@code destroyForcibly}'d and lands in FAILED — which
     * is exactly why FAILED became sweepable up to the attempt cap in the same release.
     */
    @Value("${app.video.timeout:PT15M}")
    private Duration transcodeTimeout;

    @Value("${app.video.poster-timeout:PT60S}")
    private Duration posterTimeout;

    public FFmpegTranscoder(ContentFileRepository contentFileRepository,
                             StorageClient storageClient,
                             VideoInspector videoInspector,
                             DashboardEventBroadcaster dashboardBroadcaster,
                             TranscodeExecutor transcodeExecutor) {
        this.contentFileRepository = contentFileRepository;
        this.storageClient = storageClient;
        this.videoInspector = videoInspector;
        this.dashboardBroadcaster = dashboardBroadcaster;
        this.transcodeExecutor = transcodeExecutor;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Front-of-queue via {@link TranscodeExecutor#PRIORITY_URGENT}: an urgent job overtakes any
     * queued normal work at whatever width the pool was planned for. It cannot preempt an encode
     * that has already started — nothing short of killing a running ffmpeg could — so on a
     * single-width pool "urgent" means "next", not "now".
     */
    @Override
    public void transcodeAsyncUrgent(Long contentFileId) {
        log.info("Urgent transcode queued [id={}] — front of queue", contentFileId);
        enqueue(contentFileId, TranscodeExecutor.PRIORITY_URGENT);
    }

    @Override
    public void transcodeAsync(Long contentFileId) {
        enqueue(contentFileId, TranscodeExecutor.PRIORITY_NORMAL);
    }

    private void enqueue(Long contentFileId, int priority) {
        transcodeExecutor.submit(() -> runPipeline(contentFileId), priority,
                "transcode id=" + contentFileId);
    }

    void runPipeline(Long contentFileId) {
        var file = contentFileRepository.findByIdAndDeletedAtIsNull(contentFileId).orElse(null);
        if (file == null) {
            // Loud on purpose. WARN+ is forwarded to Telegram by TelegramAppender, so this is the
            // alarm that was missing when two production uploads vanished silently.
            log.error("Transcode requested for missing content file [id={}] — dispatch lost, fired "
                    + "pre-commit, or the row was soft-deleted; TranscodeSweeper will re-drive it "
                    + "if the row still exists", contentFileId);
            return;
        }

        // Start guard + lease refresh, in one committed statement. A 0 here means nobody holds the
        // claim any more (already terminal, superseded, or soft-deleted since we read it), so a
        // duplicate dispatch costs nothing.
        if (contentFileRepository.beginTranscode(contentFileId, Instant.now()) != 1) {
            log.info("Transcode skipped [id={}] — row is not claimed (status={}); "
                    + "another worker owns it or it already reached a terminal state",
                    contentFileId, file.getStatus());
            return;
        }

        log.info("Transcoding [id={}, src={}, preset={}, maxHeight={}]",
                contentFileId, file.getStorageKey(), preset, maxHeight);
        // Read the live-feed routing ONCE, before any broadcast. Not file.getProject().getId():
        // this thread has no persistence context, so touching the LAZY association would throw.
        var route = new FeedRoute(contentFileRepository.findProjectIdById(contentFileId),
                file.getUploadedBy());
        // TRANSCODING is now a committed, observable state — broadcast it immediately so operators
        // see work start in real time and the API agrees with the live feed.
        broadcast(contentFileId, ContentFile.Status.TRANSCODING, null, route);

        Path rawTemp = null;
        Path processedTemp = null;
        try {
            // 2. Download raw to temp
            rawTemp = Files.createTempFile("transcode-raw-", ".bin");
            try (var stream = storageClient.download(RAW_BUCKET, file.getStorageKey())) {
                Files.copy(stream, rawTemp, StandardCopyOption.REPLACE_EXISTING);
            }

            // 3. Inspect input
            try (var input = Files.newInputStream(rawTemp)) {
                if (videoInspector.inspect(input) instanceof InspectionResult.Invalid invalid) {
                    log.warn("Input rejected by FFprobe [id={}]: {}", contentFileId, invalid.reason());
                    markInvalid(contentFileId, invalid.reason(), route);
                    return;
                }
            }

            // 4. Transcode (or short-circuit if disabled)
            processedTemp = Files.createTempFile("transcode-out-", ".mp4");
            if (transcoderEnabled) {
                runFfmpeg(rawTemp, processedTemp);
            } else {
                Files.copy(rawTemp, processedTemp, StandardCopyOption.REPLACE_EXISTING);
                log.debug("Transcoder disabled — copying raw bytes through");
            }

            // 5. Validate output
            try (var output = Files.newInputStream(processedTemp)) {
                if (videoInspector.inspect(output) instanceof InspectionResult.Invalid invalid) {
                    log.error("Transcode produced invalid output [id={}]: {}", contentFileId, invalid.reason());
                    markFailed(contentFileId, "Transcode produced invalid output: " + invalid.reason(), route);
                    return;
                }
            }

            // 6. Extract duration (container-level — VFR-safe).
            //    Duration of 0 means we couldn't read the container reliably → INVALID.
            int duration;
            try (var dur = Files.newInputStream(processedTemp)) {
                duration = videoInspector.extractDurationSeconds(dur);
            }
            if (duration <= 0) {
                log.warn("Marked INVALID due to zero duration [id={}]", contentFileId);
                markInvalid(contentFileId,
                        "Could not determine video duration (container reports 0 or unreadable)", route);
                return;
            }

            // 7. Upload processed to content-processed
            var processedKey = "processed/" + UUID.randomUUID() + ".mp4";
            var size = Files.size(processedTemp);
            // Integrity checksum of the EXACT bytes we serve (the processed MP4), so the device
            // can verify the download cryptographically. SHA-256 hex; matches the size below.
            var checksum = Sha256.of(Files.newInputStream(processedTemp)).hex();
            try (var out = Files.newInputStream(processedTemp)) {
                storageClient.upload(PROCESSED_BUCKET, processedKey, out, size, "video/mp4");
            }

            // 7b. Generate poster JPEG (best-effort). A failure here MUST NOT flip the file out of
            //     READY — the processed MP4 has already been uploaded; the thumbnail is a UX
            //     nicety. Disabled-mode skips ffmpeg entirely so CI without an ffmpeg binary keeps
            //     passing (matches the step-4 short-circuit).
            String thumbnailKey = generateThumbnail(contentFileId, processedTemp);

            // 8. Mark READY. Record the PROCESSED object's size as the canonical size: it's the
            //    bytes a device downloads from the presigned processed-bucket URL, which differs
            //    from the original upload size set at ingest. Without this the device is told a
            //    size that never matches its download and rejects every file.
            //
            //    This statement commits before the log line below, so a rollback can no longer
            //    hide behind a success message.
            int updated = contentFileRepository.markTranscodeReady(contentFileId, processedKey, size,
                    checksum, thumbnailKey, duration, Instant.now());
            if (updated != 1) {
                log.warn("Transcode finished but the row was no longer claimed [id={}] — "
                        + "terminal state not written; processed object {} is orphaned",
                        contentFileId, processedKey);
                return;
            }
            log.info("Transcode complete [id={}, processed={}, thumbnail={}, size={}, checksum={}]",
                    contentFileId, processedKey, thumbnailKey, size, checksum);
            broadcast(contentFileId, ContentFile.Status.READY, null, route);

        } catch (Exception e) {
            // Include the exception type: e.getMessage() is null for plenty of real failures (NPE,
            // some IO errors), and "Transcode failed [id=1]: null" tells an operator nothing.
            String reason = describe(e);
            log.error("Transcode failed [id={}]: {}", contentFileId, reason, e);
            markFailed(contentFileId, reason, route);
        } finally {
            deleteQuietly(rawTemp);
            deleteQuietly(processedTemp);
        }
    }

    private String generateThumbnail(Long contentFileId, Path processedTemp) {
        if (!transcoderEnabled) {
            log.debug("Transcoder disabled — skipping thumbnail generation [id={}]", contentFileId);
            return null;
        }
        Path thumbTemp = null;
        try {
            thumbTemp = Files.createTempFile("transcode-thumb-", ".jpg");
            runFfmpegPoster(processedTemp, thumbTemp);
            var thumbSize = Files.size(thumbTemp);
            if (thumbSize <= 0) {
                log.warn("Thumbnail step produced empty file [id={}] — leaving thumbnailStorageKey null",
                        contentFileId);
                return null;
            }
            var thumbnailKey = "thumbnails/" + UUID.randomUUID() + ".jpg";
            try (var thumbIn = Files.newInputStream(thumbTemp)) {
                storageClient.upload(thumbnailBucket, thumbnailKey, thumbIn, thumbSize, THUMBNAIL_CONTENT_TYPE);
            }
            log.debug("Thumbnail uploaded [id={}, key={}, size={}]", contentFileId, thumbnailKey, thumbSize);
            return thumbnailKey;
        } catch (Exception thumbEx) {
            // Swallow: file stays READY, key stays null. WARN so the operator can investigate
            // without the file being treated as a transcode failure.
            log.warn("Thumbnail generation failed [id={}]: {} — file remains READY without thumbnail",
                    contentFileId, thumbEx.getMessage());
            return null;
        } finally {
            deleteQuietly(thumbTemp);
        }
    }

    /**
     * Commit FAILED (retryable — the sweeper re-drives it up to the attempt cap) and broadcast.
     * Never throws: a bookkeeping failure must not mask the original error already logged.
     *
     * <p>The <b>same</b> truncated error goes into {@code transcode_last_error} and onto the frame.
     * It used to broadcast an explicit {@code null} reason while the text sat in scope one line
     * above, so a FAILED card could never say why it failed — on any surface.
     */
    private void markFailed(Long contentFileId, String error, FeedRoute route) {
        try {
            var trimmed = truncate(error, 500);
            if (contentFileRepository.markTranscodeFailed(contentFileId, trimmed, Instant.now()) == 1) {
                broadcast(contentFileId, ContentFile.Status.FAILED, trimmed, route);
            }
        } catch (Exception e) {
            log.error("Could not record FAILED state [id={}]: {} — the lease will expire and "
                    + "TranscodeSweeper will reclaim the row", contentFileId, e.getMessage());
        }
    }

    /** Commit INVALID (terminal — retrying cannot help, so the sweeper never re-drives it). */
    private void markInvalid(Long contentFileId, String reason, FeedRoute route) {
        try {
            var trimmed = truncate(reason, 500);
            if (contentFileRepository.markTranscodeInvalid(contentFileId, trimmed, Instant.now()) == 1) {
                broadcast(contentFileId, ContentFile.Status.INVALID, trimmed, route);
            }
        } catch (Exception e) {
            log.error("Could not record INVALID state [id={}]: {}", contentFileId, e.getMessage());
        }
    }

    /**
     * Push a content-status frame to the dashboard feed. Best-effort: a broadcast failure must never
     * fail the transcode. Every call site is already past its database commit, so a frame can no
     * longer announce a state that then rolls back — which is why the old
     * {@code TransactionSynchronization} dance is gone.
     */
    private void broadcast(Long contentFileId, ContentFile.Status status, String reason, FeedRoute route) {
        try {
            dashboardBroadcaster.contentStatusChanged(new ContentStatusPayload(
                    contentFileId, status.name(), reason, Instant.now(),
                    route.projectId(), route.uploadedBy()));
        } catch (Exception e) {
            log.warn("Content-status broadcast failed [id={}, status={}]: {}",
                    contentFileId, status, e.getMessage());
        }
    }

    /**
     * Where a content-status frame is allowed to go: the file's project (null for orphan content)
     * and its uploader. Resolved once per run and carried down the pipeline rather than re-read at
     * each transition — four extra single-row reads per encode would buy nothing, and the row's
     * project cannot usefully change mid-encode.
     */
    private record FeedRoute(Long projectId, String uploadedBy) {}

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

        // Posters are cheap (single decoded frame); the default 60s ceiling is generous and prevents
        // runaway processes from occupying a transcode slot on a pathological input.
        long seconds = Math.max(1, posterTimeout.toSeconds());
        boolean finished = process.waitFor(seconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("ffmpeg poster timed out after " + seconds + " seconds");
        }
        if (process.exitValue() != 0) {
            throw new IOException("ffmpeg poster failed (exit=" + process.exitValue()
                    + "): " + truncate(stderr.get(), 300));
        }
    }

    /**
     * Package-private (vs. private) so test subclasses can override and stub the actual
     * ffmpeg invocation — the orchestration path is what we want to assert in unit tests.
     */
    void runFfmpeg(Path input, Path output) throws IOException, InterruptedException {
        var process = new ProcessBuilder(ffmpegArgs(input, output))
                .redirectErrorStream(false)
                .start();

        // Drain stderr (ffmpeg writes progress here) so the subprocess doesn't block on a full pipe
        var stderr = drainAsync(process.getErrorStream());

        long seconds = Math.max(1, transcodeTimeout.toSeconds());
        boolean finished = process.waitFor(seconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("ffmpeg timed out after " + seconds + " seconds");
        }
        if (process.exitValue() != 0) {
            throw new IOException("ffmpeg failed (exit=" + process.exitValue() + "): "
                    + truncate(stderr.get(), 300));
        }
    }

    /**
     * H.264 video, AAC audio, MP4 container, capped resolution, fast-start for streaming.
     * Package-private so a test can assert the configured knobs actually reach the argument vector.
     */
    List<String> ffmpegArgs(Path input, Path output) {
        var args = new ArrayList<String>();
        args.add(ffmpegPath);
        args.add("-y");
        args.add("-i");
        args.add(input.toString());
        args.add("-vf");
        args.add("scale='min(%d,iw)':'min(%d,ih)':force_original_aspect_ratio=decrease"
                .formatted(maxWidth, maxHeight));
        args.add("-c:v");
        args.add("libx264");
        args.add("-preset");
        args.add(preset);
        args.add("-crf");
        args.add(String.valueOf(crf));
        args.add("-c:a");
        args.add("aac");
        args.add("-b:a");
        args.add(audioBitrate);
        args.add("-movflags");
        args.add("+faststart");
        args.add("-f");
        args.add("mp4");
        args.add(output.toString());
        return args;
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

    /** Exception type plus message — the message alone is null for an NPE and several IO failures. */
    private static String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank()
                ? e.getClass().getSimpleName()
                : e.getClass().getSimpleName() + ": " + message;
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() > max ? value.substring(0, max) : value;
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
