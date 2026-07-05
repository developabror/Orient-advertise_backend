package uz.orientadvertise.services.api.controller;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import uz.orientadvertise.services.domain.export.ExportType;
import uz.orientadvertise.services.service.EventReportService;
import uz.orientadvertise.services.service.EventReportService.EventReport;
import uz.orientadvertise.services.service.EventReportService.JobStatus;
import uz.orientadvertise.services.service.EventReportService.Outcome;
import uz.orientadvertise.services.service.EventReportService.ReportJob;
import uz.orientadvertise.services.service.ExcelExportService;
import uz.orientadvertise.services.service.ExcelExportService.ExportFilters;
import uz.orientadvertise.services.service.ExportConcurrencyLimiter;
import uz.orientadvertise.services.service.OperatorScopeResolver;
import uz.orientadvertise.services.service.OperatorScopeResolver.ScopedProjects;

@RestController
@Tag(name = "Reports", description = "Aggregated event reports + Excel export")
@RequestMapping("/api/reports")
public class ReportController {

    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final EventReportService reportService;
    private final ExcelExportService excelExportService;
    private final ExportConcurrencyLimiter exportLimiter;
    private final OperatorScopeResolver operatorScopeResolver;

    public ReportController(EventReportService reportService,
                              ExcelExportService excelExportService,
                              ExportConcurrencyLimiter exportLimiter,
                              OperatorScopeResolver operatorScopeResolver) {
        this.reportService = reportService;
        this.excelExportService = excelExportService;
        this.exportLimiter = exportLimiter;
        this.operatorScopeResolver = operatorScopeResolver;
    }

    /**
     * Operator scope resolved on the REQUEST thread (the report async / export streaming
     * threads have no SecurityContext): {@code null} = unrestricted (ADMIN/VIEWER), the
     * assigned set for an operator (possibly empty ⇒ nothing visible).
     */
    private Collection<Long> scopedProjectIds() {
        ScopedProjects scope = operatorScopeResolver.resolve();
        return scope.restricted() ? scope.projectIds() : null;
    }

