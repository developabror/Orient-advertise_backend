package uz.orientadvertise.services.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.Event;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.DeviceStatusViewRepository;
import uz.orientadvertise.services.domain.repository.IncidentRepository;
import uz.orientadvertise.services.service.OperatorScopeResolver.ScopedProjects;

/**
 * Single-shot dashboard aggregation backing {@code GET /service/dashboard/summary} (FE-11/FE-12).
 *
 * <p>The whole shape is computed by three lightweight aggregation queries — total
 * complexity is O(rows-with-distinct-statuses) for the first, O(regions) for the second,
 * O(distinct-priorities) for the third. The total is bounded by the product cardinalities,
 * not the device count, so it stays cheap even at scale.
 *
 * <p><b>Caching.</b> The result is wrapped in {@code @Cacheable("dashboard")} with a 30-second
 * TTL configured in {@code RedisConfig}. The cache key is fixed (one tenant) so the entry
 * shares across all callers. {@link #invalidate()} is exposed for the
 * {@link DeviceHealthMonitor} health-check job to call after a status sweep, so dashboard
 * counts catch up to the latest scan rather than waiting for the TTL to elapse — this is
 * the "pre-compute via the existing incident detection job" path mentioned in the spec.
 *
 * <p><b>Stable shape contracts:</b>
 * <ul>
 *   <li>Every {@link Device.Status} bucket appears in the response, defaulting to 0 when no
 *       device is in that state.</li>
 *   <li>Both CRITICAL and WARNING (mapped from {@link Event.Priority#HIGH}) incident
 *       priorities appear in {@code openIncidents}, defaulting to 0 when no incident is
 *       open at that priority.</li>
 *   <li>Every region appears in {@code regionSummary}, even when it has zero registered
 *       devices — the LEFT JOIN guarantees this on the SQL side.</li>
 * </ul>
 */
@Service
public class DashboardService {

    public static final String CACHE_NAME = "dashboard";
    /** Per-username for operators ({@code summary:<user>}), one shared key otherwise ({@code summary:_global}). */
    public static final String CACHE_KEY = "#scope.cacheKey()";

    private static final Logger log = LoggerFactory.getLogger(DashboardService.class);

    private final DeviceRepository deviceRepository;
    private final DeviceStatusViewRepository statusViewRepository;
    private final IncidentRepository incidentRepository;

    public DashboardService(DeviceRepository deviceRepository,
                              DeviceStatusViewRepository statusViewRepository,
                              IncidentRepository incidentRepository) {
        this.deviceRepository = deviceRepository;
        this.statusViewRepository = statusViewRepository;
        this.incidentRepository = incidentRepository;
    }

