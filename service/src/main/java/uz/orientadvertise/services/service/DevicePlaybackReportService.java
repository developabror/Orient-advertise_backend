package uz.orientadvertise.services.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.PlaybackLogRepository;

/**
 * Playback aggregated by content, within a scope. Today the only scope is a single device;
 * the service is written so REGION / DEVICE_GROUP (etc.) are later drop-ins — see the
 * Flexible scope design. Structural inverse of {@link ContentStatsService}.
 *
 * <p>Validation mirrors {@link ContentStatsService}: window defaults to the last
 * {@value #DEFAULT_WINDOW_DAYS} days; {@code from <= to}; range capped at
 * {@value #MAX_RANGE_DAYS} days.
 */
@Service
public class DevicePlaybackReportService {

    public static final int MAX_RANGE_DAYS = 90;
    public static final int DEFAULT_WINDOW_DAYS = 7;
    private static final Duration DEFAULT_WINDOW = Duration.ofDays(DEFAULT_WINDOW_DAYS);

    private final DeviceRepository deviceRepository;
    private final PlaybackLogRepository playbackLogRepository;

    public DevicePlaybackReportService(DeviceRepository deviceRepository,
                                       PlaybackLogRepository playbackLogRepository) {
        this.deviceRepository = deviceRepository;
        this.playbackLogRepository = playbackLogRepository;
    }

    /** The scope dimension. Only DEVICE is wired now; the rest are seams (see Flexible scope design). */
    public enum ReportScopeType { DEVICE, REGION, DEVICE_GROUP, FACILITY, PROJECT }

    @Transactional(readOnly = true)
    public PlaybackReport report(ReportScopeType type, long scopeId, Instant from, Instant to,
                                 boolean callerIsOperator, Collection<Long> operatorProjectIds) {
        if (type != ReportScopeType.DEVICE) {
            // No region/group route is wired yet — there is intentionally no 501 path.
            throw new IllegalArgumentException("Unsupported report scope: " + type);
        }
        return reportForDevice(scopeId, from, to, callerIsOperator, operatorProjectIds);
    }

    private PlaybackReport reportForDevice(long deviceId, Instant from, Instant to,
                                           boolean callerIsOperator, Collection<Long> operatorProjectIds) {

        // Existence + soft-delete check FIRST — soft-deleted → 404 before the (deleted-agnostic)
        // operator guard runs. Do not reorder; see ordering invariant above.
        Device device = deviceRepository.findByIdAndDeletedAtIsNull(deviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Device", deviceId));

        // Operator device-scope guard: a device outside the caller's projects is a 404,
        // never 403 — operators get no existence oracle (matches ContentStatsService).
        if (callerIsOperator) {
            boolean emptyScope = operatorProjectIds == null || operatorProjectIds.isEmpty();
            if (emptyScope
                    || !deviceRepository.existsByIdAndProjectIdIn(deviceId, operatorProjectIds)) {
                throw new ResourceNotFoundException("Device", deviceId);
            }
        }

        Instant resolvedTo = to != null ? to : Instant.now();
        Instant resolvedFrom = from != null ? from : resolvedTo.minus(DEFAULT_WINDOW);
        if (resolvedFrom.isAfter(resolvedTo)) {
            throw new IllegalArgumentException("from must be before to");
        }
        if (Duration.between(resolvedFrom, resolvedTo).toDays() > MAX_RANGE_DAYS) {
            throw new IllegalArgumentException("Date range cannot exceed " + MAX_RANGE_DAYS + " days");
        }

        // Guard already proved the device is in scope, so device.id rows are all visible —
        // no separate operator-scoped aggregation query is needed for DEVICE scope.
        List<Object[]> rows = playbackLogRepository.aggregatePerContentForDevice(
                deviceId, resolvedFrom, resolvedTo);

        List<PerContent> perContent = rows.stream()
                .map(r -> new PerContent(
                        (Long) r[0],
                        (String) r[1],
                        ((Number) r[2]).longValue(),
                        ((Number) r[3]).longValue(),
                        ((Number) r[4]).longValue() == 0L))   // durationComplete = (missingCount == 0)
                .toList();

        // Totals reduced from the mapped rows — never re-queried — so they always reconcile.
        long totalPlayCount = perContent.stream().mapToLong(PerContent::playCount).sum();
        long totalDurationSeconds = perContent.stream().mapToLong(PerContent::totalDurationSeconds).sum();
        boolean durationComplete = perContent.stream().allMatch(PerContent::durationComplete); // true for []

        Scope scope = new Scope(ReportScopeType.DEVICE.name(), device.getId(), device.getName());
        return new PlaybackReport(scope, resolvedFrom, resolvedTo,
                totalPlayCount, totalDurationSeconds, durationComplete, perContent);
    }

    /** Scope echo: {@code { type, id, name }}. Today {@code type} is always "DEVICE". */
    public record Scope(String type, long id, String name) {}

    public record PerContent(Long contentFileId, String contentFileName,
                             long playCount, long totalDurationSeconds, boolean durationComplete) {}

    public record PlaybackReport(Scope scope, Instant from, Instant to,
                                 long totalPlayCount, long totalDurationSeconds,
                                 boolean durationComplete, List<PerContent> perContent) {}
}
