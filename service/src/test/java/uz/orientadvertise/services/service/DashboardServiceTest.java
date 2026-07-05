package uz.orientadvertise.services.service;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.Event;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.DeviceStatusViewRepository;
import uz.orientadvertise.services.domain.repository.IncidentRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DashboardServiceTest {

    private static final OperatorScopeResolver.ScopedProjects UNRESTRICTED =
            new OperatorScopeResolver.ScopedProjects(null, null, null, false);

    private DeviceRepository deviceRepository;
    private DeviceStatusViewRepository statusViewRepository;
    private IncidentRepository incidentRepository;
    private DashboardService service;

    @BeforeEach
    void setUp() {
        deviceRepository = mock(DeviceRepository.class);
        statusViewRepository = mock(DeviceStatusViewRepository.class);
        incidentRepository = mock(IncidentRepository.class);
        service = new DashboardService(deviceRepository, statusViewRepository, incidentRepository);
    }

    @Test
    void everythingEmpty_returnsZeroFilledStructure() {
        when(statusViewRepository.countByComputedStatusGrouped()).thenReturn(List.of());
        when(statusViewRepository.countByRegionAndComputedStatus()).thenReturn(List.of());
        when(deviceRepository.countDevicesPerRegion()).thenReturn(List.of());
        when(incidentRepository.countOpenByPriority()).thenReturn(List.of());

        var summary = service.getSummary(UNRESTRICTED);

        assertEquals(0L, summary.totalDevices());
        assertEquals(0L, summary.onlineCount());
        assertEquals(0L, summary.offlineCount());
        assertEquals(0L, summary.noContentCount());
        assertEquals(0L, summary.openIncidents().critical());
        assertEquals(0L, summary.openIncidents().warning());
        assertTrue(summary.regionSummary().isEmpty());
    }

    @Test
    void statusCounts_populated_fromComputedViewRows() {
        // Counts come from the heartbeat-derived view — a never-heartbeated device the view
        // computes as OFFLINE is reflected here, not the phantom-ONLINE raw column.
        when(statusViewRepository.countByComputedStatusGrouped()).thenReturn(List.<Object[]>of(
                new Object[]{Device.Status.ONLINE, 8L},
                new Object[]{Device.Status.OFFLINE, 3L},
                new Object[]{Device.Status.NO_CONTENT, 1L}
        ));
        when(deviceRepository.countDevicesPerRegion()).thenReturn(List.of());
        when(incidentRepository.countOpenByPriority()).thenReturn(List.of());

        var summary = service.getSummary(UNRESTRICTED);

        assertEquals(12L, summary.totalDevices());
        assertEquals(8L, summary.onlineCount());
        assertEquals(3L, summary.offlineCount());
        assertEquals(1L, summary.noContentCount());
    }

    @Test
    void statusCounts_partialBuckets_zeroFillTheRest() {
        when(statusViewRepository.countByComputedStatusGrouped()).thenReturn(List.<Object[]>of(
                new Object[]{Device.Status.ONLINE, 5L}
        ));
        when(deviceRepository.countDevicesPerRegion()).thenReturn(List.of());
        when(incidentRepository.countOpenByPriority()).thenReturn(List.of());

        var summary = service.getSummary(UNRESTRICTED);

        assertEquals(5L, summary.totalDevices());
        assertEquals(5L, summary.onlineCount());
        assertEquals(0L, summary.offlineCount());
        assertEquals(0L, summary.noContentCount());
    }

    @Test
    void openIncidents_splitByCriticalAndWarning() {
        when(statusViewRepository.countByComputedStatusGrouped()).thenReturn(List.of());
        when(deviceRepository.countDevicesPerRegion()).thenReturn(List.of());
        when(incidentRepository.countOpenByPriority()).thenReturn(List.<Object[]>of(
                new Object[]{Event.Priority.CRITICAL, 2L},
                new Object[]{Event.Priority.HIGH, 5L},
                new Object[]{Event.Priority.MEDIUM, 7L} // ignored — not surfaced on dashboard
        ));

        var summary = service.getSummary(UNRESTRICTED);

        assertEquals(2L, summary.openIncidents().critical());
        assertEquals(5L, summary.openIncidents().warning(),
                "WARNING bucket maps to Event.Priority.HIGH");
    }

    @Test
    void openIncidents_onlyCriticalPresent_warningZero() {
        when(statusViewRepository.countByComputedStatusGrouped()).thenReturn(List.of());
        when(deviceRepository.countDevicesPerRegion()).thenReturn(List.of());
        when(incidentRepository.countOpenByPriority()).thenReturn(List.<Object[]>of(
                new Object[]{Event.Priority.CRITICAL, 1L}));

        var summary = service.getSummary(UNRESTRICTED);

        assertEquals(1L, summary.openIncidents().critical());
        assertEquals(0L, summary.openIncidents().warning());
    }

    @Test
    void regionSummary_zeroDeviceRegion_appearsWithZeroCounts() {
        // Region names + zero-device regions come from the device aggregate (LEFT JOIN);
        // per-region ONLINE comes from the computed view. A region with no ONLINE row → 0.
        when(statusViewRepository.countByComputedStatusGrouped()).thenReturn(List.of());
        when(incidentRepository.countOpenByPriority()).thenReturn(List.of());
        when(deviceRepository.countDevicesPerRegion()).thenReturn(List.<Object[]>of(
                new Object[]{1L, "Karachi", 5L},
                new Object[]{2L, "Quetta", 0L}
        ));
        when(statusViewRepository.countByRegionAndComputedStatus()).thenReturn(List.<Object[]>of(
                new Object[]{1L, Device.Status.ONLINE, 3L},
                new Object[]{1L, Device.Status.OFFLINE, 2L} // not counted as online
        ));

        var summary = service.getSummary(UNRESTRICTED);

        assertEquals(2, summary.regionSummary().size());
        assertEquals("Karachi", summary.regionSummary().get(0).regionName());
        assertEquals(5L, summary.regionSummary().get(0).totalCount());
        assertEquals(3L, summary.regionSummary().get(0).onlineCount());
        assertEquals("Quetta", summary.regionSummary().get(1).regionName());
        assertEquals(0L, summary.regionSummary().get(1).totalCount());
        assertEquals(0L, summary.regionSummary().get(1).onlineCount(),
                "region with no ONLINE row in the view → 0");
    }

    @Test
    void regionSummary_orderingPreservedFromRepository() {
        when(statusViewRepository.countByComputedStatusGrouped()).thenReturn(List.of());
        when(incidentRepository.countOpenByPriority()).thenReturn(List.of());
        when(deviceRepository.countDevicesPerRegion()).thenReturn(List.<Object[]>of(
                new Object[]{1L, "Aaaa", 1L},
                new Object[]{2L, "Mmmm", 2L},
                new Object[]{3L, "Zzzz", 3L}
        ));
        when(statusViewRepository.countByRegionAndComputedStatus()).thenReturn(List.<Object[]>of(
                new Object[]{1L, Device.Status.ONLINE, 1L},
                new Object[]{2L, Device.Status.ONLINE, 1L}
        ));

        var summary = service.getSummary(UNRESTRICTED);

        assertEquals("Aaaa", summary.regionSummary().get(0).regionName());
        assertEquals("Mmmm", summary.regionSummary().get(1).regionName());
        assertEquals("Zzzz", summary.regionSummary().get(2).regionName());
        assertEquals(0L, summary.regionSummary().get(2).onlineCount(),
                "Zzzz has no ONLINE row → 0");
    }

    @Test
    void invalidate_doesNotThrow_whenCacheNotConfigured() {
        // Guards against the eviction call on the health-check path failing when the
        // unit test runs without a real Spring proxy (no @CacheEvict advice). Plain
        // method call must be a no-op.
        service.invalidate();
    }
}
