package uz.orientadvertise.services.infra.storage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import uz.orientadvertise.services.domain.content.VideoInspector;
import uz.orientadvertise.services.domain.content.VideoInspector.InspectionResult;
import uz.orientadvertise.services.domain.event.DashboardEventBroadcaster;
import uz.orientadvertise.services.domain.event.DashboardEventBroadcaster.ContentStatusPayload;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.domain.repository.ContentFileRepository;
import uz.orientadvertise.services.domain.storage.StorageClient;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FFmpegTranscoderTest {

    private ContentFileRepository contentFileRepository;
    private StorageClient storageClient;
    private VideoInspector videoInspector;
    private DashboardEventBroadcaster dashboardBroadcaster;
    private FFmpegTranscoder transcoder;

    @BeforeEach
    void setUp() throws Exception {
        contentFileRepository = mock(ContentFileRepository.class);
        storageClient = mock(StorageClient.class);
        videoInspector = mock(VideoInspector.class);
        dashboardBroadcaster = mock(DashboardEventBroadcaster.class);
        transcoder = new FFmpegTranscoder(contentFileRepository, storageClient, videoInspector,
                dashboardBroadcaster);
        // Disable real ffmpeg for unit tests — we exercise the orchestration only
        setField(transcoder, "transcoderEnabled", false);
        setField(transcoder, "ffmpegPath", "ffmpeg");
    }

    @Test
    void transcode_validInputAndOutput_marksReadyAndUploadsProcessed() {
        var file = mock(ContentFile.class);
        when(file.getStorageKey()).thenReturn("raw/k");
        when(contentFileRepository.findById(1L)).thenReturn(Optional.of(file));
        when(storageClient.download(eq("content-raw"), eq("raw/k")))
                .thenReturn(new ByteArrayInputStream("video bytes".getBytes()));
        when(videoInspector.inspect(any())).thenReturn(InspectionResult.valid());
        when(videoInspector.extractDurationSeconds(any())).thenReturn(120);

        transcoder.transcodeAsync(1L);

        verify(file).setStatus(ContentFile.Status.TRANSCODING);
        verify(file).setStatus(ContentFile.Status.READY);
        verify(file).setProcessedStorageKey(org.mockito.ArgumentMatchers.startsWith("processed/"));
        verify(file).setDurationSeconds(120);
        verify(storageClient).upload(eq("content-processed"), anyString(), any(), anyLong(), eq("video/mp4"));
        // Disabled-mode short-circuits the poster step → no thumbnail upload, no key set.
        verify(storageClient, never()).upload(eq("content-thumbnails"), any(), any(), anyLong(), any());
        verify(file, never()).setThumbnailStorageKey(any());
    }

    @Test
    void transcode_persistsProcessedSizeToContentFile() {
        // Regression: the device downloads the PROCESSED object, so sizeBytes must be set to
        // the processed size — not left at the original upload size, which makes the device's
        // downloaded-bytes check fail and reject the file. In disabled/passthrough mode the
        // processed bytes equal the raw bytes we feed in ("video bytes" = 11 bytes).
        var file = mock(ContentFile.class);
        when(file.getStorageKey()).thenReturn("raw/k");
        when(contentFileRepository.findById(1L)).thenReturn(Optional.of(file));
        when(storageClient.download(eq("content-raw"), eq("raw/k")))
                .thenReturn(new ByteArrayInputStream("video bytes".getBytes()));
        when(videoInspector.inspect(any())).thenReturn(InspectionResult.valid());
        when(videoInspector.extractDurationSeconds(any())).thenReturn(120);

        transcoder.transcodeAsync(1L);

        // Size persisted == the processed object's size == the size uploaded to MinIO.
        verify(file).setSizeBytes(11L);
    }

    @Test
    void transcode_persistsSha256ChecksumOfProcessedObject() throws Exception {
        // The device verifies integrity against checksum (SHA-256 hex of the served object).
        // In disabled/passthrough mode the processed bytes == the fed-in "video bytes".
        var file = mock(ContentFile.class);
        when(file.getStorageKey()).thenReturn("raw/k");
        when(contentFileRepository.findById(1L)).thenReturn(Optional.of(file));
        when(storageClient.download(eq("content-raw"), eq("raw/k")))
                .thenReturn(new ByteArrayInputStream("video bytes".getBytes()));
        when(videoInspector.inspect(any())).thenReturn(InspectionResult.valid());
        when(videoInspector.extractDurationSeconds(any())).thenReturn(120);

        transcoder.transcodeAsync(1L);

        String expected = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest("video bytes".getBytes(StandardCharsets.UTF_8)));
        verify(file).setChecksum(expected);
    }

    @Test
    void transcode_uploadFails_marksFailed_andPersistsNoMetadata() {
        // Upload throwing must not leave the file half-reconciled: no READY, no key, no
        // size/checksum. The READY block (which sets all of those) is never reached.
        var file = mock(ContentFile.class);
        when(file.getStorageKey()).thenReturn("raw/k");
        when(contentFileRepository.findById(1L)).thenReturn(Optional.of(file));
        when(storageClient.download(eq("content-raw"), eq("raw/k")))
                .thenReturn(new ByteArrayInputStream("video bytes".getBytes()));
        when(videoInspector.inspect(any())).thenReturn(InspectionResult.valid());
        when(videoInspector.extractDurationSeconds(any())).thenReturn(120);
        org.mockito.Mockito.doThrow(new RuntimeException("minio down"))
                .when(storageClient).upload(eq("content-processed"), anyString(), any(), anyLong(), eq("video/mp4"));

        transcoder.transcodeAsync(1L);

        verify(file).setStatus(ContentFile.Status.FAILED);
        verify(file, never()).setStatus(ContentFile.Status.READY);
        verify(file, never()).setProcessedStorageKey(any());
        verify(file, never()).setSizeBytes(anyLong());
        verify(file, never()).setChecksum(any());
    }

    @Test
    void transcode_failurePaths_doNotTouchSizeBytes() {
        // INVALID input never reaches the READY block, so the original size must be left intact.
        var file = mock(ContentFile.class);
        when(file.getStorageKey()).thenReturn("raw/k");
        when(contentFileRepository.findById(2L)).thenReturn(Optional.of(file));
        when(storageClient.download(eq("content-raw"), eq("raw/k")))
                .thenReturn(new ByteArrayInputStream("video bytes".getBytes()));
        when(videoInspector.inspect(any())).thenReturn(InspectionResult.invalid("FFprobe failed"));

        transcoder.transcodeAsync(2L);

        verify(file).markInvalid(anyString());
        verify(file, never()).setSizeBytes(anyLong());
        verify(file, never()).setChecksum(any());
    }

    @Test
    void transcode_thumbnailGeneratedAndUploaded_setsKey() throws Exception {
        // Enable transcoder mode so step 6b runs; subclass overrides BOTH ffmpeg hooks
        // to keep the test deterministic without an actual ffmpeg binary on the path.
        var sub = new FFmpegTranscoder(contentFileRepository, storageClient, videoInspector,
                dashboardBroadcaster) {
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
        };
        setField(sub, "transcoderEnabled", true);
        setField(sub, "ffmpegPath", "ffmpeg");
        setField(sub, "thumbnailBucket", "content-thumbnails");

        var file = mock(ContentFile.class);
        when(file.getStorageKey()).thenReturn("raw/k");
        when(contentFileRepository.findById(10L)).thenReturn(Optional.of(file));
        when(storageClient.download(eq("content-raw"), eq("raw/k")))
                .thenReturn(new ByteArrayInputStream("video bytes".getBytes()));
        when(videoInspector.inspect(any())).thenReturn(InspectionResult.valid());
        when(videoInspector.extractDurationSeconds(any())).thenReturn(45);

        sub.transcodeAsync(10L);

        verify(file).setProcessedStorageKey(org.mockito.ArgumentMatchers.startsWith("processed/"));
        verify(file).setThumbnailStorageKey(org.mockito.ArgumentMatchers.startsWith("thumbnails/"));
        verify(file).setStatus(ContentFile.Status.READY);
        // Real-ffmpeg path (transcoderEnabled=true) must also persist the processed size —
        // setSizeBytes lives outside the transcoderEnabled guard, so it fires here too.
        verify(file).setSizeBytes(11L);
        verify(storageClient).upload(eq("content-thumbnails"), anyString(), any(),
                anyLong(), eq("image/jpeg"));
    }

    @Test
    void transcode_thumbnailStepFails_fileStillReadyAndKeyNull_warnLogged() throws Exception {
        // Capture WARN logs from FFmpegTranscoder so we can assert the spec'd diagnostic.
        var captured = attachAppender(FFmpegTranscoder.class);

        var sub = new FFmpegTranscoder(contentFileRepository, storageClient, videoInspector,
                dashboardBroadcaster) {
            @Override
            void runFfmpeg(Path input, Path output) throws IOException {
                Files.copy(input, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            @Override
            void runFfmpegPoster(Path input, Path output) throws IOException {
                throw new IOException("ffmpeg poster failed (exit=1): broken pipe");
            }
        };
        setField(sub, "transcoderEnabled", true);
        setField(sub, "ffmpegPath", "ffmpeg");
        setField(sub, "thumbnailBucket", "content-thumbnails");

        var file = mock(ContentFile.class);
        when(file.getStorageKey()).thenReturn("raw/k");
        when(contentFileRepository.findById(11L)).thenReturn(Optional.of(file));
        when(storageClient.download(eq("content-raw"), eq("raw/k")))
                .thenReturn(new ByteArrayInputStream("video bytes".getBytes()));
        when(videoInspector.inspect(any())).thenReturn(InspectionResult.valid());
        when(videoInspector.extractDurationSeconds(any())).thenReturn(45);

        sub.transcodeAsync(11L);

        // Spec: file still READY, processed key set, thumbnail key NEVER set, no thumb upload.
        verify(file).setProcessedStorageKey(org.mockito.ArgumentMatchers.startsWith("processed/"));
        verify(file).setStatus(ContentFile.Status.READY);
        verify(file, never()).setStatus(ContentFile.Status.FAILED);
        verify(file, never()).setThumbnailStorageKey(any());
        verify(storageClient, never()).upload(eq("content-thumbnails"), any(), any(), anyLong(), any());
        // setSizeBytes runs in the READY block AFTER the (failed) thumbnail step, so a thumbnail
        // failure must not prevent the processed size from being persisted.
        verify(file).setSizeBytes(11L);

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

    @Test
    void transcode_zeroDuration_marksInvalidNoUpload() {
        var file = mock(ContentFile.class);
        when(file.getStorageKey()).thenReturn("raw/k");
        when(contentFileRepository.findById(5L)).thenReturn(Optional.of(file));
        when(storageClient.download(any(), any()))
                .thenReturn(new ByteArrayInputStream("video bytes".getBytes()));
        when(videoInspector.inspect(any())).thenReturn(InspectionResult.valid());
        when(videoInspector.extractDurationSeconds(any())).thenReturn(0); // unreadable container

        transcoder.transcodeAsync(5L);

        verify(file).markInvalid(org.mockito.ArgumentMatchers.contains("duration"));
        verify(file, never()).setStatus(ContentFile.Status.READY);
        verify(file, never()).setDurationSeconds(org.mockito.ArgumentMatchers.anyInt());
        verify(storageClient, never()).upload(eq("content-processed"), any(), any(), anyLong(), any());
    }

    @Test
    void transcode_negativeDuration_marksInvalid() {
        var file = mock(ContentFile.class);
        when(file.getStorageKey()).thenReturn("raw/k");
        when(contentFileRepository.findById(6L)).thenReturn(Optional.of(file));
        when(storageClient.download(any(), any()))
                .thenReturn(new ByteArrayInputStream("video bytes".getBytes()));
        when(videoInspector.inspect(any())).thenReturn(InspectionResult.valid());
        when(videoInspector.extractDurationSeconds(any())).thenReturn(-1);

        transcoder.transcodeAsync(6L);

        verify(file).markInvalid(org.mockito.ArgumentMatchers.contains("duration"));
    }

    @Test
    void transcode_invalidInput_marksInvalidNoUpload() {
        var file = mock(ContentFile.class);
        when(file.getStorageKey()).thenReturn("raw/k");
        when(contentFileRepository.findById(2L)).thenReturn(Optional.of(file));
        when(storageClient.download(any(), any()))
                .thenReturn(new ByteArrayInputStream("garbage".getBytes()));
        when(videoInspector.inspect(any()))
                .thenReturn(InspectionResult.invalid("FFprobe failed"));

        transcoder.transcodeAsync(2L);

        verify(file).markInvalid("FFprobe failed");
        verify(storageClient, never()).upload(eq("content-processed"), any(), any(), anyLong(), any());
    }

    @Test
    void transcode_invalidOutput_marksFailed() {
        var file = mock(ContentFile.class);
        when(file.getStorageKey()).thenReturn("raw/k");
        when(contentFileRepository.findById(3L)).thenReturn(Optional.of(file));
        when(storageClient.download(any(), any()))
                .thenReturn(new ByteArrayInputStream("video bytes".getBytes()));
        // Input passes, output fails
        when(videoInspector.inspect(any()))
                .thenReturn(InspectionResult.valid())            // input
                .thenReturn(InspectionResult.invalid("bad mp4")); // output

        transcoder.transcodeAsync(3L);

        verify(file).setStatus(ContentFile.Status.TRANSCODING);
        verify(file).setStatus(ContentFile.Status.FAILED);
        verify(storageClient, never()).upload(eq("content-processed"), any(), any(), anyLong(), any());
    }

    @Test
    void transcode_missingFile_returnsQuietly() {
        when(contentFileRepository.findById(99L)).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> transcoder.transcodeAsync(99L));
        verify(videoInspector, never()).inspect(any());
    }

    @Test
    void transcode_storageDownloadFails_marksFailed() {
        var file = mock(ContentFile.class);
        when(file.getStorageKey()).thenReturn("raw/k");
        when(contentFileRepository.findById(4L)).thenReturn(Optional.of(file));
        when(storageClient.download(any(), any())).thenThrow(new RuntimeException("MinIO down"));

        transcoder.transcodeAsync(4L);

        verify(file).setStatus(ContentFile.Status.FAILED);
    }

    // ===== Part B: content-status broadcast over /ws/dashboard =====

    @Test
    void transcode_validInput_broadcastsTranscodingThenReady() {
        var file = mock(ContentFile.class);
        when(file.getId()).thenReturn(1L);
        when(file.getStorageKey()).thenReturn("raw/k");
        // getStatus() is read once per broadcast: TRANSCODING (immediate), then READY (terminal).
        when(file.getStatus()).thenReturn(ContentFile.Status.TRANSCODING, ContentFile.Status.READY);
        when(contentFileRepository.findById(1L)).thenReturn(Optional.of(file));
        when(storageClient.download(eq("content-raw"), eq("raw/k")))
                .thenReturn(new ByteArrayInputStream("video bytes".getBytes()));
        when(videoInspector.inspect(any())).thenReturn(InspectionResult.valid());
        when(videoInspector.extractDurationSeconds(any())).thenReturn(120);

        transcoder.transcodeAsync(1L);

        // No active transaction in this unit test → terminal broadcast fires immediately too.
        var captor = ArgumentCaptor.forClass(ContentStatusPayload.class);
        verify(dashboardBroadcaster, times(2)).contentStatusChanged(captor.capture());
        var statuses = captor.getAllValues().stream().map(ContentStatusPayload::status).toList();
        assertEquals(java.util.List.of("TRANSCODING", "READY"), statuses);
        assertEquals(1L, captor.getAllValues().get(0).contentId());
    }

    @Test
    void transcode_invalidInput_broadcastsInvalid() {
        var file = mock(ContentFile.class);
        when(file.getStorageKey()).thenReturn("raw/k");
        when(file.getStatus()).thenReturn(ContentFile.Status.INVALID);
        when(file.getInvalidReason()).thenReturn("FFprobe failed");
        when(contentFileRepository.findById(2L)).thenReturn(Optional.of(file));
        when(storageClient.download(any(), any()))
                .thenReturn(new ByteArrayInputStream("garbage".getBytes()));
        when(videoInspector.inspect(any())).thenReturn(InspectionResult.invalid("FFprobe failed"));

        transcoder.transcodeAsync(2L);

        var captor = ArgumentCaptor.forClass(ContentStatusPayload.class);
        verify(dashboardBroadcaster, times(1)).contentStatusChanged(captor.capture());
        assertEquals("INVALID", captor.getValue().status());
        assertEquals("FFprobe failed", captor.getValue().invalidReason());
    }

    /**
     * Negative: a broadcast failure must never fail the transcode — the dashboard feed is
     * best-effort (mirrors DeviceHeartbeatService.emitStatusChangeEvent).
     */
    @Test
    void transcode_broadcasterThrows_doesNotFailTranscode() {
        var file = mock(ContentFile.class);
        when(file.getStorageKey()).thenReturn("raw/k");
        when(contentFileRepository.findById(1L)).thenReturn(Optional.of(file));
        when(storageClient.download(eq("content-raw"), eq("raw/k")))
                .thenReturn(new ByteArrayInputStream("video bytes".getBytes()));
        when(videoInspector.inspect(any())).thenReturn(InspectionResult.valid());
        when(videoInspector.extractDurationSeconds(any())).thenReturn(120);
        doThrow(new RuntimeException("redis down")).when(dashboardBroadcaster).contentStatusChanged(any());

        assertDoesNotThrow(() -> transcoder.transcodeAsync(1L));

        verify(file).setStatus(ContentFile.Status.READY);
        verify(file, never()).setStatus(ContentFile.Status.FAILED);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        // Walk the class hierarchy so anonymous subclasses (used in the new tests below)
        // can still set fields declared on FFmpegTranscoder itself.
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException ignore) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
