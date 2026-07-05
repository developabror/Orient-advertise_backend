package uz.orientadvertise.services.api.controller;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uz.orientadvertise.services.api.controller.DashboardController;
import uz.orientadvertise.services.api.security.JwtAuthenticationFilter;
import uz.orientadvertise.services.api.security.SecurityConfig;
import uz.orientadvertise.services.domain.audit.AuditRecorder;
import uz.orientadvertise.services.domain.auth.TokenValidator;
import uz.orientadvertise.services.domain.auth.UserActiveChecker;
import uz.orientadvertise.services.service.DashboardService;
import uz.orientadvertise.services.service.DashboardService.DashboardSummary;
import uz.orientadvertise.services.service.DashboardService.OpenIncidentCounts;
import uz.orientadvertise.services.service.DashboardService.RegionSummary;
import uz.orientadvertise.services.service.OperatorScopeResolver;
import uz.orientadvertise.services.service.OperatorScopeResolver.ScopedProjects;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DashboardController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class DashboardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DashboardService dashboardService;

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
    @WithMockUser(roles = "VIEWER")
    void getSummary_returnsAllFields() throws Exception {
        var summary = new DashboardSummary(
                12L, 8L, 3L, 1L,
                new OpenIncidentCounts(2L, 5L),
                List.of(
                        new RegionSummary(1L, "Karachi", 5L, 7L),
                        new RegionSummary(2L, "Lahore", 3L, 5L),
                        new RegionSummary(3L, "Quetta", 0L, 0L)));
        when(dashboardService.getSummary(any())).thenReturn(summary);

        mockMvc.perform(get("/api/dashboard/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalDevices").value(12))
                .andExpect(jsonPath("$.onlineCount").value(8))
                .andExpect(jsonPath("$.offlineCount").value(3))
                .andExpect(jsonPath("$.noContentCount").value(1))
                .andExpect(jsonPath("$.openIncidents.critical").value(2))
                .andExpect(jsonPath("$.openIncidents.warning").value(5))
                .andExpect(jsonPath("$.regionSummary.length()").value(3))
                // Zero-device region is present and carries integer zeros, never null.
                .andExpect(jsonPath("$.regionSummary[2].regionName").value("Quetta"))
                .andExpect(jsonPath("$.regionSummary[2].onlineCount").value(0))
                .andExpect(jsonPath("$.regionSummary[2].totalCount").value(0));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void getSummary_admin_allowed() throws Exception {
        when(dashboardService.getSummary(any())).thenReturn(new DashboardSummary(
                0L, 0L, 0L, 0L, new OpenIncidentCounts(0L, 0L), List.of()));

        mockMvc.perform(get("/api/dashboard/summary"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void getSummary_operator_allowed() throws Exception {
        when(dashboardService.getSummary(any())).thenReturn(new DashboardSummary(
                0L, 0L, 0L, 0L, new OpenIncidentCounts(0L, 0L), List.of()));

        mockMvc.perform(get("/api/dashboard/summary"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADVERTISER")
    void getSummary_advertiser_returns403() throws Exception {
        // Advertisers are scoped to their content — global dashboard counts would leak
        // tenant-level state, so the endpoint rejects them.
        mockMvc.perform(get("/api/dashboard/summary"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getSummary_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/dashboard/summary"))
                .andExpect(status().isUnauthorized());
    }
}