    @Cacheable(value = CACHE_NAME, key = CACHE_KEY)
    @Transactional(readOnly = true)
    public DashboardSummary getSummary(ScopedProjects scope) {
        boolean restricted = scope.restricted();
        if (restricted && scope.projectIds().isEmpty()) {
            // operator with zero projects ⇒ a per-username EMPTY entry (cache hit path),
            // never a fall-through to the global admin compute.
            return new DashboardSummary(0L, 0L, 0L, 0L, new OpenIncidentCounts(0L, 0L), List.of());
        }
        Collection<Long> pids = restricted ? scope.projectIds() : null;   // null = unrestricted

        // Status buckets — zero-filled so the response shape is stable.
        Map<Device.Status, Long> statusCounts = new EnumMap<>(Device.Status.class);
        for (Device.Status s : Device.Status.values()) {
            statusCounts.put(s, 0L);
        }
        long total = 0L;
        // Counts come from the heartbeat-derived device_status_view (single source of truth),
        // not the raw, non-authoritative status column. The view excludes soft-deleted rows,
        // so the total matches the prior raw count. The view is 3-valued (ON/OFFLINE/NO_CONTENT)
        // — UNREGISTERED stays zero-filled.
        var statusRows = restricted
                ? statusViewRepository.countByComputedStatusGroupedScoped(pids)
                : statusViewRepository.countByComputedStatusGrouped();
        for (Object[] row : statusRows) {
            Device.Status s = (Device.Status) row[0];
            long count = ((Number) row[1]).longValue();
            statusCounts.put(s, count);
            total += count;
        }

        // Open incidents grouped by priority. The dashboard only surfaces CRITICAL +
        // WARNING — WARNING maps to Event.Priority.HIGH, since incidents are opened
        // from events with HIGH severity (CRITICAL stays as CRITICAL).
        Map<Event.Priority, Long> priorityCounts = new EnumMap<>(Event.Priority.class);
        for (Event.Priority p : Event.Priority.values()) {
            priorityCounts.put(p, 0L);
        }
        var priorityRows = restricted
                ? incidentRepository.countOpenByPriorityScoped(pids)
                : incidentRepository.countOpenByPriority();
        for (Object[] row : priorityRows) {
            Event.Priority p = (Event.Priority) row[0];
            priorityCounts.put(p, ((Number) row[1]).longValue());
        }
        OpenIncidentCounts openIncidents = new OpenIncidentCounts(
                priorityCounts.getOrDefault(Event.Priority.CRITICAL, 0L),
                priorityCounts.getOrDefault(Event.Priority.HIGH, 0L));

        // Per-region: the LEFT JOIN guarantees a row per region, including those with
        // zero registered devices. Count columns can come back null for empty regions
        // (LEFT JOIN with a SUM/COUNT over the missing side); coerce to 0 so the
        // response never carries nulls.
        // Per-region ONLINE counts also derive from the view; region names + zero-device
        // regions still come from the device aggregate's LEFT JOIN so empty regions remain.
        Map<Long, Long> onlineByRegion = new HashMap<>();
        var regionStatusRows = restricted
                ? statusViewRepository.countByRegionAndComputedStatusScoped(pids)
                : statusViewRepository.countByRegionAndComputedStatus();
        for (Object[] row : regionStatusRows) {
            if (row[0] != null && row[1] == Device.Status.ONLINE) {
                onlineByRegion.merge((Long) row[0], ((Number) row[2]).longValue(), Long::sum);
            }
        }
        List<RegionSummary> regions = new ArrayList<>();
        var perRegionRows = restricted
                ? deviceRepository.countDevicesPerRegionScoped(pids)
                : deviceRepository.countDevicesPerRegion();
        for (Object[] row : perRegionRows) {
            Long regionId = (Long) row[0];
            String regionName = (String) row[1];
            long totalCount = row[2] == null ? 0L : ((Number) row[2]).longValue();
            long onlineCount = onlineByRegion.getOrDefault(regionId, 0L);
            regions.add(new RegionSummary(regionId, regionName, onlineCount, totalCount));
        }

        return new DashboardSummary(
                total,
                statusCounts.getOrDefault(Device.Status.ONLINE, 0L),
                statusCounts.getOrDefault(Device.Status.OFFLINE, 0L),
                statusCounts.getOrDefault(Device.Status.NO_CONTENT, 0L),
                openIncidents,
                regions);
    }

    /**
     * Drop the cached summary. Called by the device-health Quartz job after each scan
     * so dashboard counts catch up to a status flip without waiting 30s for TTL expiry.
     */
    @CacheEvict(value = CACHE_NAME, allEntries = true)
    public void invalidate() {
        // allEntries: with per-username operator keys now in the cache, evict the whole
        // region so a status sweep refreshes operator dashboards too (not just the global one).
        log.debug("Dashboard cache invalidated (all entries)");
    }

    public record DashboardSummary(
            long totalDevices,
            long onlineCount,
            long offlineCount,
            long noContentCount,
            OpenIncidentCounts openIncidents,
            List<RegionSummary> regionSummary) {}

    public record OpenIncidentCounts(long critical, long warning) {}

    public record RegionSummary(Long regionId, String regionName,
                                  long onlineCount, long totalCount) {}
}
