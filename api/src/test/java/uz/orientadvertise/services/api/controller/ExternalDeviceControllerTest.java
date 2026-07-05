package uz.orientadvertise.services.api.controller;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uz.orientadvertise.services.api.controller.ExternalDeviceController;
import uz.orientadvertise.services.api.security.JwtAuthenticationFilter;
import uz.orientadvertise.services.api.security.SecurityConfig;
import uz.orientadvertise.services.domain.audit.AuditRecorder;
import uz.orientadvertise.services.domain.auth.TokenValidator;
import uz.orientadvertise.services.domain.auth.UserActiveChecker;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.DeviceActionType;
import uz.orientadvertise.services.domain.model.Event;
import uz.orientadvertise.services.domain.model.RemoteAction;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.EventRepository;
import uz.orientadvertise.services.service.DeviceActionService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ExternalDeviceController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class ExternalDeviceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DeviceRepository deviceRepository;

    @MockitoBean
    private EventRepository eventRepository;

    @MockitoBean
    private DeviceActionService deviceActionService;

    @MockitoBean
    private uz.orientadvertise.services.service.DeviceManagementService deviceManagementService;

    @MockitoBean
    private TokenValidator tokenValidator;

    @MockitoBean
    private UserActiveChecker userActiveChecker;

    @MockitoBean
    private AuditRecorder auditRecorder;

    private Device mockDevice() {
        var device = mock(Device.class);
        when(device.getId()).thenReturn(42L);
        when(device.getSerialNumber()).thenReturn("SN-1");
        when(device.getName()).thenReturn("TV-1");
        when(device.getStatus()).thenReturn(Device.Status.ONLINE);
        when(device.getLastHeartbeatAt()).thenReturn(Instant.parse("2026-05-06T00:00:00Z"));
        when(device.getCurrentContentVersion()).thenReturn("v42");
        return device;
    }

    @Test
    @WithMockUser(roles = "API_CLIENT", username = "abcd1234")
    void getStatus_returnsPublicFieldsOnly() throws Exception {
        var device = mockDevice();
        when(deviceRepository.findBySerialNumberAndDeletedAtIsNull("SN-1"))
                .thenReturn(Optional.of(device));
        // computedStatus is the heartbeat-derived value, fetched by device id.
        when(deviceManagementService.computedStatus(42L)).thenReturn(Device.Status.ONLINE);

        mockMvc.perform(get("/api/external/devices/SN-1/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serialNumber").value("SN-1"))
                .andExpect(jsonPath("$.computedStatus").value("ONLINE"))
                .andExpect(jsonPath("$.currentContentVersion").value("v42"))
                // Internal device id must not appear in the response shape.
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.deviceId").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "API_CLIENT")
    void getStatus_unknownSerial_returns404() throws Exception {
        when(deviceRepository.findBySerialNumberAndDeletedAtIsNull("SN-999"))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/external/devices/SN-999/status"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void getStatus_jwtAdmin_returns403() throws Exception {
        // External endpoints require ROLE_API_CLIENT specifically — JWT-derived ADMIN
        // role does NOT grant access. This is the cross-auth isolation guarantee.
        mockMvc.perform(get("/api/external/devices/SN-1/status"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getStatus_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/external/devices/SN-1/status"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "API_CLIENT")
    void getHistory_returnsEventEntries_noInternalIds() throws Exception {
        var device = mockDevice();
        when(deviceRepository.findBySerialNumberAndDeletedAtIsNull("SN-1"))
                .thenReturn(Optional.of(device));

        var inner = mock(Device.class);
        when(inner.getId()).thenReturn(42L);
        // Stubs assembled into local variables before the outer when() chain to avoid
        // Mockito's UnfinishedStubbingException (nested mock()/when() inside an outer when()).
        var event = mock(Event.class);
        when(event.getId()).thenReturn(100L); // internal id should NOT appear
        when(event.getOccurredAt()).thenReturn(Instant.parse("2026-05-06T00:00:00Z"));
        when(event.getEventType()).thenReturn("OFFLINE");
        when(event.getPriority()).thenReturn(Event.Priority.HIGH);
        when(event.getPayload()).thenReturn("{}");
        when(event.getDevice()).thenReturn(inner);
        when(eventRepository.findFiltered(eq(42L), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(event)));

        mockMvc.perform(get("/api/external/devices/SN-1/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventType").value("OFFLINE"))
                .andExpect(jsonPath("$[0].priority").value("HIGH"))
                .andExpect(jsonPath("$[0].id").doesNotExist())
                .andExpect(jsonPath("$[0].deviceId").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "API_CLIENT")
    void getHistory_pageSizeOver100_returns400() throws Exception {
        // Service-layer guard fires BEFORE the repository call, so no device stub needed.
        mockMvc.perform(get("/api/external/devices/SN-1/history").param("size", "500"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "API_CLIENT", username = "abcd1234")
    void issueAction_returnsActionWithSerialNotDeviceId() throws Exception {
        var device = mockDevice();
        var inner = mock(Device.class);
        when(inner.getId()).thenReturn(42L);
        var action = mock(RemoteAction.class);
        when(action.getId()).thenReturn(7L);
        when(action.getDevice()).thenReturn(inner);
        when(action.getActionType()).thenReturn("REBOOT");
        when(action.getStatus()).thenReturn(RemoteAction.Status.PENDING);
        when(action.getIssuedAt()).thenReturn(Instant.parse("2026-05-06T00:00:00Z"));
        when(action.getExpiresAt()).thenReturn(Instant.parse("2026-05-06T01:00:00Z"));
        when(deviceActionService.issueAction(anyLong(), eq(DeviceActionType.REBOOT), any(), any()))
                .thenReturn(action);
        when(deviceRepository.findBySerialNumberAndDeletedAtIsNull("SN-1"))
                .thenReturn(Optional.of(device));

        mockMvc.perform(post("/api/external/devices/SN-1/actions")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"REBOOT"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.actionId").value(7))
                .andExpect(jsonPath("$.serialNumber").value("SN-1"))
                .andExpect(jsonPath("$.actionType").value("REBOOT"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.deviceId").doesNotExist())
                .andExpect(jsonPath("$.issuedBy").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "API_CLIENT")
    void issueAction_volumeOutOfRange_returns400() throws Exception {
        // Bean Validation fires before any repository call, so no device stub needed.
        mockMvc.perform(post("/api/external/devices/SN-1/actions")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"VOLUME_SET","volume":150}"""))
                .andExpect(status().isBadRequest());
    }
}
