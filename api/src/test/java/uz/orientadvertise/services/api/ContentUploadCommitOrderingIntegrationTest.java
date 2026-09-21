package uz.orientadvertise.services.api;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import uz.orientadvertise.services.Application;
import uz.orientadvertise.services.domain.content.Transcoder;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.domain.repository.ContentFileRepository;
import uz.orientadvertise.services.domain.storage.StorageClient;
import uz.orientadvertise.services.service.ContentUploadService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;

/**
 * The test that would have caught the v1.0.132 incident, and the reason it did not exist before:
 * <b>mock verification cannot see a commit-ordering bug</b>. {@code ContentUploadServiceTest} asserted
 * {@code verify(transcoder).transcodeAsync(42L)} and passed on every run while two production
 * uploads were being silently lost.
 *
 * <p>What actually failed in production: the upload service dispatched the transcode from
 * <i>inside</i> its own {@code @Transactional} method, ten lines after {@code save()}. Because
 * {@code ContentFile} is {@code IDENTITY}-generated, Hibernate had issued the INSERT to obtain the
 * id — but the row was uncommitted, and under READ COMMITTED a SELECT on another connection takes no
 * lock against a foreign uncommitted INSERT. The worker's {@code findById} returned empty, the
 * pipeline logged a WARN and returned, and the file stayed {@code UPLOADED} forever.
 *
 * <p>So this test asserts the only thing that matters: <b>at the moment the transcoder is called, is
 * the row visible to an independent transaction?</b> The test double answers that question by reading
 * through {@code REQUIRES_NEW}, which is exactly what the real worker thread does. Revert the
 * after-commit dispatch and this test fails.
 */
@SpringBootTest(classes = {Application.class,
        ContentUploadCommitOrderingIntegrationTest.RecordingTranscoderConfig.class})
@ActiveProfiles("test")
class ContentUploadCommitOrderingIntegrationTest {

    /**
     * Replaces the real transcoder. Instead of encoding anything it answers, for each dispatch,
     * "was this row readable from a fresh transaction at the instant I was called?"
     */
    static class RecordingTranscoder implements Transcoder {

        record Dispatch(Long id, boolean visibleInFreshTransaction, boolean urgent) {}

        final List<Dispatch> dispatches = new CopyOnWriteArrayList<>();
        private final ContentFileRepository repository;
        private final TransactionTemplate freshTransaction;

        RecordingTranscoder(ContentFileRepository repository, PlatformTransactionManager txManager) {
            this.repository = repository;
            // A template of our own — never mutate the shared auto-configured one. REQUIRES_NEW is
            // the point: it reads on an independent transaction, exactly as the real worker thread
            // does on its own connection.
            this.freshTransaction = new TransactionTemplate(txManager);
            this.freshTransaction.setPropagationBehavior(Propagation.REQUIRES_NEW.value());
        }

        private void record(Long id, boolean urgent) {
            boolean visible = Boolean.TRUE.equals(freshTransaction.execute(
                    status -> repository.findById(id).isPresent()));
            dispatches.add(new Dispatch(id, visible, urgent));
        }

        @Override
        public void transcodeAsync(Long contentFileId) {
            record(contentFileId, false);
        }

        @Override
        public void transcodeAsyncUrgent(Long contentFileId) {
            record(contentFileId, true);
        }
    }

    @TestConfiguration
    static class RecordingTranscoderConfig {

        @Bean
        @Primary
        RecordingTranscoder recordingTranscoder(ContentFileRepository repository,
                                                 PlatformTransactionManager txManager) {
            return new RecordingTranscoder(repository, txManager);
        }

        /**
         * MinIO is not available in the test context and the bytes are irrelevant here — only the
         * transaction boundary is under test.
         */
        @Bean
        @Primary
        StorageClient stubStorageClient() {
            var client = mock(StorageClient.class);
            org.mockito.Mockito.doNothing().when(client)
                    .upload(anyString(), anyString(), any(), anyLong(), anyString());
            return client;
        }
    }

