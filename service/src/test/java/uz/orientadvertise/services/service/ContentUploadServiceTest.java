package uz.orientadvertise.services.service;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import uz.orientadvertise.services.domain.auth.Role;
import uz.orientadvertise.services.domain.content.ContentUploadedEvent;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.domain.model.Project;
import uz.orientadvertise.services.domain.repository.ContentFileRepository;
import uz.orientadvertise.services.domain.repository.ProjectRepository;
import uz.orientadvertise.services.domain.storage.StorageClient;
import uz.orientadvertise.services.service.OperatorScopeResolver.ScopedProjects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for the upload path.
 *
 * <p><b>Read this before adding a "does it dispatch?" assertion here.</b> The version of this class
 * that shipped the v1.0.132 incident asserted {@code verify(transcoder).transcodeAsync(42L)} and
 * passed on every run while both production uploads were being silently lost. Mock verification
 * proves a call happened; it says nothing about <em>when</em> it happened relative to the
 * transaction commit, which was the entire defect. The commit-ordering contract is therefore proven
 * by {@code ContentUploadCommitOrderingIntegrationTest} in the api module, against a real
 * transaction manager. What this class asserts is the part unit tests genuinely can: that the bytes
 * land before the row, and that the announcement is an event rather than a direct transcoder call.
 */
class ContentUploadServiceTest {

    private StorageClient storageClient;
    private ContentFileRepository contentFileRepository;
    private ProjectRepository projectRepository;
    private ApplicationEventPublisher events;
    private OperatorScopeResolver operatorScopeResolver;
    private ContentUploadService service;

    @BeforeEach
    void setUp() throws Exception {
        storageClient = mock(StorageClient.class);
        contentFileRepository = mock(ContentFileRepository.class);
        projectRepository = mock(ProjectRepository.class);
        events = mock(ApplicationEventPublisher.class);
        operatorScopeResolver = mock(OperatorScopeResolver.class);
        // Unrestricted (admin) unless a test narrows it.
        when(operatorScopeResolver.resolve()).thenReturn(new ScopedProjects("admin", Role.ADMIN, null, false));
        service = new ContentUploadService(storageClient, contentFileRepository,
                projectRepository, events, operatorScopeResolver, null, "content-raw");
        // Point the self-proxy at the instance itself: without Spring there is no transaction
        // advice to route through, and the delegation is what we want exercised. Same idiom as
        // RetentionCleanupServiceTest.
        var selfField = ContentUploadService.class.getDeclaredField("self");
        selfField.setAccessible(true);
        selfField.set(service, service);
    }

    private void savedWithId(long id) {
        when(contentFileRepository.save(any(ContentFile.class)))
                .thenAnswer(inv -> {
                    var cf = (ContentFile) inv.getArgument(0);
                    var idField = ContentFile.class.getDeclaredField("id");
                    idField.setAccessible(true);
                    idField.set(cf, id);
                    return cf;
                });
    }

    @Test
    void upload_validProject_uploadsCreatesRowAndAnnouncesForTranscode() {
        var project = mock(Project.class);
        when(project.getId()).thenReturn(1L);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        savedWithId(42L);

        var data = new ByteArrayInputStream("video bytes".getBytes());
        var result = service.upload(1L, "movie.mp4", "video/mp4", 11, data);

        assertNotNull(result);
        assertEquals(42L, result.fileId());
        assertEquals("UPLOADED", result.status());
        assertEquals(1L, result.projectId());
        assertTrue(result.storageKey().startsWith("raw/"));
        assertTrue(result.storageKey().endsWith("_movie.mp4"));

        verify(storageClient).upload(eq("content-raw"), anyString(), any(), eq(11L), eq("video/mp4"));

        var event = ArgumentCaptor.forClass(ContentUploadedEvent.class);
        verify(events).publishEvent(event.capture());
        assertEquals(42L, event.getValue().contentFileId());
        assertFalse(event.getValue().urgent());
    }

    @Test
    void upload_urgentFlag_isCarriedOnTheEvent() {
        // The listener needs the flag to pick the queue priority; losing it here would silently
        // demote every urgent upload to the back of the queue.
        savedWithId(9L);

        service.upload(null, "clip.mp4", "video/mp4", 5,
                new ByteArrayInputStream("x".getBytes()), true, "operator1");

        var event = ArgumentCaptor.forClass(ContentUploadedEvent.class);
        verify(events).publishEvent(event.capture());
        assertTrue(event.getValue().urgent());
    }

    @Test
    void upload_putsBytesInMinioBeforeTouchingTheDatabase() {
        // The PUT is a multi-megabyte network transfer; it must not happen while a Hikari
        // connection and an open transaction are held. Ordering is the observable proof.
        savedWithId(11L);

        service.upload(null, "movie.mp4", "video/mp4", 3,
                new ByteArrayInputStream("abc".getBytes()));

        InOrder order = inOrder(storageClient, contentFileRepository);
        order.verify(storageClient).upload(eq("content-raw"), anyString(), any(), anyLong(), anyString());
        order.verify(contentFileRepository).save(any(ContentFile.class));
    }

