package uz.orientadvertise.services.service;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.auth.Role;
import uz.orientadvertise.services.domain.model.AdvertiserContentAccess;
import uz.orientadvertise.services.domain.model.AppUser;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.domain.repository.AdvertiserContentAccessRepository;
import uz.orientadvertise.services.domain.repository.AppUserRepository;
import uz.orientadvertise.services.domain.repository.ContentFileRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdvertiserContentServiceTest {

    private AdvertiserContentAccessRepository accessRepository;
    private AppUserRepository userRepository;
    private ContentFileRepository contentFileRepository;
    private AdvertiserContentService service;

    @BeforeEach
    void setUp() {
        accessRepository = mock(AdvertiserContentAccessRepository.class);
        userRepository = mock(AppUserRepository.class);
        contentFileRepository = mock(ContentFileRepository.class);
        service = new AdvertiserContentService(accessRepository, userRepository, contentFileRepository);
    }

    @Test
    void getAccessibleContent_noLinkedContent_returnsEmptyList() {
        // Edge case: advertiser with zero linked content sees empty list, not error.
        when(accessRepository.findAccessibleContent(99L)).thenReturn(List.of());

        var result = service.getAccessibleContent(99L);

        assertNotNull(result, "Should return empty list, not null");
        assertTrue(result.isEmpty(), "Advertiser with no linked content sees empty dashboard");
    }

    @Test
    void getAccessibleContent_withContent_returnsLinkedFiles() {
        var file1 = mock(ContentFile.class);
        var file2 = mock(ContentFile.class);
        when(accessRepository.findAccessibleContent(1L)).thenReturn(List.of(file1, file2));

        var result = service.getAccessibleContent(1L);

        assertEquals(2, result.size());
    }

    @Test
    void linkContent_unknownUser_throws404() {
        when(userRepository.findById(7L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () ->
                service.linkContent(7L, 10L, "admin"));
    }

    @Test
    void linkContent_nonAdvertiserUser_rejected() {
        var user = mock(AppUser.class);
        when(user.getRole()).thenReturn(Role.OPERATOR);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));

        assertThrows(IllegalStateException.class, () ->
                service.linkContent(7L, 10L, "admin"));
    }

    @Test
    void linkContent_unknownOrDeletedContent_throws404() {
        var user = mock(AppUser.class);
        when(user.getRole()).thenReturn(Role.ADVERTISER);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(contentFileRepository.findByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () ->
                service.linkContent(7L, 10L, "admin"));
    }

    @Test
    void linkContent_failedStatusContent_isAllowed() {
        // Edge case: linking FAILED content is intentionally allowed — grant precedes
        // file readiness, and audit history shouldn't reject based on transient state.
        var user = mock(AppUser.class);
        when(user.getId()).thenReturn(7L);
        when(user.getRole()).thenReturn(Role.ADVERTISER);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));

        var content = mock(ContentFile.class);
        when(content.getId()).thenReturn(10L);
        when(content.getStatus()).thenReturn(ContentFile.Status.FAILED);
        when(contentFileRepository.findByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.of(content));
        when(accessRepository.existsByUserIdAndContentFileId(7L, 10L)).thenReturn(false);
        when(accessRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.linkContent(7L, 10L, "admin");
        assertNotNull(result);
        verify(accessRepository).save(any(AdvertiserContentAccess.class));
    }

    @Test
    void linkContent_invalidStatusContent_isAllowed() {
        var user = mock(AppUser.class);
        when(user.getId()).thenReturn(7L);
        when(user.getRole()).thenReturn(Role.ADVERTISER);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));

        var content = mock(ContentFile.class);
        when(content.getId()).thenReturn(10L);
        when(content.getStatus()).thenReturn(ContentFile.Status.INVALID);
        when(contentFileRepository.findByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.of(content));
        when(accessRepository.existsByUserIdAndContentFileId(7L, 10L)).thenReturn(false);
        when(accessRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.linkContent(7L, 10L, "admin");
        assertNotNull(result);
    }

    @Test
    void linkContent_duplicate_throws409() {
        var user = mock(AppUser.class);
        when(user.getId()).thenReturn(7L);
        when(user.getRole()).thenReturn(Role.ADVERTISER);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));

        var content = mock(ContentFile.class);
        when(content.getId()).thenReturn(10L);
        when(content.getStatus()).thenReturn(ContentFile.Status.READY);
        when(contentFileRepository.findByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.of(content));
        when(accessRepository.existsByUserIdAndContentFileId(7L, 10L)).thenReturn(true);

        assertThrows(IllegalStateException.class, () ->
                service.linkContent(7L, 10L, "admin"));
    }

    @Test
    void unlinkContent_existing_removesGrant() {
        var access = mock(AdvertiserContentAccess.class);
        when(accessRepository.findByUserIdAndContentFileId(7L, 10L)).thenReturn(Optional.of(access));

        boolean result = service.unlinkContent(7L, 10L);

        assertTrue(result);
        verify(accessRepository).delete(access);
    }

    @Test
    void unlinkContent_missing_isIdempotent() {
        // Idempotent contract: missing grant returns false but does not throw.
        when(accessRepository.findByUserIdAndContentFileId(7L, 999L)).thenReturn(Optional.empty());

        boolean result = service.unlinkContent(7L, 999L);

        assertFalse(result);
        verify(accessRepository, never()).delete(any());
    }

    @Test
    void revokeAllForUser_returnsRowCountAndCallsBulkDelete() {
        when(accessRepository.deleteAllByUserId(7L)).thenReturn(3);
        int removed = service.revokeAllForUser(7L);
        assertEquals(3, removed);
        verify(accessRepository).deleteAllByUserId(7L);
    }
}
