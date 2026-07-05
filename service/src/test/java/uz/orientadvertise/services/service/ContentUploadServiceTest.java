package uz.orientadvertise.services.service;

import java.io.ByteArrayInputStream;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.orientadvertise.services.domain.content.Transcoder;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.domain.model.Project;
import uz.orientadvertise.services.domain.repository.ContentFileRepository;
import uz.orientadvertise.services.domain.repository.ProjectRepository;
import uz.orientadvertise.services.domain.storage.StorageClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContentUploadServiceTest {

    private StorageClient storageClient;
    private ContentFileRepository contentFileRepository;
    private ProjectRepository projectRepository;
    private Transcoder transcoder;
    private ContentUploadService service;

    @BeforeEach
    void setUp() {
        storageClient = mock(StorageClient.class);
        contentFileRepository = mock(ContentFileRepository.class);
        projectRepository = mock(ProjectRepository.class);
        transcoder = mock(Transcoder.class);
        service = new ContentUploadService(storageClient, contentFileRepository,
                projectRepository, transcoder, "content-raw");
    }

    @Test
    void upload_validProject_uploadsCreatesRowAndTriggersTranscode() {
        var project = mock(Project.class);
        when(project.getId()).thenReturn(1L);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(contentFileRepository.save(any(ContentFile.class)))
                .thenAnswer(inv -> {
                    var cf = (ContentFile) inv.getArgument(0);
                    var idField = ContentFile.class.getDeclaredField("id");
                    idField.setAccessible(true);
                    idField.set(cf, 42L);
                    return cf;
                });

        var data = new ByteArrayInputStream("video bytes".getBytes());
        var result = service.upload(1L, "movie.mp4", "video/mp4", 11, data);

        assertNotNull(result);
        assertEquals(42L, result.fileId());
        assertEquals("UPLOADED", result.status());
        assertEquals(1L, result.projectId());
        assertTrue(result.storageKey().startsWith("raw/"));
        assertTrue(result.storageKey().endsWith("_movie.mp4"));

        verify(storageClient).upload(eq("content-raw"), anyString(), any(), eq(11L), eq("video/mp4"));
        verify(transcoder).transcodeAsync(42L);
    }

    @Test
    void upload_unknownProject_uploadsAsOrphan() {
        // Unknown project must NOT 404 — the upload still succeeds with project=null
        // so the operator can bind a project later via PATCH /api/content/{id}/project.
        when(projectRepository.findById(99L)).thenReturn(Optional.empty());
        when(contentFileRepository.save(any(ContentFile.class)))
                .thenAnswer(inv -> {
                    var cf = (ContentFile) inv.getArgument(0);
                    var idField = ContentFile.class.getDeclaredField("id");
                    idField.setAccessible(true);
                    idField.set(cf, 7L);
                    return cf;
                });

        var result = service.upload(99L, "file.mp4", "video/mp4", 100,
                new ByteArrayInputStream("x".getBytes()));

        assertNotNull(result);
        assertEquals(7L, result.fileId());
        assertNull(result.projectId(), "Orphan upload should report null projectId");
        // Bytes still landed in MinIO and async transcode still kicked off.
        verify(storageClient).upload(eq("content-raw"), anyString(), any(), eq(100L), eq("video/mp4"));
        verify(transcoder).transcodeAsync(7L);
    }

    @Test
    void upload_nullProjectId_uploadsAsOrphan() {
        // Null projectId path: never even hits the project repo. Same orphan outcome.
        when(contentFileRepository.save(any(ContentFile.class)))
                .thenAnswer(inv -> {
                    var cf = (ContentFile) inv.getArgument(0);
                    var idField = ContentFile.class.getDeclaredField("id");
                    idField.setAccessible(true);
                    idField.set(cf, 8L);
                    return cf;
                });

        var result = service.upload(null, "file.mp4", "video/mp4", 50,
                new ByteArrayInputStream("x".getBytes()));

        assertNotNull(result);
        assertEquals(8L, result.fileId());
        assertNull(result.projectId());
        verify(projectRepository, never()).findById(anyLong());
        verify(storageClient).upload(eq("content-raw"), anyString(), any(), eq(50L), eq("video/mp4"));
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