    @Test
    void upload_unknownProject_uploadsAsOrphan() {
        // Unknown project must NOT 404 — the upload still succeeds with project=null
        // so the operator can bind a project later via PATCH /api/content/{id}/project.
        when(projectRepository.findById(99L)).thenReturn(Optional.empty());
        savedWithId(7L);

        var result = service.upload(99L, "file.mp4", "video/mp4", 100,
                new ByteArrayInputStream("x".getBytes()));

        assertNotNull(result);
        assertEquals(7L, result.fileId());
        assertNull(result.projectId(), "Orphan upload should report null projectId");
        // Bytes still landed in MinIO and the transcode announcement still fired.
        verify(storageClient).upload(eq("content-raw"), anyString(), any(), eq(100L), eq("video/mp4"));
        verify(events).publishEvent(any(ContentUploadedEvent.class));
    }

    @Test
    void upload_projectOutsideOperatorScope_uploadsAsOrphanWithoutBindingIt() {
        // AUTHZ-01: a restricted operator must not land content in another tenant's project.
        // Same outcome as an unknown project — the project row is never even loaded, so the
        // response cannot differ between "exists but not yours" and "does not exist".
        when(operatorScopeResolver.resolve())
                .thenReturn(new ScopedProjects("op", Role.OPERATOR, List.of(5L), true));
        savedWithId(9L);

        var result = service.upload(42L, "file.mp4", "video/mp4", 100,
                new ByteArrayInputStream("x".getBytes()), false, "op");

        assertNull(result.projectId(), "Out-of-scope project must not be bound");
        verify(projectRepository, never()).findById(anyLong());
        var saved = ArgumentCaptor.forClass(ContentFile.class);
        verify(contentFileRepository).save(saved.capture());
        assertNull(saved.getValue().getProject());
        assertEquals("op", saved.getValue().getUploadedBy());
    }

    @Test
    void upload_projectInsideOperatorScope_bindsIt() {
        when(operatorScopeResolver.resolve())
                .thenReturn(new ScopedProjects("op", Role.OPERATOR, List.of(5L), true));
        var project = mock(Project.class);
        when(project.getId()).thenReturn(5L);
        when(projectRepository.findById(5L)).thenReturn(Optional.of(project));
        savedWithId(10L);

        var result = service.upload(5L, "file.mp4", "video/mp4", 100,
                new ByteArrayInputStream("x".getBytes()), false, "op");

        assertEquals(5L, result.projectId());
    }

    @Test
    void upload_nullProjectId_uploadsAsOrphan() {
        // Null projectId path: never even hits the project repo. Same orphan outcome.
        savedWithId(8L);

        var result = service.upload(null, "file.mp4", "video/mp4", 50,
                new ByteArrayInputStream("x".getBytes()));

        assertNotNull(result);
        assertEquals(8L, result.fileId());
        assertNull(result.projectId());
        verify(projectRepository, never()).findById(anyLong());
        verify(storageClient).upload(eq("content-raw"), anyString(), any(), eq(50L), eq("video/mp4"));
    }

    @Test
    void upload_storageFailure_persistsNothingAndAnnouncesNothing() {
        // Negative: if the bytes never reach MinIO there must be no row and no transcode. A row
        // without an object is exactly the orphan the sweeper would then burn attempts on.
        org.mockito.Mockito.doThrow(new RuntimeException("minio down"))
                .when(storageClient).upload(anyString(), anyString(), any(), anyLong(), anyString());

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () ->
                service.upload(null, "movie.mp4", "video/mp4", 3,
                        new ByteArrayInputStream("abc".getBytes())));

        verify(contentFileRepository, never()).save(any(ContentFile.class));
        verify(events, never()).publishEvent(any(ContentUploadedEvent.class));
    }

    @Test
    void upload_sanitizesFilenameInStorageKey() {
        var project = mock(Project.class);
        when(project.getId()).thenReturn(1L);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(contentFileRepository.save(any(ContentFile.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service.upload(1L, "my video!.mp4", "video/mp4", 10,
                new ByteArrayInputStream("x".getBytes()));

        assertTrue(result.storageKey().contains("my_video_.mp4"),
                "Filename should be sanitised: " + result.storageKey());
    }

    @Test
    void upload_nullFilename_doesNotCrash() {
        var project = mock(Project.class);
        when(project.getId()).thenReturn(1L);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(contentFileRepository.save(any(ContentFile.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service.upload(1L, null, "application/octet-stream", 10,
                new ByteArrayInputStream("x".getBytes()));

        assertNotNull(result.storageKey());
        assertTrue(result.storageKey().contains("unnamed"));
    }
}
