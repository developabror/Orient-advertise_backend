package uz.orientadvertise.services.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import uz.orientadvertise.services.common.exception.AccessForbiddenException;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.model.AppUser;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.domain.repository.AdvertiserContentAccessRepository;
import uz.orientadvertise.services.domain.repository.AppUserRepository;
import uz.orientadvertise.services.domain.repository.ContentFileRepository;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.OperatorContentAccessRepository;
import uz.orientadvertise.services.domain.repository.PlaybackLogRepository;

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

class ContentStatsServiceTest {

    private ContentFileRepository contentFileRepository;
    private PlaybackLogRepository playbackLogRepository;
    private AdvertiserContentAccessRepository advertiserAccessRepository;
    private OperatorContentAccessRepository operatorAccessRepository;
    private AppUserRepository userRepository;
    private DeviceRepository deviceRepository;
    private ContentStatsService service;

    @BeforeEach
    void setUp() {
        contentFileRepository = mock(ContentFileRepository.class);
        playbackLogRepository = mock(PlaybackLogRepository.class);
        advertiserAccessRepository = mock(AdvertiserContentAccessRepository.class);
        operatorAccessRepository = mock(OperatorContentAccessRepository.class);
        userRepository = mock(AppUserRepository.class);
        deviceRepository = mock(DeviceRepository.class);
        service = new ContentStatsService(contentFileRepository, playbackLogRepository,
                advertiserAccessRepository, operatorAccessRepository, userRepository, deviceRepository);

        var content = mock(ContentFile.class);
        when(content.getId()).thenReturn(10L);
        when(content.getName()).thenReturn("ad.mp4");
        when(content.getDeletedAt()).thenReturn(null);
        when(contentFileRepository.findById(10L)).thenReturn(Optional.of(content));

        when(playbackLogRepository.countByContentInRange(any(), any(), any(), any()))
                .thenReturn(42L);
        when(playbackLogRepository.countPerDeviceForContent(any(), any(), any(), any()))
                .thenReturn(List.of());
        when(playbackLogRepository.findTimestampsForContent(any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));
    }

    @Test
    void unknownContentFile_throws404() {
        when(contentFileRepository.findById(999L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () ->
                service.getStats(999L, null, null, null, PageRequest.of(0, 20), "alice", false, false, null));
    }

    @Test
    void deletedContentFile_throws404() {
        var content = mock(ContentFile.class);
        when(content.getDeletedAt()).thenReturn(Instant.now());
        when(contentFileRepository.findById(11L)).thenReturn(Optional.of(content));

        assertThrows(ResourceNotFoundException.class, () ->
                service.getStats(11L, null, null, null, PageRequest.of(0, 20), "alice", false, false, null));
    }

    @Test
    void admin_alwaysAllowed() {
        var stats = service.getStats(10L, null, null, null,
                PageRequest.of(0, 20), "admin", false, false, null);
        assertEquals(42, stats.totalPlayCount());
        verify(advertiserAccessRepository, never()).existsByUserIdAndContentFileId(any(), any());
    }

    @Test
    void advertiser_withGrant_isAllowed() {
        var user = mock(AppUser.class);
        when(user.getId()).thenReturn(50L);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(advertiserAccessRepository.existsByUserIdAndContentFileId(50L, 10L)).thenReturn(true);

        var stats = service.getStats(10L, null, null, null,
                PageRequest.of(0, 20), "alice", true, false, null);
        assertEquals(42, stats.totalPlayCount());
    }

    @Test
    void advertiser_withoutGrant_throws403() {
        var user = mock(AppUser.class);
        when(user.getId()).thenReturn(51L);
        when(userRepository.findByUsername("bob")).thenReturn(Optional.of(user));
        when(advertiserAccessRepository.existsByUserIdAndContentFileId(51L, 10L)).thenReturn(false);

        assertThrows(AccessForbiddenException.class, () ->
                service.getStats(10L, null, null, null,
                        PageRequest.of(0, 20), "bob", true, false, null));
    }

    @Test
    void advertiser_unknownUser_throws403() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());
        assertThrows(AccessForbiddenException.class, () ->
                service.getStats(10L, null, null, null,
                        PageRequest.of(0, 20), "ghost", true, false, null));
    }

    @Test
    void rangeOver30Days_omitsTimestamps() {
        // Edge case requirement: range > 30 days = counts only.
        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofDays(60));

        var stats = service.getStats(10L, null, from, to,
                PageRequest.of(0, 20), "admin", false, false, null);

        assertFalse(stats.timestampsIncluded());
        assertEquals(0, stats.timestamps().getNumberOfElements());
        verify(playbackLogRepository, never()).findTimestampsForContent(any(), any(), any(), any(), any());
    }

    @Test
    void rangeAt30Days_includesTimestamps() {
        // Boundary: exactly 30 days returns timestamps.
        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofDays(30));

        var stats = service.getStats(10L, null, from, to,
                PageRequest.of(0, 20), "admin", false, false, null);

        assertTrue(stats.timestampsIncluded());
        verify(playbackLogRepository).findTimestampsForContent(any(), any(), any(), any(), any());
    }

    @Test
    void rangeOver90Days_throws400() {
        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofDays(91));
        var ex = assertThrows(IllegalArgumentException.class, () ->
                service.getStats(10L, null, from, to,
                        PageRequest.of(0, 20), "admin", false, false, null));
        assertTrue(ex.getMessage().contains("90"));
    }

    @Test
    void fromAfterTo_throws400() {
        Instant to = Instant.now().minus(Duration.ofDays(1));
        Instant from = Instant.now();
        assertThrows(IllegalArgumentException.class, () ->
                service.getStats(10L, null, from, to,
                        PageRequest.of(0, 20), "admin", false, false, null));
    }

    @Test
    void pageSizeOver100_throws400() {
        var ex = assertThrows(IllegalArgumentException.class, () ->
                service.getStats(10L, null, null, null,
                        PageRequest.of(0, 101), "admin", false, false, null));
        assertTrue(ex.getMessage().contains("100"));
    }

    @Test
    void perDevice_aggregatesFromObjectArrayRows() {
        when(playbackLogRepository.countPerDeviceForContent(eq(10L), any(), any(), any()))
                .thenReturn(List.of(
                        new Object[]{1L, "TV-1", 10L},
                        new Object[]{2L, "TV-2", 5L}));

        var stats = service.getStats(10L, null, null, null,
                PageRequest.of(0, 20), "admin", false, false, null);

        assertEquals(2, stats.perDevice().size());
        assertEquals(1L, stats.perDevice().get(0).deviceId());
        assertEquals("TV-1", stats.perDevice().get(0).deviceName());
        assertEquals(10L, stats.perDevice().get(0).playCount());
    }

    @Test
    void noBounds_defaultsTo7DayWindow() {
        Instant before = Instant.now();
        service.getStats(10L, null, null, null, PageRequest.of(0, 20), "admin", false, false, null);
        Instant after = Instant.now();

        var fromCap = org.mockito.ArgumentCaptor.forClass(Instant.class);
        var toCap = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(playbackLogRepository).countByContentInRange(any(), any(),
                fromCap.capture(), toCap.capture());

        long days = Duration.between(fromCap.getValue(), toCap.getValue()).toDays();
        assertEquals(7, days, "default window is 7 days");
        assertTrue(!toCap.getValue().isBefore(before) && !toCap.getValue().isAfter(after));
    }
}
