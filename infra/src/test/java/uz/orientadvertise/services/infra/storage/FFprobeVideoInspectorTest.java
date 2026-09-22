package uz.orientadvertise.services.infra.storage;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import uz.orientadvertise.services.domain.content.VideoInspector.InspectionResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The inspector's process handling, with a scripted stand-in for ffprobe.
 *
 * <p>It used to read stdout to the end before {@code waitFor(30s)}, so the timeout could never fire,
 * and it read stderr only after stdout closed, so a flood of errors deadlocked it. Either way the
 * transcode worker was held forever — and since LOGIC-08 the sweeper leaves a held file alone, so
 * on a single-width pool one such upload would stall every transcode behind it. Every test is
 * pre-emptively time-boxed: a regression fails here instead of hanging the build.
 */
@DisabledOnOs(OS.WINDOWS)
class FFprobeVideoInspectorTest {

    @TempDir
    Path dir;

    private FFprobeVideoInspector inspectorRunning(String script, Duration timeout) throws Exception {
        Path ffprobe = dir.resolve("ffprobe");
        Files.writeString(ffprobe, "#!/bin/sh\n" + script + "\n");
        Files.setPosixFilePermissions(ffprobe, PosixFilePermissions.fromString("rwx------"));
        var inspector = new FFprobeVideoInspector();
        ReflectionTestUtils.setField(inspector, "ffprobePath", ffprobe.toString());
        ReflectionTestUtils.setField(inspector, "enabled", true);
        ReflectionTestUtils.setField(inspector, "timeout", timeout);
        return inspector;
    }

    private static String invalidReason(InspectionResult result) {
        return assertInstanceOf(InspectionResult.Invalid.class, result).reason();
    }

    private static ByteArrayInputStream bytes() {
        return new ByteArrayInputStream("not really a video".getBytes());
    }

    @Test
    void hungFfprobe_timesOut_andIsReportedInvalid() throws Exception {
        var inspector = inspectorRunning("sleep 30", Duration.ofSeconds(1));

        var result = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> inspector.inspect(bytes()));

        assertTrue(invalidReason(result).contains("timed out"), invalidReason(result));
    }

    @Test
    void errorFlood_onStderr_doesNotDeadlock() throws Exception {
        // ~200 KB of errors: several times a pipe buffer, written before anything reaches stdout.
        var inspector = inspectorRunning(
                "i=0; while [ $i -lt 4000 ]; do echo 'decode error: corrupt macroblock at frame' >&2; "
                        + "i=$((i+1)); done; echo 'codec_type=video'",
                Duration.ofSeconds(10));

        var result = assertTimeoutPreemptively(Duration.ofSeconds(15), () -> inspector.inspect(bytes()));

        assertInstanceOf(InspectionResult.Valid.class, result, "a video stream was reported; got: " + result);
    }

    @Test
    void normalRun_isParsedAsBefore() throws Exception {
        var inspector = inspectorRunning("echo 'codec_type=video'; echo 'codec_name=h264'", Duration.ofSeconds(10));

        assertInstanceOf(InspectionResult.Valid.class,
                assertTimeoutPreemptively(Duration.ofSeconds(15), () -> inspector.inspect(bytes())));
    }

    @Test
    void noVideoStream_isStillInvalid() throws Exception {
        var inspector = inspectorRunning("echo 'codec_type=audio'", Duration.ofSeconds(10));

        var result = assertTimeoutPreemptively(Duration.ofSeconds(15), () -> inspector.inspect(bytes()));

        assertEquals("No video stream detected", invalidReason(result));
    }

    @Test
    void hungDurationProbe_timesOut_andReturnsZero() throws Exception {
        var inspector = inspectorRunning("sleep 30", Duration.ofSeconds(1));

        assertEquals(0, (int) assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> inspector.extractDurationSeconds(bytes())));
    }

    @Test
    void duration_isRounded() throws Exception {
        var inspector = inspectorRunning("echo '12.6'", Duration.ofSeconds(10));

        assertEquals(13, (int) assertTimeoutPreemptively(Duration.ofSeconds(15),
                () -> inspector.extractDurationSeconds(bytes())));
    }
}
