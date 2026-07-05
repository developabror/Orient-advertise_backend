package uz.orientadvertise.services.api.controller;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.orientadvertise.services.service.ContentStatsService;
import uz.orientadvertise.services.service.ContentStatsService.ContentStats;
import uz.orientadvertise.services.service.ContentStatsService.DeviceCount;
import uz.orientadvertise.services.service.DevicePlaybackReportService;
import uz.orientadvertise.services.service.OperatorScopeResolver;
import uz.orientadvertise.services.service.OperatorScopeResolver.ScopedProjects;

/**
 * Aggregated stats endpoints. Currently exposes per-content playback statistics; the
 * route lives under {@code /service/stats} so future per-device, per-region, or per-time
 * aggregations can join the same controller cleanly.
 */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final ContentStatsService contentStatsService;
    private final DevicePlaybackReportService devicePlaybackReportService;
    private final OperatorScopeResolver operatorScopeResolver;

    public StatsController(ContentStatsService contentStatsService,
                           DevicePlaybackReportService devicePlaybackReportService,
                           OperatorScopeResolver operatorScopeResolver) {
        this.contentStatsService = contentStatsService;
        this.devicePlaybackReportService = devicePlaybackReportService;
        this.operatorScopeResolver = operatorScopeResolver;
    }

    @GetMapping("/content/{contentFileId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR', 'VIEWER', 'ADVERTISER')")
    public ResponseEntity<ContentStatsResponse> getContentStats(
            @PathVariable Long contentFileId,
            @RequestParam(required = false) Long deviceId,
            @RequestParam(required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            Pageable pageable) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        boolean isAdvertiser = auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADVERTISER".equals(a.getAuthority()));
        String username = auth != null ? auth.getName() : null;
        boolean operatorOnly = CallerRoles.isOperatorOnly(auth);
        // For an operator-only caller, the device dimension is intersected with their projects.
        Collection<Long> operatorProjectIds = null;
        if (operatorOnly) {
            ScopedProjects scope = operatorScopeResolver.resolve();
            operatorProjectIds = scope.restricted() ? scope.projectIds() : List.of();
        }

        ContentStats stats = contentStatsService.getStats(
                contentFileId, deviceId, from, to, pageable, username, isAdvertiser,
                operatorOnly, operatorProjectIds);
        return ResponseEntity.ok(ContentStatsResponse.from(stats));
    }

    /**
     * Per-device playback report: which content played on this device, how many times, and total
     * duration (seconds) over a window. Device-scoped inverse of {@link #getContentStats}. ADVERTISER
     * is intentionally excluded — advertiser scoping is content-keyed (via /api/stats/content/*).
     * VIEWER is allowed (read-only).
     */
    @io.swagger.v3.oas.annotations.Operation(
            summary = "Per-device playback report (by content)",
            description = "Aggregates playback for one device over a window: per content file, "
                    + "the play count and total duration in seconds. scope is an object "
                    + "{ type, id, name } (type currently always \"DEVICE\"); the wire carries "
                    + "integer seconds only (the frontend formats H:MM:SS). VIEWER allowed; "
                    + "ADVERTISER → 403; unknown / soft-deleted / out-of-operator-scope device → 404.")
    @GetMapping("/device/{deviceId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR', 'VIEWER')")
    public ResponseEntity<PlaybackReportResponse> getDeviceReport(
            @PathVariable Long deviceId,
            @RequestParam(required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        boolean operatorOnly = CallerRoles.isOperatorOnly(auth);
        Collection<Long> operatorProjectIds = null;
        if (operatorOnly) {
            ScopedProjects scope = operatorScopeResolver.resolve();
            operatorProjectIds = scope.restricted() ? scope.projectIds() : List.of();
        }
        var report = devicePlaybackReportService.report(
                DevicePlaybackReportService.ReportScopeType.DEVICE,
                deviceId, from, to, operatorOnly, operatorProjectIds);
        return ResponseEntity.ok(PlaybackReportResponse.from(report));
    }

    public record PlaybackReportResponse(
            ScopeDto scope,
            Instant from,
            Instant to,
            long totalPlayCount,
            long totalDurationSeconds,
            boolean durationComplete,
            List<PerContentDto> perContent) {

        public static PlaybackReportResponse from(DevicePlaybackReportService.PlaybackReport r) {
            return new PlaybackReportResponse(
                    ScopeDto.from(r.scope()), r.from(), r.to(),
                    r.totalPlayCount(), r.totalDurationSeconds(), r.durationComplete(),
                    r.perContent().stream().map(PerContentDto::from).toList());
        }
    }

    public record ScopeDto(String type, long id, String name) {
        public static ScopeDto from(DevicePlaybackReportService.Scope s) {
            return new ScopeDto(s.type(), s.id(), s.name());
        }
    }

    public record PerContentDto(Long contentFileId, String contentFileName,
                                long playCount, long totalDurationSeconds, boolean durationComplete) {
        public static PerContentDto from(DevicePlaybackReportService.PerContent c) {
            return new PerContentDto(c.contentFileId(), c.contentFileName(),
                    c.playCount(), c.totalDurationSeconds(), c.durationComplete());
        }
    }

    public record ContentStatsResponse(
            Long contentFileId,
            String contentFileName,
            Instant from,
            Instant to,
            long totalPlayCount,
            List<DeviceCountDto> perDevice,
            boolean timestampsIncluded,
            TimestampsPage timestamps) {

        public static ContentStatsResponse from(ContentStats s) {
            return new ContentStatsResponse(
                    s.contentFileId(),
                    s.contentFileName(),
                    s.from(),
                    s.to(),
                    s.totalPlayCount(),
                    s.perDevice().stream().map(DeviceCountDto::from).toList(),
                    s.timestampsIncluded(),
                    s.timestampsIncluded() ? TimestampsPage.from(s.timestamps()) : null);
        }
    }

    public record DeviceCountDto(Long deviceId, String deviceName, long playCount) {
        public static DeviceCountDto from(DeviceCount d) {
            return new DeviceCountDto(d.deviceId(), d.deviceName(), d.playCount());
        }
    }

    public record TimestampsPage(List<Instant> content, int page, int size,
                                  long totalElements, int totalPages) {
        public static TimestampsPage from(Page<Instant> p) {
            return new TimestampsPage(p.getContent(), p.getNumber(), p.getSize(),
                    p.getTotalElements(), p.getTotalPages());
        }
    }
}
