package uz.orientadvertise.services.infra.storage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import uz.orientadvertise.services.domain.content.VideoInspector;
import uz.orientadvertise.services.domain.content.VideoInspector.InspectionResult;
import uz.orientadvertise.services.domain.event.DashboardEventBroadcaster;
import uz.orientadvertise.services.domain.event.DashboardEventBroadcaster.ContentStatusPayload;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.domain.repository.ContentFileRepository;
import uz.orientadvertise.services.domain.storage.StorageClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Orchestration coverage for the transcode pipeline.
 *
 * <p>Terminal state is asserted against the repository's bulk statements rather than entity setters:
 * since v1.0.132 the pipeline holds no transaction, so each state change must be its own committed
 * statement. Mutating a detached entity and hoping a dirty check picks it up is exactly the bug that
 * made {@code TRANSCODING} unobservable and the orphan recoverer's predicate unsatisfiable.
 */
class FFmpegTranscoderTest {

    private ContentFileRepository contentFileRepository;
    private StorageClient storageClient;
    private VideoInspector videoInspector;
    private DashboardEventBroadcaster dashboardBroadcaster;
    private TranscodeExecutor transcodeExecutor;
    private FFmpegTranscoder transcoder;

    @BeforeEach
    void setUp() throws Exception {
        contentFileRepository = mock(ContentFileRepository.class);
        storageClient = mock(StorageClient.class);
        videoInspector = mock(VideoInspector.class);
        dashboardBroadcaster = mock(DashboardEventBroadcaster.class);
        transcodeExecutor = mock(TranscodeExecutor.class);
        transcoder = configure(new FFmpegTranscoder(contentFileRepository, storageClient, videoInspector,
                dashboardBroadcaster, transcodeExecutor));
        // Disable real ffmpeg for unit tests — we exercise the orchestration only
        setField(transcoder, "transcoderEnabled", false);
        // Default: the row is claimed, so the pipeline proceeds.
        when(contentFileRepository.beginTranscode(anyLong(), any())).thenReturn(1);
    }

    /** Applies the defaults Spring would inject from {@code app.video.*}. */
    private FFmpegTranscoder configure(FFmpegTranscoder t) throws Exception {
        setField(t, "ffmpegPath", "ffmpeg");
        setField(t, "thumbnailBucket", "content-thumbnails");
        setField(t, "preset", "veryfast");
        setField(t, "crf", 23);
        setField(t, "maxWidth", 1920);
        setField(t, "maxHeight", 1080);
        setField(t, "audioBitrate", "128k");
        setField(t, "transcodeTimeout", Duration.ofMinutes(15));
        setField(t, "posterTimeout", Duration.ofSeconds(60));
        return t;
    }

    private ContentFile claimedFile(long id) {
        var file = mock(ContentFile.class);
        when(file.getStorageKey()).thenReturn("raw/k");
        when(file.getStatus()).thenReturn(ContentFile.Status.TRANSCODING);
        when(file.getUploadedBy()).thenReturn("alice");
        when(contentFileRepository.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(file));
        // The live-feed routing read. NOT file.getProject(): this thread holds no persistence
        // context, so the LAZY association would throw.
        when(contentFileRepository.findProjectIdById(id)).thenReturn(3L);
        when(storageClient.download(eq("content-raw"), eq("raw/k")))
                .thenReturn(new ByteArrayInputStream("video bytes".getBytes()));
        return file;
    }

    // ---------- happy path ----------

    @Test
    void transcode_validInputAndOutput_marksReadyAndUploadsProcessed() {
        claimedFile(1L);
        when(videoInspector.inspect(any())).thenReturn(InspectionResult.valid());
        when(videoInspector.extractDurationSeconds(any())).thenReturn(120);

        transcoder.runPipeline(1L);

        verify(contentFileRepository).markTranscodeReady(eq(1L),
                org.mockito.ArgumentMatchers.startsWith("processed/"),
                anyLong(), anyString(), isNull(), eq(120), any(Instant.class));
        verify(storageClient).upload(eq("content-processed"), anyString(), any(), anyLong(), eq("video/mp4"));
        // Disabled-mode short-circuits the poster step → no thumbnail upload, no key set.
        verify(storageClient, never()).upload(eq("content-thumbnails"), any(), any(), anyLong(), any());
    }

