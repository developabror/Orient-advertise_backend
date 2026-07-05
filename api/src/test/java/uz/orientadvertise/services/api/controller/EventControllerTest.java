package uz.orientadvertise.services.api.controller;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uz.orientadvertise.services.api.controller.EventController;
import uz.orientadvertise.services.api.security.JwtAuthenticationFilter;
import uz.orientadvertise.services.api.security.SecurityConfig;
import uz.orientadvertise.services.domain.audit.AuditRecorder;
import uz.orientadvertise.services.domain.auth.TokenValidator;
import uz.orientadvertise.services.domain.auth.UserActiveChecker;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.Event;
import uz.orientadvertise.services.service.EventQueryService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EventController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class EventControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EventQueryService eventQueryService;

    @MockitoBean
    private TokenValidator tokenValidator;

    @MockitoBean
    private UserActiveChecker userActiveChecker;

    @MockitoBean
    private AuditRecorder auditRecorder;

    @Test
    @WithMockUser(roles = "OPERATOR")
    void list_byDeviceId_returnsPagedEvents() throws Exception {
        var event = stubEvent(1L, 100L, Event.Priority.HIGH, "DEVICE_OFFLINE");
        when(eventQueryService.findFiltered(any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(event)));

        mockMvc.perform(get("/api/events").param("deviceId", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.content[0].deviceId").value(100))
                .andExpect(jsonPath("$.content[0].priority").value("HIGH"));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void list_noDeviceOrFacility_returns400() throws Exception {
        when(eventQueryService.findFiltered(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException(
                        "At least one of deviceId or facilityId is required"));

        mockMvc.perform(get("/api/events"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("deviceId or facilityId")));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void list_rangeOver90Days_returns400() throws Exception {
        when(eventQueryService.findFiltered(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Date range cannot exceed 90 days"));

        mockMvc.perform(get("/api/events")
                        .param("deviceId", "1")
                        .param("from", "2025-01-01T00:00:00Z")
                        .param("to", "2025-12-31T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("90 days")));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void list_pageSizeOver100_returns400() throws Exception {
        when(eventQueryService.findFiltered(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Page size cannot exceed 100"));

        mockMvc.perform(get("/api/events")
                        .param("deviceId", "1")
                        .param("size", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("100")));
    }

    @Test
    void list_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/events").param("deviceId", "1"))
                .andExpect(status().isUnauthorized());
    }

    private Event stubEvent(Long id, Long deviceId, Event.Priority priority, String type) {
        Event e = mock(Event.class);
        when(e.getId()).thenReturn(id);
        when(e.getEventType()).thenReturn(type);
        when(e.getPriority()).thenReturn(priority);
        when(e.getPayload()).thenReturn("{}");
        when(e.getOccurredAt()).thenReturn(Instant.parse("2026-05-06T00:00:00Z"));
        when(e.getCreatedAt()).thenReturn(Instant.parse("2026-05-06T00:00:00Z"));
        Device d = mock(Device.class);
        when(d.getId()).thenReturn(deviceId);
        when(e.getDevice()).thenReturn(d);
        return e;
    }
}
