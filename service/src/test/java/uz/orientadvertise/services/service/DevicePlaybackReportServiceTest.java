package uz.orientadvertise.services.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.PlaybackLogRepository;
import uz.orientadvertise.services.service.DevicePlaybackReportService.ReportScopeType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DevicePlaybackReportServiceTest {

    private static final long DEVICE_ID = 42L;
    private static final Instant FROM = Instant.parse("2026-06-17T10:00:00Z");
    private static final Instant TO = Instant.parse("2026-06-24T10:00:00Z");

    private DeviceRepository deviceRepository;
    private PlaybackLogRepository playbackLogRepository;
    private DevicePlaybackReportService service;

    @BeforeEach
    void setUp() {
        deviceRepository = mock(DeviceRepository.class);
        playbackLogRepository = mock(PlaybackLogRepository.class);
        service = new DevicePlaybackReportService(deviceRepository, playbackLogRepository);

        var device = mock(Device.class);
        when(device.getId()).thenReturn(DEVICE_ID);
        when(device.getName()).thenReturn("Lobby TV-1");
        when(deviceRepository.findByIdAndDeletedAtIsNull(DEVICE_ID)).thenReturn(Optional.of(device));
        when(playbackLogRepository.aggregatePerContentForDevice(any(), any(), any())).thenReturn(List.of());
    }

    /** Row shape: {contentFileId, contentFileName, playCount, totalDurationSeconds, missingDurationCount}. */
    private Object[] row(long contentId, String name, long count, long durationSum, long missing) {
        return new Object[]{contentId, name, count, durationSum, missing};
    }

    private DevicePlaybackReportService.PlaybackReport reportDevice() {
        return service.report(ReportScopeType.DEVICE, DEVICE_ID, FROM, TO, false, null);
    }

    @Test
    void report_aggregatesAndSumsTotals() {
        when(playbackLogRepository.aggregatePerContentForDevice(DEVICE_ID, FROM, TO)).thenReturn(List.of(
                row(7L, "Summer Promo 30s", 420L, 12600L, 0L),
                row(9L, "Store Hours", 300L, 9000L, 0L)));

        var report = reportDevice();

        assertEquals("DEVICE", report.scope().type());
        assertEquals(DEVICE_ID, report.scope().id());
        assertEquals("Lobby TV-1", report.scope().name());
        assertEquals(2, report.perContent().size());
        assertEquals(7L, report.perContent().get(0).contentFileId());
        assertEquals("Summer Promo 30s", report.perContent().get(0).contentFileName());
        assertEquals(420L, report.perContent().get(0).playCount());
        assertEquals(12600L, report.perContent().get(0).totalDurationSeconds());
        // Totals reduced from rows.
        assertEquals(720L, report.totalPlayCount());
        assertEquals(21600L, report.totalDurationSeconds());
        assertTrue(report.durationComplete());
    }

    @Test
    void report_fallsBackToCatalogDuration_durationCompleteTrue() {
        // Catalog fallback happened in SQL; service sees missingCount == 0 → durationComplete true.
        when(playbackLogRepository.aggregatePerContentForDevice(DEVICE_ID, FROM, TO))
                .thenReturn(List.<Object[]>of(row(7L, "A", 5L, 250L, 0L)));

        var report = reportDevice();

        assertTrue(report.perContent().get(0).durationComplete());
        assertTrue(report.durationComplete());
    }

    @Test
    void report_bothDurationSourcesNull_durationCompleteFalse() {
        when(playbackLogRepository.aggregatePerContentForDevice(DEVICE_ID, FROM, TO))
                .thenReturn(List.<Object[]>of(row(9L, "Store Hours", 300L, 0L, 300L)));

        var report = reportDevice();

        assertFalse(report.perContent().get(0).durationComplete());
        assertFalse(report.durationComplete());
        assertEquals(0L, report.totalDurationSeconds()); // lower bound
    }

    @Test
    void report_defaultsWindowToLast7Days() {
        service.report(ReportScopeType.DEVICE, DEVICE_ID, null, null, false, null);

        ArgumentCaptor<Instant> fromCap = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> toCap = ArgumentCaptor.forClass(Instant.class);
        verify(playbackLogRepository).aggregatePerContentForDevice(eq(DEVICE_ID), fromCap.capture(), toCap.capture());

        Instant now = Instant.now();
        assertTrue(Duration.between(toCap.getValue(), now).abs().getSeconds() < 5, "to ≈ now");
        assertTrue(Duration.between(fromCap.getValue(), now.minus(Duration.ofDays(7))).abs().getSeconds() < 5,
                "from ≈ now − 7d");
    }

    @Test
    void report_fromAfterTo_throwsIllegalArgument() {
        var ex = assertThrows(IllegalArgumentException.class, () ->
                service.report(ReportScopeType.DEVICE, DEVICE_ID, TO, FROM, false, null));
        assertTrue(ex.getMessage().contains("from"));
    }

    @Test
    void report_rangeOver90Days_throwsIllegalArgument() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-06-01T00:00:00Z"); // > 90 days
        var ex = assertThrows(IllegalArgumentException.class, () ->
                service.report(ReportScopeType.DEVICE, DEVICE_ID, from, to, false, null));
        assertTrue(ex.getMessage().contains("90"));
    }

    @Test
    void report_unknownDevice_throwsResourceNotFound() {
        when(deviceRepository.findByIdAndDeletedAtIsNull(DEVICE_ID)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> reportDevice());
    }

    @Test
    void report_operatorInScope_runsAggregation() {
        when(deviceRepository.existsByIdAndProjectIdIn(eq(DEVICE_ID), any())).thenReturn(true);
        when(playbackLogRepository.aggregatePerContentForDevice(DEVICE_ID, FROM, TO))
                .thenReturn(List.<Object[]>of(row(7L, "A", 3L, 90L, 0L)));

        var report = service.report(ReportScopeType.DEVICE, DEVICE_ID, FROM, TO, true, List.of(1L, 2L));

        assertEquals(3L, report.totalPlayCount());
        verify(playbackLogRepository).aggregatePerContentForDevice(DEVICE_ID, FROM, TO);
    }

    @Test
    void report_operatorEmptyScope_throws404() {
        assertThrows(ResourceNotFoundException.class, () ->
                service.report(ReportScopeType.DEVICE, DEVICE_ID, FROM, TO, true, List.of()));
        // Empty-scope short-circuits before the aggregation runs.
        verify(playbackLogRepository, never()).aggregatePerContentForDevice(any(), any(), any());
    }

    @Test
    void report_operatorDeviceOutOfScope_throws404() {
        when(deviceRepository.existsByIdAndProjectIdIn(eq(DEVICE_ID), any())).thenReturn(false);
        assertThrows(ResourceNotFoundException.class, () ->
                service.report(ReportScopeType.DEVICE, DEVICE_ID, FROM, TO, true, List.of(1L, 2L)));
        verify(playbackLogRepository, never()).aggregatePerContentForDevice(any(), any(), any());
    }

    @Test
    void report_softDeletedDeviceCheckedBeforeGuard() {
        // Operator caller, but the device is soft-deleted → 404 from findByIdAndDeletedAtIsNull,
        // and the deleted-agnostic operator guard must NEVER run (ordering invariant).
        when(deviceRepository.findByIdAndDeletedAtIsNull(DEVICE_ID)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () ->
                service.report(ReportScopeType.DEVICE, DEVICE_ID, FROM, TO, true, List.of(1L, 2L)));

        verify(deviceRepository, never()).existsByIdAndProjectIdIn(anyLong(), any());
    }

    @Test
    void report_emptyResult_returnsZeroTotalsDurationCompleteTrue() {
        // @BeforeEach already stubs an empty aggregation.
        var report = reportDevice();
        assertEquals(0L, report.totalPlayCount());
        assertEquals(0L, report.totalDurationSeconds());
        assertTrue(report.durationComplete());
        assertTrue(report.perContent().isEmpty());
    }

    @Test
    void report_totalsReconcileWithRows() {
        when(playbackLogRepository.aggregatePerContentForDevice(DEVICE_ID, FROM, TO)).thenReturn(List.of(
                row(7L, "A", 420L, 12600L, 0L),
                row(9L, "B", 300L, 0L, 300L),
                row(3L, "C", 100L, 4000L, 0L)));

        var report = reportDevice();

        long sumCount = report.perContent().stream().mapToLong(p -> p.playCount()).sum();
        long sumDuration = report.perContent().stream().mapToLong(p -> p.totalDurationSeconds()).sum();
        assertEquals(sumCount, report.totalPlayCount());
        assertEquals(sumDuration, report.totalDurationSeconds());
        assertEquals(820L, report.totalPlayCount());
        assertEquals(16600L, report.totalDurationSeconds());
        assertFalse(report.durationComplete()); // one row had missing duration
    }
}
