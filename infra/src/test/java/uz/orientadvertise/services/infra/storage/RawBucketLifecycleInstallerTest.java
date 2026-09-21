package uz.orientadvertise.services.infra.storage;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.minio.MinioClient;
import io.minio.SetBucketLifecycleArgs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Orchestration around the lifecycle install.
 *
 * <p>The one thing a mock cannot prove here is the thing that actually broke: whether MinIO's ILM
 * validator <em>accepts</em> the document. That was settled against a disposable MinIO on the
 * deployed release ({@code RELEASE.2025-09-07T16-13-09Z}, {@code io.minio:minio:8.5.14}) —
 * abort-only fails with {@code MalformedXML} under both an empty filter and a {@code raw/} prefix,
 * while expiration + {@code raw/} installs and reads back as
 * {@code status=Enabled prefix=raw/ expirationDays=30 abort=null}. What these tests pin is the shape
 * of the rule we send, which is what would silently regress.
 */
class RawBucketLifecycleInstallerTest {

    private MinioClient minioClient;
    private MinioProperties properties;
    private MinioHealthStatus healthStatus;
    private StorageLifecycleStatus lifecycleStatus;

    @BeforeEach
    void setUp() {
        minioClient = mock(MinioClient.class);
        properties = new MinioProperties();
        properties.setRawBucket("content-raw");
        properties.setRawExpiryDays(30);
        healthStatus = new MinioHealthStatus();
        healthStatus.markUp();
        lifecycleStatus = new StorageLifecycleStatus();
    }

    private RawBucketLifecycleInstaller installer() {
        return new RawBucketLifecycleInstaller(minioClient, properties, healthStatus, lifecycleStatus);
    }

    private SetBucketLifecycleArgs captureInstalledArgs() throws Exception {
        var args = ArgumentCaptor.forClass(SetBucketLifecycleArgs.class);
        verify(minioClient).setBucketLifecycle(args.capture());
        return args.getValue();
    }

    @Test
    void installsExpirationScopedToTheRawPrefix() throws Exception {
        installer().installLifecyclePolicy();

        var args = captureInstalledArgs();
        assertEquals("content-raw", args.bucket());
        var rules = args.config().rules();
        assertEquals(1, rules.size());
        var rule = rules.getFirst();

        assertEquals(RawBucketLifecycleInstaller.RULE_ID, rule.id());
        assertNotNull(rule.expiration(), "an Expiration action is what makes MinIO accept the rule");
        assertEquals(30, rule.expiration().days());
        // Scoped: processed MP4s and posters live in other buckets, but the prefix makes the intent
        // explicit and survives anyone repointing raw-bucket at a shared bucket later.
        assertEquals(RawBucketLifecycleInstaller.RAW_PREFIX, rule.filter().prefix());
    }

    @Test
    void neverSendsAnAbortAction_becauseMinioRejectsIt() throws Exception {
        // The whole reason the old rule failed on every boot. MinIO has no abort type in its
        // lifecycle package; including it as the sole action produced MalformedXML, and even
        // alongside an expiration MinIO discards it on readback.
        installer().installLifecyclePolicy();

        var rule = captureInstalledArgs().config().rules().getFirst();
        assertNull(rule.abortIncompleteMultipartUpload());
    }

    @Test
    void successIsReportedOnTheHealthStatus() {
        installer().installLifecyclePolicy();

        assertEquals(StorageLifecycleStatus.State.OK, lifecycleStatus.getState());
        assertTrue(lifecycleStatus.getDetail().contains("30 day"), lifecycleStatus.getDetail());
    }

    @Test
    void zeroDays_disablesInstallation_butWarnsThatStorageWillGrowForever() throws Exception {
        var captured = attachAppender();
        properties.setRawExpiryDays(0);

        installer().installLifecyclePolicy();

        verify(minioClient, never()).setBucketLifecycle(any());
        // Disabled is a deliberate configuration choice, not a failure — so OK, not FAILED.
        assertEquals(StorageLifecycleStatus.State.OK, lifecycleStatus.getState());
        assertTrue(captured.list.stream().anyMatch(e -> e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("without bound")),
                "disabling expiry must still say out loud that storage grows forever: " + captured.list);
    }

    @Test
    void minioDegraded_defersRatherThanReportingFailure() throws Exception {
        // We never got to try, so calling this a lifecycle failure would double-report the MinIO
        // outage that is the real signal.
        healthStatus.markDegraded("startup bucket check: ConnectException");

        installer().installLifecyclePolicy();

        verify(minioClient, never()).setBucketLifecycle(any());
        assertEquals(StorageLifecycleStatus.State.PENDING, lifecycleStatus.getState());
    }

    @Test
    void installFailure_logsAtErrorAndFlagsTheHealthStatus() throws Exception {
        // The regression guard for the actual incident: a rejected install must NOT be a WARN that
        // scrolls past. It ran for the life of the deployment that way, so operators believed
        // cleanup was running while the stored config was zero bytes.
        var captured = attachAppender();
        doThrow(new IllegalStateException("The XML you provided was not well-formed"))
                .when(minioClient).setBucketLifecycle(any());

        installer().installLifecyclePolicy();

        assertEquals(StorageLifecycleStatus.State.FAILED, lifecycleStatus.getState());
        assertTrue(lifecycleStatus.getDetail().contains("well-formed"), lifecycleStatus.getDetail());
        assertTrue(captured.list.stream().anyMatch(e -> e.getLevel() == Level.ERROR
                        && e.getFormattedMessage().contains("will NOT be reclaimed")),
                "a rejected install must log at ERROR and name the consequence: " + captured.list);
    }

    @Test
    void installFailure_doesNotPropagate() throws Exception {
        // Runs on ApplicationReadyEvent: a throw here would be an unhandled listener exception at
        // startup. A missing lifecycle rule is a degraded state, not a reason to refuse traffic.
        doThrow(new RuntimeException("minio down")).when(minioClient).setBucketLifecycle(any());

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> installer().installLifecyclePolicy());
    }

    private static ListAppender<ILoggingEvent> attachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(RawBucketLifecycleInstaller.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }
}
