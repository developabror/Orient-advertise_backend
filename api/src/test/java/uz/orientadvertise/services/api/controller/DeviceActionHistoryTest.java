package uz.orientadvertise.services.api.controller;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uz.orientadvertise.services.api.security.JwtAuthenticationFilter;
import uz.orientadvertise.services.api.security.SecurityConfig;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.audit.AuditRecorder;
import uz.orientadvertise.services.domain.auth.TokenValidator;
import uz.orientadvertise.services.domain.auth.UserActiveChecker;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.RemoteAction;
import uz.orientadvertise.services.service.DeviceActionService;
import uz.orientadvertise.services.service.DeviceDiagnosticsService;
import uz.orientadvertise.services.service.DeviceHeartbeatService;
import uz.orientadvertise.services.service.DeviceManagementService;
import uz.orientadvertise.services.service.DeviceRegistrationService;
import uz.orientadvertise.services.service.DeviceSyncService;
import uz.orientadvertise.services.service.PlaylistControlService;
import uz.orientadvertise.services.service.RemoteActionService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DeviceController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class DeviceActionHistoryTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RemoteActionService remoteActionService;

    @MockitoBean
    private DeviceRegistrationService registrationService;

    @MockitoBean
    private DeviceHeartbeatService heartbeatService;

    @MockitoBean
    private DeviceManagementService managementService;

    @MockitoBean
    private DeviceSyncService syncService;

    @MockitoBean
    private PlaylistControlService playlistControlService;

    @MockitoBean
    private DeviceActionService deviceActionService;

    @MockitoBean
    private DeviceDiagnosticsService diagnosticsService;

    @MockitoBean
    private uz.orientadvertise.services.service.DeviceRegistrationRateLimiter registrationRateLimiter;

    @MockitoBean
    private uz.orientadvertise.services.domain.content.DevicePushChannel devicePushChannel;

    @MockitoBean
    private TokenValidator tokenValidator;

    @MockitoBean
    private UserActiveChecker userActiveChecker;

    @MockitoBean
    private AuditRecorder auditRecorder;

    // ----- happy path -----

    @Test
    @WithMockUser(roles = "OPERATOR")
    void history_returns200WithFullActionFields() throws Exception {
        // Verify every contract field is rendered: status, payload, timestamps, issuedBy,
        // result. Distinct from the device-side /pending DTO which is intentionally slim.
        var a1 = stubAction(101L, "REBOOT", RemoteAction.Status.CONFIRMED,
                "{}",
                Instant.parse("2026-05-08T10:00:00Z"),
                Instant.parse("2026-05-08T10:05:00Z"),
                Instant.parse("2026-05-08T10:00:30Z"),
                "alice", "ok");
        var a2 = stubAction(102L, "VOLUME_SET", RemoteAction.Status.PENDING,
                "{\"volume\":50}",
                Instant.parse("2026-05-08T11:00:00Z"),
                Instant.parse("2026-05-08T11:05:00Z"),
                null, "alice", null);
        when(remoteActionService.getHistory(eq(7L), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(a1, a2), PageRequest.of(0, 20), 2));

        mockMvc.perform(get("/api/devices/7/actions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].actionId").value(101))
                .andExpect(jsonPath("$.content[0].actionType").value("REBOOT"))
                .andExpect(jsonPath("$.content[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$.content[0].payload").value("{}"))
                .andExpect(jsonPath("$.content[0].issuedAt").value("2026-05-08T10:00:00Z"))
                .andExpect(jsonPath("$.content[0].expiresAt").value("2026-05-08T10:05:00Z"))
                .andExpect(jsonPath("$.content[0].confirmedAt").value("2026-05-08T10:00:30Z"))
                .andExpect(jsonPath("$.content[0].issuedBy").value("alice"))
                .andExpect(jsonPath("$.content[0].result").value("ok"))
                // PENDING action: confirmedAt and result are absent (null fields suppressed).
                .andExpect(jsonPath("$.content[1].status").value("PENDING"))
                .andExpect(jsonPath("$.content[1].confirmedAt").doesNotExist())
                .andExpect(jsonPath("$.content[1].result").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void history_filtersByStatusAndActionType_arePropagated() throws Exception {
        when(remoteActionService.getHistory(eq(7L), eq(RemoteAction.Status.FAILED),
                eq("REBOOT"), any(), any(), any()))
                .thenReturn(Page.empty(PageRequest.of(0, 20)));

        mockMvc.perform(get("/api/devices/7/actions")
                        .param("status", "FAILED")
                        .param("actionType", "REBOOT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));

        verify(remoteActionService).getHistory(eq(7L),
                eq(RemoteAction.Status.FAILED), eq("REBOOT"),
                isNull(), isNull(), any(Pageable.class));
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void history_fromAndToBothOmitted_serviceReceivesNulls() throws Exception {
        // The service resolves defaults (trailing 30 days) — the controller passes null
        // through unchanged so the service's resolution policy stays the single source of
        // truth for the default window.
        when(remoteActionService.getHistory(any(), any(), any(), any(), any(), any()))
                .thenReturn(Page.empty(PageRequest.of(0, 20)));

        mockMvc.perform(get("/api/devices/7/actions"))
                .andExpect(status().isOk());

        verify(remoteActionService).getHistory(eq(7L),
                isNull(), isNull(), isNull(), isNull(), any(Pageable.class));
    }

    // ----- 404: unknown device -----

    @Test
    @WithMockUser(roles = "ADMIN")
    void history_unknownDevice_returns404() throws Exception {
        // The device-existence check fires inside the service BEFORE any range/page
        // validation. Even an authorized ADMIN gets 404, not 200-with-empty.
        when(remoteActionService.getHistory(eq(999L), any(), any(), any(), any(), any()))
                .thenThrow(new ResourceNotFoundException("Device", 999L));

        mockMvc.perform(get("/api/devices/999/actions"))
                .andExpect(status().isNotFound());
    }

    // ----- 400: range validation -----

    @Test
    @WithMockUser(roles = "OPERATOR")
    void history_fromAfterTo_returns400() throws Exception {
        when(remoteActionService.getHistory(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("from must be before to"));

        mockMvc.perform(get("/api/devices/7/actions")
                        .param("from", "2026-05-10T00:00:00Z")
                        .param("to", "2026-05-01T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("from must be before to")));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void history_rangeOver90Days_returns400() throws Exception {
        when(remoteActionService.getHistory(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Date range cannot exceed 90 days"));

        mockMvc.perform(get("/api/devices/7/actions")
                        .param("from", "2025-01-01T00:00:00Z")
                        .param("to", "2025-12-31T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("90 days")));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void history_pageSizeOver100_returns400() throws Exception {
        when(remoteActionService.getHistory(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Page size cannot exceed 100"));

        mockMvc.perform(get("/api/devices/7/actions").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("100")));
    }

    // ----- role gates -----

    @Test
    @WithMockUser(roles = "ADVERTISER")
    void history_advertiser_returns403() throws Exception {
        mockMvc.perform(get("/api/devices/7/actions"))
                .andExpect(status().isForbidden());
        verify(remoteActionService, never()).getHistory(any(), any(), any(), any(), any(), any());
    }

    @Test
    void history_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/devices/7/actions"))
                .andExpect(status().isUnauthorized());
    }

    // ----- helpers -----

    private static RemoteAction stubAction(long id, String type, RemoteAction.Status status,
                                             String payload, Instant issuedAt, Instant expiresAt,
                                             Instant confirmedAt, String issuedBy, String result) {
        var a = mock(RemoteAction.class);
        when(a.getId()).thenReturn(id);
        when(a.getActionType()).thenReturn(type);
        when(a.getStatus()).thenReturn(status);
        when(a.getPayload()).thenReturn(payload);
        when(a.getIssuedAt()).thenReturn(issuedAt);
        when(a.getExpiresAt()).thenReturn(expiresAt);
        when(a.getConfirmedAt()).thenReturn(confirmedAt);
        when(a.getIssuedBy()).thenReturn(issuedBy);
        when(a.getResult()).thenReturn(result);
        // Device association isn't read by the DTO, but the mock returns a non-null
        // device so any future field that derefs getDevice() doesn't NPE.
        when(a.getDevice()).thenReturn(mock(Device.class));
        return a;
    }
}
