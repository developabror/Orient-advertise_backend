package uz.orientadvertise.services.api.controller;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uz.orientadvertise.services.api.controller.HealthController;
import uz.orientadvertise.services.api.security.JwtAuthenticationFilter;
import uz.orientadvertise.services.api.security.SecurityConfig;
import uz.orientadvertise.services.domain.audit.AuditRecorder;
import uz.orientadvertise.services.domain.auth.TokenValidator;
import uz.orientadvertise.services.domain.auth.UserActiveChecker;
import uz.orientadvertise.services.domain.model.HealthStatus;
import uz.orientadvertise.services.service.HealthService;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HealthController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HealthService healthService;

    @MockitoBean
    private TokenValidator tokenValidator;

    @MockitoBean
    private UserActiveChecker userActiveChecker;

    @MockitoBean
    private AuditRecorder auditRecorder;

    @Test
    void health_returnsOkWithAllUp() throws Exception {
        when(healthService.checkAll()).thenReturn(List.of(
                new HealthStatus("application", new HealthStatus.Status.Up(), "2024-01-01T00:00:00Z"),
                new HealthStatus("database", new HealthStatus.Status.Up(), "2024-01-01T00:00:00Z")
        ));

        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallStatus").value("UP"))
                .andExpect(jsonPath("$.components.length()").value(2));
    }

    @Test
    void health_returnsDegradedWhenComponentDown() throws Exception {
        when(healthService.checkAll()).thenReturn(List.of(
                new HealthStatus("application", new HealthStatus.Status.Up(), "2024-01-01T00:00:00Z"),
                new HealthStatus("database", new HealthStatus.Status.Down("timeout"), "2024-01-01T00:00:00Z")
        ));

        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallStatus").value("DEGRADED"));
    }
}