    @Test
    void transcode_persistsProcessedSizeAndChecksumOfProcessedObject() throws Exception {
        // The device downloads the PROCESSED object, so sizeBytes must be the processed size and
        // checksum its SHA-256 — otherwise the device's downloaded-bytes check never matches and it
        // rejects every file. In disabled/passthrough mode the processed bytes equal the raw bytes.
        claimedFile(1L);
        when(videoInspector.inspect(any())).thenReturn(InspectionResult.valid());
        when(videoInspector.extractDurationSeconds(any())).thenReturn(120);

        transcoder.runPipeline(1L);

        String expected = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest("video bytes".getBytes(StandardCharsets.UTF_8)));
        verify(contentFileRepository).markTranscodeReady(eq(1L), anyString(), eq(11L), eq(expected),
                isNull(), eq(120), any(Instant.class));
    }

    // ---------- the lease / claim guard ----------

    @Test
    void transcode_rowNotClaimed_isANoOp() {
        // beginTranscode returning 0 means nobody holds the claim: already terminal, superseded, or
        // soft-deleted. A duplicate dispatch must cost nothing — no download, no encode, no write.
        claimedFile(5L);
        when(contentFileRepository.beginTranscode(eq(5L), any())).thenReturn(0);

        transcoder.runPipeline(5L);

        verify(storageClient, never()).download(anyString(), anyString());
        verify(contentFileRepository, never()).markTranscodeReady(anyLong(), anyString(), anyLong(),
                anyString(), any(), anyInt(), any());
        verify(contentFileRepository, never()).markTranscodeFailed(anyLong(), anyString(), any());
    }

    @Test
    void transcode_missingRow_logsErrorAndStops() {
        // The exact production symptom. It must be an ERROR (WARN+ is forwarded to Telegram), and
        // it must name the cause, not just the id.
        var captured = attachAppender(FFmpegTranscoder.class);
        when(contentFileRepository.findByIdAndDeletedAtIsNull(99L)).thenReturn(Optional.empty());

        transcoder.runPipeline(99L);

        verify(contentFileRepository, never()).beginTranscode(anyLong(), any());
        boolean errorFound = captured.list.stream()
                .anyMatch(e -> e.getLevel() == Level.ERROR
                        && e.getFormattedMessage().contains("dispatch lost"));
        assertTrue(errorFound, "Expected an ERROR naming the lost dispatch; captured: " + captured.list);
    }

    @Test
    void transcode_broadcastsTranscodingBeforeWorkAndTerminalAfter() {
        claimedFile(1L);
        when(videoInspector.inspect(any())).thenReturn(InspectionResult.valid());
        when(videoInspector.extractDurationSeconds(any())).thenReturn(120);
        when(contentFileRepository.markTranscodeReady(anyLong(), anyString(), anyLong(), anyString(),
                any(), anyInt(), any())).thenReturn(1);

        transcoder.runPipeline(1L);

        var payload = org.mockito.ArgumentCaptor.forClass(ContentStatusPayload.class);
        verify(dashboardBroadcaster, org.mockito.Mockito.times(2)).contentStatusChanged(payload.capture());
        assertEquals("TRANSCODING", payload.getAllValues().get(0).status());
        assertEquals("READY", payload.getAllValues().get(1).status());
    }

    @Test
    void transcode_terminalWriteLostTheRow_doesNotBroadcastReady() {
        // Negative: if the terminal UPDATE affects 0 rows the row was reclaimed or deleted mid-run.
        // Announcing READY anyway would tell the dashboard something the database does not say.
        claimedFile(1L);
        when(videoInspector.inspect(any())).thenReturn(InspectionResult.valid());
        when(videoInspector.extractDurationSeconds(any())).thenReturn(120);
        when(contentFileRepository.markTranscodeReady(anyLong(), anyString(), anyLong(), anyString(),
                any(), anyInt(), any())).thenReturn(0);

        transcoder.runPipeline(1L);

        var payload = org.mockito.ArgumentCaptor.forClass(ContentStatusPayload.class);
        verify(dashboardBroadcaster).contentStatusChanged(payload.capture());
        assertEquals("TRANSCODING", payload.getValue().status());
    }

    // ---------- what the live feed is told ----------

    @Test
    void transcode_failed_broadcastsTheSameReasonItPersisted() {
        // The defect: markFailed wrote the error to transcode_last_error and then broadcast an
        // explicit null reason, with the text in scope one line above — so a FAILED card could
        // never say why it failed, on any surface.
        claimedFile(1L);
        when(videoInspector.inspect(any())).thenReturn(InspectionResult.valid());
        when(videoInspector.extractDurationSeconds(any())).thenReturn(120);
        when(contentFileRepository.markTranscodeFailed(anyLong(), anyString(), any())).thenReturn(1);
        org.mockito.Mockito.doThrow(new RuntimeException("minio down"))
                .when(storageClient).upload(eq("content-processed"), anyString(), any(), anyLong(), eq("video/mp4"));

        transcoder.runPipeline(1L);

        // The persisted error and the broadcast reason must be the same string — one truncation,
        // one source of truth.
        var persisted = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(contentFileRepository).markTranscodeFailed(eq(1L), persisted.capture(), any(Instant.class));
        var payload = org.mockito.ArgumentCaptor.forClass(ContentStatusPayload.class);
        verify(dashboardBroadcaster, org.mockito.Mockito.times(2)).contentStatusChanged(payload.capture());
        ContentStatusPayload failed = payload.getAllValues().get(1);
        assertEquals("FAILED", failed.status());
        assertEquals(persisted.getValue(), failed.invalidReason());
        assertTrue(failed.invalidReason().contains("minio down"), failed.invalidReason());
    }

    @Test
    void transcode_broadcastsCarryTheFilesProjectAndUploader() {
        // Routing keys for the dashboard fan-out. Without them a content frame goes to every
        // operator session; with projectId alone the uploader of orphan content sees nothing.
        claimedFile(1L);
        when(videoInspector.inspect(any())).thenReturn(InspectionResult.valid());
        when(videoInspector.extractDurationSeconds(any())).thenReturn(120);
        when(contentFileRepository.markTranscodeReady(anyLong(), anyString(), anyLong(), anyString(),
                any(), anyInt(), any())).thenReturn(1);

        transcoder.runPipeline(1L);

        var payload = org.mockito.ArgumentCaptor.forClass(ContentStatusPayload.class);
        verify(dashboardBroadcaster, org.mockito.Mockito.times(2)).contentStatusChanged(payload.capture());
        for (ContentStatusPayload sent : payload.getAllValues()) {
            assertEquals(3L, sent.projectId(), "every frame of the run is routed: " + sent);
            assertEquals("alice", sent.uploadedBy(), "every frame of the run is routed: " + sent);
        }
    }

    @Test
    void transcode_orphanContent_stillNamesItsUploader() {
        // Orphan content has no project at all, so the owner is its only routing key.
        claimedFile(1L);
        when(contentFileRepository.findProjectIdById(1L)).thenReturn(null);
        when(videoInspector.inspect(any())).thenReturn(InspectionResult.invalid("FFprobe failed"));
        when(contentFileRepository.markTranscodeInvalid(anyLong(), anyString(), any())).thenReturn(1);

        transcoder.runPipeline(1L);

        var payload = org.mockito.ArgumentCaptor.forClass(ContentStatusPayload.class);
        verify(dashboardBroadcaster, org.mockito.Mockito.times(2)).contentStatusChanged(payload.capture());
        ContentStatusPayload invalid = payload.getAllValues().get(1);
        assertEquals("INVALID", invalid.status());
        org.junit.jupiter.api.Assertions.assertNull(invalid.projectId());
        assertEquals("alice", invalid.uploadedBy());
    }

    @Test
    void transcode_routingIsReadOnce_notPerTransition() {
        // Four transitions must not cost four extra single-row reads, and re-reading mid-encode
        // could only ever produce an inconsistent route.
        claimedFile(1L);
        when(videoInspector.inspect(any())).thenReturn(InspectionResult.valid());
        when(videoInspector.extractDurationSeconds(any())).thenReturn(120);
        when(contentFileRepository.markTranscodeReady(anyLong(), anyString(), anyLong(), anyString(),
                any(), anyInt(), any())).thenReturn(1);

        transcoder.runPipeline(1L);

        verify(contentFileRepository, org.mockito.Mockito.times(1)).findProjectIdById(1L);
    }

    // ---------- failure paths ----------

    @Test
    void transcode_uploadFails_marksFailed_andPersistsNoMetadata() {
        // Upload throwing must not leave the file half-reconciled: no READY, no key, no
        // size/checksum. The READY statement is never reached.
        claimedFile(1L);
        when(videoInspector.inspect(any())).thenReturn(InspectionResult.valid());
        when(videoInspector.extractDurationSeconds(any())).thenReturn(120);
        org.mockito.Mockito.doThrow(new RuntimeException("minio down"))
                .when(storageClient).upload(eq("content-processed"), anyString(), any(), anyLong(), eq("video/mp4"));

        transcoder.runPipeline(1L);

        verify(contentFileRepository).markTranscodeFailed(eq(1L), anyString(), any(Instant.class));
        verify(contentFileRepository, never()).markTranscodeReady(anyLong(), anyString(), anyLong(),
                anyString(), any(), anyInt(), any());
    }

    @Test
    void transcode_invalidInput_marksInvalidNotFailed() {
        // INVALID is terminal and NOT swept: retrying content the container rejected cannot help.
        // Recording it as FAILED instead would make the sweeper burn three attempts on it.
        claimedFile(2L);
        when(videoInspector.inspect(any())).thenReturn(InspectionResult.invalid("FFprobe failed"));

        transcoder.runPipeline(2L);

        verify(contentFileRepository).markTranscodeInvalid(eq(2L), anyString(), any(Instant.class));
        verify(contentFileRepository, never()).markTranscodeFailed(anyLong(), anyString(), any());
        verify(contentFileRepository, never()).markTranscodeReady(anyLong(), anyString(), anyLong(),
                anyString(), any(), anyInt(), any());
    }

    @Test
    void transcode_zeroDuration_marksInvalid() {
        claimedFile(3L);
        when(videoInspector.inspect(any())).thenReturn(InspectionResult.valid());
        when(videoInspector.extractDurationSeconds(any())).thenReturn(0);

        transcoder.runPipeline(3L);

        verify(contentFileRepository).markTranscodeInvalid(eq(3L),
                org.mockito.ArgumentMatchers.contains("duration"), any(Instant.class));
    }

    // ---------- queueing ----------

    @Test
    void transcodeAsync_enqueuesAtNormalPriority_urgentAtFrontOfQueue() {
        transcoder.transcodeAsync(7L);
        transcoder.transcodeAsyncUrgent(8L);

        verify(transcodeExecutor).submit(any(), eq(TranscodeExecutor.PRIORITY_NORMAL), anyString());
        verify(transcodeExecutor).submit(any(), eq(TranscodeExecutor.PRIORITY_URGENT), anyString());
    }

    // ---------- configurable encoder knobs ----------

    @Test
    void ffmpegArgs_carryTheConfiguredPresetAndResolutionCap() throws Exception {
        // The preset is the single biggest lever on peak RSS, and the resolution cap is the other
        // one. If they stop reaching the argument vector, the memory budget silently doubles.
        setField(transcoder, "preset", "medium");
        setField(transcoder, "maxHeight", 720);
        setField(transcoder, "maxWidth", 1280);
        setField(transcoder, "crf", 20);

        var args = transcoder.ffmpegArgs(Path.of("/tmp/in"), Path.of("/tmp/out.mp4"));

        assertEquals("medium", args.get(args.indexOf("-preset") + 1));
        assertEquals("20", args.get(args.indexOf("-crf") + 1));
        assertTrue(args.get(args.indexOf("-vf") + 1).contains("min(1280,iw)"), args.toString());
        assertTrue(args.get(args.indexOf("-vf") + 1).contains("min(720,ih)"), args.toString());
        // Explicitly NOT pinned: measured at 136 kB of 431 MB, and x264 already picks these on 1 vCPU.
        assertTrue(args.stream().noneMatch("-threads"::equals), "must not pin -threads: " + args);
        assertTrue(args.stream().noneMatch("-filter_threads"::equals), "must not pin -filter_threads");
    }

    // ---------- thumbnails ----------

    @Test
    void transcode_thumbnailGeneratedAndUploaded_setsKey() throws Exception {
        // Enable transcoder mode so the poster step runs; subclass overrides BOTH ffmpeg hooks
        // to keep the test deterministic without an actual ffmpeg binary on the path.
        var sub = configure(new FFmpegTranscoder(contentFileRepository, storageClient, videoInspector,
                dashboardBroadcaster, transcodeExecutor) {
            @Override
            void runFfmpeg(Path input, Path output) throws IOException {
                Files.copy(input, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            @Override
            void runFfmpegPoster(Path input, Path output) throws IOException {
                // Real ffmpeg would write JPEG bytes; a non-empty file is enough for the
                // upload branch to fire (we assert on the bucket and content-type).
                Files.write(output, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9});
            }
        });
        setField(sub, "transcoderEnabled", true);

        claimedFile(10L);
        when(videoInspector.inspect(any())).thenReturn(InspectionResult.valid());
        when(videoInspector.extractDurationSeconds(any())).thenReturn(45);

        sub.runPipeline(10L);

        verify(contentFileRepository).markTranscodeReady(eq(10L),
                org.mockito.ArgumentMatchers.startsWith("processed/"),
                eq(11L), anyString(),
                org.mockito.ArgumentMatchers.startsWith("thumbnails/"), eq(45), any(Instant.class));
        verify(storageClient).upload(eq("content-thumbnails"), anyString(), any(),
                anyLong(), eq("image/jpeg"));
    }

    @Test
    void transcode_thumbnailStepFails_fileStillReadyAndKeyNull_warnLogged() throws Exception {
        // Capture WARN logs from FFmpegTranscoder so we can assert the spec'd diagnostic.
        var captured = attachAppender(FFmpegTranscoder.class);

        var sub = configure(new FFmpegTranscoder(contentFileRepository, storageClient, videoInspector,
                dashboardBroadcaster, transcodeExecutor) {
            @Override
            void runFfmpeg(Path input, Path output) throws IOException {
                Files.copy(input, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            @Override
            void runFfmpegPoster(Path input, Path output) throws IOException {
                throw new IOException("ffmpeg poster failed (exit=1): broken pipe");
            }
        });
        setField(sub, "transcoderEnabled", true);

        claimedFile(11L);
        when(videoInspector.inspect(any())).thenReturn(InspectionResult.valid());
        when(videoInspector.extractDurationSeconds(any())).thenReturn(45);

        sub.runPipeline(11L);

        // Spec: file still READY with the processed key and size, thumbnail key null, no thumb upload.
        verify(contentFileRepository).markTranscodeReady(eq(11L),
                org.mockito.ArgumentMatchers.startsWith("processed/"),
                eq(11L), anyString(), isNull(), eq(45), any(Instant.class));
        verify(contentFileRepository, never()).markTranscodeFailed(anyLong(), anyString(), any());
        verify(storageClient, never()).upload(eq("content-thumbnails"), any(), any(), anyLong(), any());

        // Spec: a WARN must be logged so the operator can investigate.
        boolean warnFound = captured.list.stream()
                .anyMatch(e -> e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("Thumbnail generation failed"));
        assertTrue(warnFound,
                "Expected a WARN about thumbnail failure; captured: " + captured.list);
    }

    private static ListAppender<ILoggingEvent> attachAppender(Class<?> target) {
        Logger logger = (Logger) LoggerFactory.getLogger(target);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = FFmpegTranscoder.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
