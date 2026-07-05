package uz.orientadvertise.services.api.controller;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uz.orientadvertise.services.api.security.JwtAuthenticationFilter;
import uz.orientadvertise.services.api.security.SecurityConfig;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.audit.AuditRecorder;
import uz.orientadvertise.services.domain.auth.Role;
import uz.orientadvertise.services.domain.auth.TokenValidator;
import uz.orientadvertise.services.domain.auth.UserActiveChecker;
import uz.orientadvertise.services.service.ContentStatsService;
import uz.orientadvertise.services.service.DevicePlaybackReportService;
import uz.orientadvertise.services.service.DevicePlaybackReportService.PerContent;
import uz.orientadvertise.services.service.DevicePlaybackReportService.PlaybackReport;
import uz.orientadvertise.services.service.DevicePlaybackReportService.ReportScopeType;
import uz.orientadvertise.services.service.DevicePlaybackReportService.Scope;
import uz.orientadvertise.services.service.OperatorScopeResolver;
import uz.orientadvertise.services.service.OperatorScopeResolver.ScopedProjects;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StatsController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class DevicePlaybackReportControllerTest {

    private static final Instant FROM = Instant.parse("2026-06-17T10:00:00Z");
    private static final Instant TO = Instant.parse("2026-06-24T10:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DevicePlaybackReportService devicePlaybackReportService;

    @MockitoBean
    private ContentStatsService contentStatsService;

    @MockitoBean
    private OperatorScopeResolver operatorScopeResolver;

    @MockitoBean
    private TokenValidator tokenValidator;

    @MockitoBean
    private UserActiveChecker userActiveChecker;

    @MockitoBean
    private AuditRecorder auditRecorder;

    @BeforeEach
    void stubUnrestrictedScope() {
        lenient().when(operatorScopeResolver.resolve())
                .thenReturn(new ScopedProjects(null, null, null, false));
    }

    private PlaybackReport report(List<PerContent> rows, long totalCount, long totalDuration, boolean complete) {
        return new PlaybackReport(new Scope("DEVICE", 42L, "Lobby TV-1"), FROM, TO,
                totalCount, totalDuration, complete, rows);
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void getDeviceReport_returnsScopeObjectAndPerContent() throws Exception {
        var rep = report(List.of(
                new PerContent(7L, "Summer Promo 30s", 420L, 12600L, true),
                new PerContent(9L, "Store Hours", 300L, 9000L, true)),
                720L, 21600L, true);
        when(devicePlaybackReportService.report(any(), anyLong(), any(), any(), anyBoolean(), any()))
                .thenReturn(rep);

        mockMvc.perform(get("/api/stats/device/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope.type").value("DEVICE"))
                .andExpect(jsonPath("$.scope.id").value(42))
                .andExpect(jsonPath("$.scope.name").value("Lobby TV-1"))
                .andExpect(jsonPath("$.totalPlayCount").value(720))
                .andExpect(jsonPath("$.totalDurationSeconds").value(21600))
                .andExpect(jsonPath("$.durationComplete").value(true))
                .andExpect(jsonPath("$.perContent.length()").value(2))
                .andExpect(jsonPath("$.perContent[0].contentFileId").value(7))
                .andExpect(jsonPath("$.perContent[0].playCount").value(420))
                .andExpect(jsonPath("$.perContent[0].durationComplete").value(true))
                // Must NOT leak a flat device id/name or any hh:mm:ss field.
                .andExpect(jsonPath("$.deviceId").doesNotExist())
                .andExpect(jsonPath("$.deviceName").doesNotExist())
                .andExpect(jsonPath("$.totalDurationHms").doesNotExist())
                .andExpect(jsonPath("$.perContent[0].totalDurationHms").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void getDeviceReport_operatorAlsoAllowed() throws Exception {
        when(devicePlaybackReportService.report(any(), anyLong(), any(), any(), anyBoolean(), any()))
                .thenReturn(report(List.of(), 0L, 0L, true));
        mockMvc.perform(get("/api/stats/device/42")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void getDeviceReport_bothDurationSourcesNull_durationCompleteFalse() throws Exception {
        var rep = report(List.of(new PerContent(9L, "Store Hours", 300L, 0L, false)),
                300L, 0L, false);
        when(devicePlaybackReportService.report(any(), anyLong(), any(), any(), anyBoolean(), any()))
                .thenReturn(rep);

        mockMvc.perform(get("/api/stats/device/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.perContent[0].durationComplete").value(false))
                .andExpect(jsonPath("$.durationComplete").value(false))
                .andExpect(jsonPath("$.perContent[0].totalDurationSeconds").value(0));
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void getDeviceReport_noPlays_returnsEmptyPerContent() throws Exception {
        when(devicePlaybackReportService.report(any(), anyLong(), any(), any(), anyBoolean(), any()))
                .thenReturn(report(List.of(), 0L, 0L, true));

        mockMvc.perform(get("/api/stats/device/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.perContent.length()").value(0))
                .andExpect(jsonPath("$.totalPlayCount").value(0))
                .andExpect(jsonPath("$.durationComplete").value(true));
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void getDeviceReport_unknownDevice_returns404() throws Exception {
        when(devicePlaybackReportService.report(any(), anyLong(), any(), any(), anyBoolean(), any()))
                .thenThrow(new ResourceNotFoundException("Device", 999L));
        mockMvc.perform(get("/api/stats/device/999")).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void getDeviceReport_operatorOutOfScope_returns404() throws Exception {
        when(devicePlaybackReportService.report(any(), anyLong(), any(), any(), anyBoolean(), any()))
                .thenThrow(new ResourceNotFoundException("Device", 42L));
        mockMvc.perform(get("/api/stats/device/42")).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void getDeviceReport_fromAfterTo_returns400() throws Exception {
        when(devicePlaybackReportService.report(any(), anyLong(), any(), any(), anyBoolean(), any()))
                .thenThrow(new IllegalArgumentException("from must be before to"));
        mockMvc.perform(get("/api/stats/device/42"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(containsString("from")));
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void getDeviceReport_rangeOver90Days_returns400() throws Exception {
        when(devicePlaybackReportService.report(any(), anyLong(), any(), any(), anyBoolean(), any()))
                .thenThrow(new IllegalArgumentException("Date range cannot exceed 90 days"));
        mockMvc.perform(get("/api/stats/device/42"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("90")));
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void getDeviceReport_unparseableFrom_returns400() throws Exception {
        mockMvc.perform(get("/api/stats/device/42").param("from", "not-a-date"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getDeviceReport_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/stats/device/42")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "ADVERTISER")
    void getDeviceReport_advertiser_returns403() throws Exception {
        mockMvc.perform(get("/api/stats/device/42")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void getDeviceReport_operatorScopePassedToService() throws Exception {
        // Operator restricted to projects [1,2] → controller must pass operatorOnly=true + those ids.
        when(operatorScopeResolver.resolve())
                .thenReturn(new ScopedProjects("op", Role.OPERATOR, List.of(1L, 2L), true));
        when(devicePlaybackReportService.report(any(), anyLong(), any(), any(), anyBoolean(), any()))
                .thenReturn(report(List.of(), 0L, 0L, true));

        mockMvc.perform(get("/api/stats/device/42")).andExpect(status().isOk());

        verify(devicePlaybackReportService).report(
                eq(ReportScopeType.DEVICE), eq(42L), any(), any(), eq(true), eq(List.of(1L, 2L)));
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void getDeviceReport_errorEnvelopeShape() throws Exception {
        when(devicePlaybackReportService.report(any(), anyLong(), any(), any(), anyBoolean(), any()))
                .thenThrow(new IllegalArgumentException("from must be before to"));
        mockMvc.perform(get("/api/stats/device/42"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.correlationId").exists())
                // No nested { error: { code } } envelope, no code strings.
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.error.code").doesNotExist());
    }
}
