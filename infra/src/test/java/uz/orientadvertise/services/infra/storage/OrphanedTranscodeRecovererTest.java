package uz.orientadvertise.services.infra.storage;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.orientadvertise.services.domain.content.Transcoder;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.domain.repository.ContentFileRepository;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrphanedTranscodeRecovererTest {

    private ContentFileRepository repository;
    private Transcoder transcoder;
    private OrphanedTranscodeRecoverer recoverer;

    @BeforeEach
    void setUp() {
        repository = mock(ContentFileRepository.class);
        transcoder = mock(Transcoder.class);
        recoverer = new OrphanedTranscodeRecoverer(repository, transcoder);
    }

    @Test
    void noOrphans_doesNothing() {
        when(repository.findByStatusAndDeletedAtIsNull(ContentFile.Status.TRANSCODING))
                .thenReturn(List.of());

        recoverer.recoverOrphanedTranscodes();

        verify(transcoder, never()).transcodeAsync(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void multipleOrphans_resetToUploadedAndRequeued() {
        var f1 = mock(ContentFile.class);
        var f2 = mock(ContentFile.class);
        when(f1.getId()).thenReturn(1L);
        when(f2.getId()).thenReturn(2L);
        when(repository.findByStatusAndDeletedAtIsNull(ContentFile.Status.TRANSCODING))
                .thenReturn(List.of(f1, f2));

        recoverer.recoverOrphanedTranscodes();

        verify(f1).setStatus(ContentFile.Status.UPLOADED);
        verify(f2).setStatus(ContentFile.Status.UPLOADED);
        verify(transcoder).transcodeAsync(1L);
        verify(transcoder).transcodeAsync(2L);
    }
}
