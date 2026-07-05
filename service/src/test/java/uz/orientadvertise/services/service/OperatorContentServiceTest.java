package uz.orientadvertise.services.service;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.auth.Role;
import uz.orientadvertise.services.domain.model.AppUser;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.domain.model.OperatorContentAccess;
import uz.orientadvertise.services.domain.repository.AppUserRepository;
import uz.orientadvertise.services.domain.repository.ContentFileRepository;
import uz.orientadvertise.services.domain.repository.OperatorContentAccessRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Behavioral clone of {@code AdvertiserContentServiceTest}, with OPERATOR as the enforced role. */
class OperatorContentServiceTest {

    private OperatorContentAccessRepository accessRepository;
    private AppUserRepository userRepository;
    private ContentFileRepository contentFileRepository;
    private OperatorContentService service;

    @BeforeEach
    void setUp() {
        accessRepository = mock(OperatorContentAccessRepository.class);
        userRepository = mock(AppUserRepository.class);
        contentFileRepository = mock(ContentFileRepository.class);
        service = new OperatorContentService(accessRepository, userRepository, contentFileRepository);
    }

    private AppUser user(long id, Role role) {
        var u = mock(AppUser.class);
        when(u.getId()).thenReturn(id);
        when(u.getRole()).thenReturn(role);
        return u;
    }

    private ContentFile content(long id) {
        var c = mock(ContentFile.class);
        when(c.getId()).thenReturn(id);
        return c;
    }

    @Test
    void link_unknownUser_throws404() {
        when(userRepository.findById(5L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.linkContent(5L, 88L, "admin"));
    }

    @Test
    void link_nonOperatorUser_throws409() {
        var advertiser = user(5L, Role.ADVERTISER);
        when(userRepository.findById(5L)).thenReturn(Optional.of(advertiser));
        assertThrows(IllegalStateException.class, () -> service.linkContent(5L, 88L, "admin"));
        verify(contentFileRepository, never()).findByIdAndDeletedAtIsNull(any());
    }

    @Test
    void link_softDeletedOrMissingContent_throws404() {
        var op = user(5L, Role.OPERATOR);
        when(userRepository.findById(5L)).thenReturn(Optional.of(op));
        when(contentFileRepository.findByIdAndDeletedAtIsNull(88L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.linkContent(5L, 88L, "admin"));
    }

    @Test
    void link_duplicateGrant_throws409() {
        var op = user(5L, Role.OPERATOR);
        var file = content(88L);
        when(userRepository.findById(5L)).thenReturn(Optional.of(op));
        when(contentFileRepository.findByIdAndDeletedAtIsNull(88L)).thenReturn(Optional.of(file));
        when(accessRepository.existsByUserIdAndContentFileId(5L, 88L)).thenReturn(true);
        assertThrows(IllegalStateException.class, () -> service.linkContent(5L, 88L, "admin"));
    }

    @Test
    void link_success_persists() {
        var op = user(5L, Role.OPERATOR);
        var file = content(88L);
        when(userRepository.findById(5L)).thenReturn(Optional.of(op));
        when(contentFileRepository.findByIdAndDeletedAtIsNull(88L)).thenReturn(Optional.of(file));
        when(accessRepository.existsByUserIdAndContentFileId(5L, 88L)).thenReturn(false);
        when(accessRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.linkContent(5L, 88L, "admin");

        verify(accessRepository).save(any(OperatorContentAccess.class));
    }

    @Test
    void unlink_absent_returnsFalse() {
        when(accessRepository.findByUserIdAndContentFileId(5L, 88L)).thenReturn(Optional.empty());
        assertFalse(service.unlinkContent(5L, 88L));
        verify(accessRepository, never()).delete(any());
    }

    @Test
    void unlink_present_deletesAndReturnsTrue() {
        var grant = mock(OperatorContentAccess.class);
        when(accessRepository.findByUserIdAndContentFileId(5L, 88L)).thenReturn(Optional.of(grant));
        assertTrue(service.unlinkContent(5L, 88L));
        verify(accessRepository).delete(grant);
    }

    @Test
    void revokeAllForUser_delegatesToBulkDelete() {
        when(accessRepository.deleteAllByUserId(5L)).thenReturn(3);
        assertEquals(3, service.revokeAllForUser(5L));
        verify(accessRepository).deleteAllByUserId(eq(5L));
    }
}
