package uz.orientadvertise.services.api.controller;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uz.orientadvertise.services.api.controller.IncidentController;
import uz.orientadvertise.services.api.security.JwtAuthenticationFilter;
import uz.orientadvertise.services.api.security.SecurityConfig;
import uz.orientadvertise.services.domain.audit.AuditRecorder;
import uz.orientadvertise.services.domain.auth.TokenValidator;
import uz.orientadvertise.services.domain.auth.UserActiveChecker;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.Event;
import uz.orientadvertise.services.domain.model.Incident;
import uz.orientadvertise.services.service.IncidentService;
import uz.orientadvertise.services.service.OperatorScopeResolver;
import uz.orientadvertise.services.service.OperatorScopeResolver.ScopedProjects;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(IncidentController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class IncidentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IncidentService incidentService;

    @MockitoBean
    private OperatorScopeResolver operatorScopeResolver;

    @MockitoBean
    private TokenValidator tokenValidator;

    @MockitoBean
    private UserActiveChecker userActiveChecker;

    @MockitoBean
    private AuditRecorder auditRecorder;

    @Test
    @WithMockUser(roles = "OPERATOR")
    void getOpen_returnsOpenIncidents() throws Exception {
        var i1 = mockIncident(1L, 100L, Event.Priority.CRITICAL);
        var i2 = mockIncident(2L, 101L, Event.Priority.MEDIUM);
        when(operatorScopeResolver.resolve()).thenReturn(new ScopedProjects(null, null, null, false));
        when(incidentService.getOpenScoped(any())).thenReturn(List.of(i1, i2));

        mockMvc.perform(get("/api/incidents/open"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].priority").value("CRITICAL"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void getOpen_filterByPriority_returnsOnlyMatching() throws Exception {
        var i1 = mockIncident(1L, 100L, Event.Priority.CRITICAL);
        var i2 = mockIncident(2L, 101L, Event.Priority.MEDIUM);
        var i3 = mockIncident(3L, 102L, Event.Priority.CRITICAL);
        when(operatorScopeResolver.resolve()).thenReturn(new ScopedProjects(null, null, null, false));
        when(incidentService.getOpenScoped(any())).thenReturn(List.of(i1, i2, i3));

        mockMvc.perform(get("/api/incidents/open").param("priority", "CRITICAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].priority").value("CRITICAL"))
                .andExpect(jsonPath("$[1].priority").value("CRITICAL"));
    }

    @Test
    void getOpen_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/incidents/open"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "alice", roles = "OPERATOR")
    void acknowledge_returnsUpdatedIncident() throws Exception {
        var incident = mockIncident(5L, 100L, Event.Priority.HIGH);
        when(incident.getStatus()).thenReturn(Incident.Status.ACKNOWLEDGED);
        when(incident.getAcknowledgedBy()).thenReturn("alice");
        when(incident.getAcknowledgedAt()).thenReturn(Instant.parse("2026-05-06T01:00:00Z"));
        when(incidentService.acknowledge(org.mockito.ArgumentMatchers.eq(5L),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(incident);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/incidents/5/acknowledge"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.acknowledgedBy").value("alice"));
    }

    @Test
    @WithMockUser(username = "bob", roles = "OPERATOR")
    void resolve_returnsResolvedIncident() throws Exception {
        var incident = mockIncident(6L, 100L, Event.Priority.HIGH);
        when(incident.getStatus()).thenReturn(Incident.Status.RESOLVED);
        when(incident.getResolvedBy()).thenReturn("bob");
        when(incident.getResolvedAt()).thenReturn(Instant.parse("2026-05-06T01:30:00Z"));
        when(incidentService.resolve(org.mockito.ArgumentMatchers.eq(6L),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(incident);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/incidents/6/resolve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolvedBy").value("bob"));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void resolve_alreadyResolved_returns409() throws Exception {
        when(incidentService.resolve(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new IllegalStateException("Incident 7 is already resolved"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/incidents/7/resolve"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("already resolved")));
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void acknowledge_viewer_forbidden() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/incidents/8/acknowledge"))
                .andExpect(status().isForbidden());
    }

    @Test
    void resolve_unauthenticated_returns401() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/incidents/9/resolve"))
                .andExpect(status().isUnauthorized());
    }

    private Incident mockIncident(Long id, Long deviceId, Event.Priority priority) {
        Incident i = mock(Incident.class);
        when(i.getId()).thenReturn(id);
        when(i.getEventType()).thenReturn("DEVICE_OFFLINE");
        when(i.getStatus()).thenReturn(Incident.Status.OPEN);
        when(i.getPriority()).thenReturn(priority);
        when(i.getDescription()).thenReturn("auto");
        when(i.getOccurrenceCount()).thenReturn(1);
        when(i.getOpenedAt()).thenReturn(Instant.parse("2026-05-06T00:00:00Z"));
        when(i.getUpdatedAt()).thenReturn(Instant.parse("2026-05-06T00:00:00Z"));
        Device d = mock(Device.class);
        when(d.getId()).thenReturn(deviceId);
        when(i.getDevice()).thenReturn(d);
        return i;
    }
}