    /**
     * Aggregated event report. Returns either:
     * <ul>
     *   <li><b>200</b> with the inline report when expected event count is at or below
     *       {@link EventReportService#ASYNC_THRESHOLD};</li>
     *   <li><b>202</b> with {@code {"jobId": "..."}} otherwise — poll
     *       {@code GET /service/reports/events/jobs/{jobId}} for status.</li>
     * </ul>
     */
    @Operation(
            summary = "Aggregated event/incident report",
            description = """
                    Returns counts by type, top affected devices, total incident count, and \
                    average resolution time over a date window. For small ranges the report \
                    is computed inline (200). For ranges exceeding ~10,000 events the work \
                    is queued and a job id is returned (202) — poll the jobs endpoint.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Inline report",
                    content = @Content(examples = @ExampleObject(value = """
                            {
                              "status": "COMPLETED",
                              "facilityId": 1,
                              "from": "2026-04-06T00:00:00Z",
                              "to": "2026-05-06T00:00:00Z",
                              "totalEvents": 42,
                              "countsByType": { "OFFLINE": 30, "SYNC_TIMEOUT": 12 },
                              "incidentCount": 3,
                              "avgResolutionSeconds": 1800.0,
                              "topAffectedDevices": [
                                { "deviceId": 1, "deviceName": "TV-1", "eventCount": 30 }
                              ]
                            }
                            """))),
            @ApiResponse(responseCode = "202", description = "Queued; poll job",
                    content = @Content(examples = @ExampleObject(value = """
                            { "status": "PENDING", "jobId": "8c1f5d7e-..." }
                            """))),
            @ApiResponse(responseCode = "400", description = "Range > 90 days or from > to")
    })
    @GetMapping("/events")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR', 'VIEWER')")
    public ResponseEntity<ReportResponse> getEventReport(
            @RequestParam(required = false) Long facilityId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        Outcome outcome = reportService.run(facilityId, from, to, scopedProjectIds());
        if (outcome.async()) {
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(ReportResponse.queued(outcome.jobId()));
        }
        return ResponseEntity.ok(ReportResponse.completed(outcome.report()));
    }

    /**
     * Stream an .xlsx export. Per-user concurrency capped at
     * {@link ExportConcurrencyLimiter#MAX_CONCURRENT_PER_USER}. The slot is acquired
     * synchronously before the response is constructed so a 429 can be returned cleanly;
     * the slot is released by the streaming body's try-with-resources, which fires
     * whether the body completes normally, throws, or the client disconnects.
     */
    @Operation(
            summary = "Stream an Excel (.xlsx) export",
            description = """
                    Apache POI streaming workbook (`SXSSFWorkbook`) — memory stays bounded \
                    regardless of row count. Per-user concurrency is capped at 2; a third \
                    concurrent request returns 429. The export aborts cleanly after the \
                    60-second deadline (`X-Export-Timeout-Seconds: 60`). Sheets vary by \
                    `type`: EVENTS, DEVICES, or STATS.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Streamed .xlsx — application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            @ApiResponse(responseCode = "400", description = "Invalid type, bad ISO dates, or missing facilityId"),
            @ApiResponse(responseCode = "429", description = "Per-user concurrent export limit reached")
    })
    @GetMapping("/export")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR', 'VIEWER')")
    public ResponseEntity<StreamingResponseBody> exportExcel(
            @RequestParam ExportType type,
            @RequestParam(required = false) Long facilityId,
            @RequestParam(required = false) Long deviceId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {

        var auth = SecurityContextHolder.getContext().getAuthentication();
        String username = auth != null ? auth.getName() : "anonymous";
        var slot = exportLimiter.acquire(username);

        var filters = new ExportFilters(facilityId, deviceId, from, to);
        // Resolve scope HERE on the request thread — the streaming body runs on a thread with
        // no SecurityContext, so the resolver must not be called inside it.
        Collection<Long> projectIds = scopedProjectIds();
        long deadline = System.currentTimeMillis()
                + ExcelExportService.DEFAULT_TIMEOUT.toMillis();

        StreamingResponseBody body = out -> {
            try (slot) {
                excelExportService.streamExport(type, filters, projectIds, out, deadline);
            }
        };

        String filename = "export-" + type.name().toLowerCase() + "-"
                + Instant.now().toEpochMilli() + ".xlsx";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(XLSX_MIME))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"")
                .header("X-Export-Timeout-Seconds",
                        String.valueOf(ExcelExportService.DEFAULT_TIMEOUT.toSeconds()))
                .body(body);
    }

    @Operation(
            summary = "Poll an async report job",
            description = "Returns PENDING, COMPLETED, or FAILED. Jobs auto-expire 30 minutes "
                    + "after creation; expired or unknown ids return 404."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Job status (and result if COMPLETED)"),
            @ApiResponse(responseCode = "404", description = "Unknown or expired job id")
    })
    @GetMapping("/events/jobs/{jobId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR', 'VIEWER')")
    public ResponseEntity<JobResponse> getJob(@PathVariable String jobId) {
        ReportJob job = reportService.getJob(jobId);
        return ResponseEntity.ok(JobResponse.from(job));
    }

    /**
     * Inline (status=COMPLETED) and queued (status=PENDING) responses share this envelope —
     * the {@code jobId} field is populated only when queued, and the report fields
     * ({@code totalEvents}, {@code countsByType}, {@code incidentCount},
     * {@code avgResolutionSeconds}, {@code topAffectedDevices}) are populated only when
     * completed. All five report fields are part of the contract; FE generators that drop
     * them lose data the backend has been emitting since the inline path was added.
     */
    @Schema(name = "ReportResponse", description = "Aggregated event report envelope (inline or queued)")
    public record ReportResponse(
            @Schema(description = "PENDING when queued, COMPLETED when inline", example = "COMPLETED")
            String status,
            @Schema(description = "Job id; populated only when status=PENDING", nullable = true,
                    example = "8c1f5d7e-9b3a-4c1f-9d7e-9b3a4c1f9d7e")
            String jobId,
            @Schema(description = "Facility filter echo", nullable = true, example = "1")
            Long facilityId,
            @Schema(description = "Window start (inclusive)", nullable = true)
            Instant from,
            @Schema(description = "Window end (exclusive)", nullable = true)
            Instant to,
            @Schema(description = "Total event count over the window — sum of all countsByType entries",
                    nullable = true, example = "42")
            Long totalEvents,
            @Schema(description = "Per-event-type counts. Keys are EventType names "
                    + "(e.g. OFFLINE, SYNC_TIMEOUT); values are counts.",
                    nullable = true, example = "{ \"OFFLINE\": 30, \"SYNC_TIMEOUT\": 12 }")
            Map<String, Long> countsByType,
            @Schema(description = "Number of incidents derived from the events", nullable = true, example = "3")
            Long incidentCount,
            @Schema(description = "Average incident resolution time in seconds; null when no incidents",
                    nullable = true, example = "1800.0")
            Double avgResolutionSeconds,
            @Schema(description = "Top devices by event count over the window", nullable = true)
            List<EventReportService.DeviceImpact> topAffectedDevices) {

        public static ReportResponse queued(String jobId) {
            return new ReportResponse("PENDING", jobId, null, null, null, null, null, null, null, null);
        }

        public static ReportResponse completed(EventReport r) {
            return new ReportResponse("COMPLETED", null,
                    r.facilityId(), r.from(), r.to(),
                    r.totalEvents(), r.countsByType(),
                    r.incidentCount(), r.avgResolutionSeconds(),
                    r.topAffectedDevices());
        }
    }

    public record JobResponse(
            String jobId,
            JobStatus status,
            EventReport result,
            String error,
            Instant expiresAt) {

        public static JobResponse from(ReportJob j) {
            return new JobResponse(j.jobId(), j.status(), j.result(), j.error(), j.expiresAt());
        }
    }
}