    @Autowired
    private ContentUploadService uploadService;

    @Autowired
    private RecordingTranscoder transcoder;

    @Autowired
    private ContentFileRepository contentFileRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void reset() {
        transcoder.dispatches.clear();
    }

    @Test
    void rowIsVisibleToAnIndependentTransactionWhenTheTranscodeIsDispatched() {
        var result = uploadService.upload(null, "clip.mp4", "video/mp4", 11,
                new ByteArrayInputStream("video bytes".getBytes()));

        assertEquals(1, transcoder.dispatches.size(), "exactly one dispatch per upload");
        var dispatch = transcoder.dispatches.getFirst();
        assertEquals(result.fileId(), dispatch.id());
        assertTrue(dispatch.visibleInFreshTransaction(),
                "the transcode was dispatched before the upload transaction committed — the row is "
                        + "invisible to the worker's connection and the file will be stuck in UPLOADED");
    }

    @Test
    void urgentUpload_alsoDispatchesAfterCommit() {
        // The urgent branch was worse in an inverted way: its old executor used CallerRunsPolicy, so
        // on queue overflow the whole pipeline ran inline on the request thread inside the still-open
        // transaction — and then SUCCEEDED, making the urgent path silently alternate between
        // working and failing depending on pool pressure.
        uploadService.upload(null, "urgent.mp4", "video/mp4", 11,
                new ByteArrayInputStream("video bytes".getBytes()), true, "admin");

        var dispatch = transcoder.dispatches.getFirst();
        assertTrue(dispatch.urgent(), "the urgent flag must survive to the dispatch");
        assertTrue(dispatch.visibleInFreshTransaction());
    }

    @Test
    void theRowIsClaimedByTheDispatch_soTheSweeperWillNotDoubleDrive() {
        var result = uploadService.upload(null, "clip.mp4", "video/mp4", 11,
                new ByteArrayInputStream("video bytes".getBytes()));

        var stored = contentFileRepository.findById(result.fileId()).orElseThrow();
        assertEquals(ContentFile.Status.TRANSCODING, stored.getStatus(),
                "the after-commit listener claims the row, so TRANSCODING is committed and visible");
        assertEquals(1, stored.getTranscodeAttempts());
    }

    @Test
    void rolledBackUpload_dispatchesNothing() {
        // The corollary of AFTER_COMMIT, and previously a real leak: a rollback used to leave a
        // queued transcode chasing a row that never existed.
        long before = contentFileRepository.count();

        assertThrowsRollback(() -> transactionTemplate.execute(status -> {
            uploadService.upload(null, "doomed.mp4", "video/mp4", 11,
                    new ByteArrayInputStream("video bytes".getBytes()));
            throw new IllegalStateException("caller aborts after the upload");
        }));

        assertTrue(transcoder.dispatches.isEmpty(),
                "a rolled-back upload must dispatch nothing; got " + transcoder.dispatches);
        assertEquals(before, contentFileRepository.count(), "and must leave no row behind");
    }

    @Test
    @Transactional
    void dispatchIsDeferredUntilCommit_notFiredMidTransaction() {
        // Inside a caller-managed transaction the dispatch must NOT have happened yet when
        // upload() returns — that deferral is the whole fix, stated as an assertion.
        uploadService.upload(null, "deferred.mp4", "video/mp4", 11,
                new ByteArrayInputStream("video bytes".getBytes()));

        assertTrue(transcoder.dispatches.isEmpty(),
                "dispatch must wait for commit, not fire inside the open transaction; got "
                        + transcoder.dispatches);
    }

    private static void assertThrowsRollback(Runnable action) {
        try {
            action.run();
            org.junit.jupiter.api.Assertions.fail("expected the transaction to roll back");
        } catch (IllegalStateException expected) {
            // the rollback trigger
        }
    }
}
