package uz.orientadvertise.services.api.controller;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uz.orientadvertise.services.api.controller.ReportController;
import uz.orientadvertise.services.api.security.JwtAuthenticationFilter;
import uz.orientadvertise.services.api.security.SecurityConfig;
import uz.orientadvertise.services.common.exception.RateLimitExceededException;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.audit.AuditRecorder;
import uz.orientadvertise.services.domain.auth.TokenValidator;
import uz.orientadvertise.services.domain.auth.UserActiveChecker;
import uz.orientadvertise.services.service.EventReportService;
import uz.orientadvertise.services.service.EventReportService.EventReport;
import uz.orientadvertise.services.service.EventReportService.JobStatus;
import uz.orientadvertise.services.service.EventReportService.Outcome;
import uz.orientadvertise.services.service.EventReportService.ReportJob;
import uz.orientadvertise.services.service.ExcelExportService;
import uz.orientadvertise.services.service.ExportConcurrencyLimiter;
import uz.orientadvertise.services.service.OperatorScopeResolver;
import uz.orientadvertise.services.service.OperatorScopeResolver.ScopedProjects;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReportController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class ReportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EventReportService reportService;

    @MockitoBean
    private ExcelExportService excelExportService;

    @MockitoBean
    private ExportConcurrencyLimiter exportLimiter;

    @MockitoBean
    private TokenValidator tokenValidator;

    @MockitoBean
    private UserActiveChecker userActiveChecker;

    @MockitoBean
    private AuditRecorder auditRecorder;

    @MockitoBean
    private OperatorScopeResolver operatorScopeResolver;

    @org.junit.jupiter.api.BeforeEach
    void unrestrictedScope() {
        lenient().when(operatorScopeResolver.resolve())
                .thenReturn(new ScopedProjects(null, null, null, false));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void getReport_smallRange_returnsInlineReport() throws Exception {
        var report = new EventReport(1L,
                Instant.parse("2026-04-29T00:00:00Z"),
                Instant.parse("2026-05-06T00:00:00Z"),
                42L,
                Map.of("OFFLINE", 30L, "SYNC_TIMEOUT", 12L),
                3L, 1800.0,
                List.of(new EventReportService.DeviceImpact(1L, "TV-1", 30L)));
        when(reportService.run(eq(1L), any(), any(), any())).thenReturn(Outcome.sync(report));

        mockMvc.perform(get("/api/reports/events").param("facilityId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.totalEvents").value(42))
                .andExpect(jsonPath("$.incidentCount").value(3))
                .andExpect(jsonPath("$.avgResolutionSeconds").value(1800.0))
                .andExpect(jsonPath("$.countsByType.OFFLINE").value(30))
                .andExpect(jsonPath("$.topAffectedDevices[0].deviceId").value(1));
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void getReport_zeroEvents_returnsZeroFilledStructureNot404() throws Exception {
        // Edge case: empty range still yields 200 with zero-filled fields, not 404.
        var report = new EventReport(1L,
                Instant.parse("2026-04-29T00:00:00Z"),
                Instant.parse("2026-05-06T00:00:00Z"),
                0L, Map.of(), 0L, null, List.of());
        when(reportService.run(any(), any(), any(), any())).thenReturn(Outcome.sync(report));

        mockMvc.perform(get("/api/reports/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEvents").value(0))
                .andExpect(jsonPath("$.incidentCount").value(0))
                .andExpect(jsonPath("$.avgResolutionSeconds").doesNotExist())
                .andExpect(jsonPath("$.countsByType.length()").value(0))
                .andExpect(jsonPath("$.topAffectedDevices.length()").value(0));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void getReport_largeRange_returns202WithJobId() throws Exception {
        when(reportService.run(any(), any(), any(), any())).thenReturn(Outcome.async("job-abc"));

        mockMvc.perform(get("/api/reports/events").param("facilityId", "1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.jobId").value("job-abc"));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void getJob_pending_returnsJobStatus() throws Exception {
        var job = new ReportJob("job-abc", JobStatus.PENDING, null, null,
                Instant.parse("2026-05-06T01:00:00Z"));
        when(reportService.getJob("job-abc")).thenReturn(job);

        mockMvc.perform(get("/api/reports/events/jobs/job-abc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.result").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void getJob_completed_returnsResult() throws Exception {
        var report = new EventReport(1L,
                Instant.parse("2026-04-29T00:00:00Z"),
                Instant.parse("2026-05-06T00:00:00Z"),
                100L, Map.of("OFFLINE", 100L), 5L, 1000.0,
                List.of(new EventReportService.DeviceImpact(1L, "TV-1", 100L)));
        var job = new ReportJob("job-abc", JobStatus.COMPLETED, report, null,
                Instant.parse("2026-05-06T01:00:00Z"));
        when(reportService.getJob("job-abc")).thenReturn(job);

        mockMvc.perform(get("/api/reports/events/jobs/job-abc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.result.totalEvents").value(100));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void getJob_unknown_returns404() throws Exception {
        when(reportService.getJob("missing"))
                .thenThrow(new ResourceNotFoundException("ReportJob", "missing"));

        mockMvc.perform(get("/api/reports/events/jobs/missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void getReport_rangeOver90Days_returns400() throws Exception {
        when(reportService.run(any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Date range cannot exceed 90 days"));

        mockMvc.perform(get("/api/reports/events")
                        .param("from", "2025-01-01T00:00:00Z")
                        .param("to", "2026-05-06T00:00:00Z"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADVERTISER")
    void getReport_advertiser_returns403() throws Exception {
        mockMvc.perform(get("/api/reports/events"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getReport_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/reports/events"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "OPERATOR", username = "alice")
    void exportExcel_devices_returnsXlsxStream() throws Exception {
        var slot = mock(ExportConcurrencyLimiter.Slot.class);
        when(exportLimiter.acquire(eq("alice"))).thenReturn(slot);
        org.mockito.Mockito.doAnswer(inv -> {
            var out = (java.io.OutputStream) inv.getArgument(3);
            // Simulate POI writing the .xlsx zip header so the test can assert the bytes.
            out.write(new byte[]{'P', 'K', 0x03, 0x04});
            return null;
        }).when(excelExportService).streamExport(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyLong());

        var mvcResult = mockMvc.perform(get("/api/reports/export").param("type", "DEVICES"))
                .andExpect(status().isOk())
                .andReturn();
        // Async dispatch happens with StreamingResponseBody; resolve it.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Content-Type",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("X-Export-Timeout-Seconds", "60"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                                org.hamcrest.Matchers.startsWith("attachment; filename=\"export-devices-")));
        org.mockito.Mockito.verify(slot).close();
    }

    @Test
    @WithMockUser(roles = "OPERATOR", username = "alice")
    void exportExcel_overLimit_returns429() throws Exception {
        when(exportLimiter.acquire(eq("alice")))
                .thenThrow(new RateLimitExceededException(
                        "Export limit reached: max 2 concurrent exports per user"));

        mockMvc.perform(get("/api/reports/export").param("type", "DEVICES"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("max 2")));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void exportExcel_invalidType_returns400() throws Exception {
        mockMvc.perform(get("/api/reports/export").param("type", "NOPE"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADVERTISER", username = "alice")
    void exportExcel_advertiser_returns403() throws Exception {
        mockMvc.perform(get("/api/reports/export").param("type", "DEVICES"))
                .andExpect(status().isForbidden());
    }

    @Test
    void exportExcel_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/reports/export").param("type", "DEVICES"))
                .andExpect(status().isUnauthorized());
    }
}
