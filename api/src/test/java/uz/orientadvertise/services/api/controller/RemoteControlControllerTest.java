package uz.orientadvertise.services.api.controller;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import uz.orientadvertise.services.api.security.JwtAuthenticationFilter;
import uz.orientadvertise.services.api.security.SecurityConfig;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.audit.AuditRecorder;
import uz.orientadvertise.services.domain.auth.TokenValidator;
import uz.orientadvertise.services.domain.auth.UserActiveChecker;
import uz.orientadvertise.services.service.DeviceManagementService;
import uz.orientadvertise.services.service.RemoteSessionService;
import uz.orientadvertise.services.service.RemoteSessionService.AckStatus;
import uz.orientadvertise.services.service.RemoteSessionService.RemoteCapabilityView;
import uz.orientadvertise.services.service.RemoteSessionService.RemoteSessionView;
import uz.orientadvertise.services.service.exception.RemoteCapabilityUnsupportedException;
import uz.orientadvertise.services.service.exception.RemoteControlDisabledException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RemoteControlController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class RemoteControlControllerTest {

    private static final String SESSION = "rs_7f3a91c4b8e24d5a";
    private static final Instant EXPIRES = Instant.parse("2026-08-27T10:45:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RemoteSessionService remoteSessionService;

    @MockitoBean
    private DeviceManagementService deviceManagementService;

    @MockitoBean
    private TokenValidator tokenValidator;

    @MockitoBean
    private UserActiveChecker userActiveChecker;

    @MockitoBean
    private AuditRecorder auditRecorder;

    /** Device-agent auth: principal = the device id (Long), authority ROLE_DEVICE. */
    private static RequestPostProcessor device(long id) {
        return authentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                id, null, List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_DEVICE"))));
    }

    private static RemoteSessionView view(String status, String ticket, String deliveredVia) {
        return new RemoteSessionView(SESSION, 12L, status, "wss://relay.example.uz/viewer",
                ticket, EXPIRES, false, deliveredVia,
                new RemoteCapabilityView(true, "ROOT", "SCRCPY_WS", 1280, 720,
                        Instant.parse("2026-08-27T10:12:00Z")));
    }

    // ----- POST /remote : positive -----

    @Test
    @WithMockUser(roles = "OPERATOR")
    void start_returns201WithViewerTicketAndCapability() throws Exception {
        when(remoteSessionService.start(eq(12L), eq(false), anyString()))
                .thenReturn(view("PENDING", "opaque.viewer.ticket", "WS"));

        mockMvc.perform(post("/api/devices/12/remote").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"viewOnly\":false}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sessionId").value(SESSION))
                .andExpect(jsonPath("$.deviceId").value(12))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.relayUrl").value("wss://relay.example.uz/viewer"))
                .andExpect(jsonPath("$.viewerTicket").value("opaque.viewer.ticket"))
                .andExpect(jsonPath("$.expiresAt").exists())
                .andExpect(jsonPath("$.viewOnly").value(false))
                .andExpect(jsonPath("$.deliveredVia").value("WS"))
                .andExpect(jsonPath("$.capability.supported").value(true))
                .andExpect(jsonPath("$.capability.input").value("ROOT"))
                .andExpect(jsonPath("$.capability.maxWidth").value(1280));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void start_withNoBody_defaultsToFullControl() throws Exception {
        when(remoteSessionService.start(eq(12L), eq(false), anyString()))
                .thenReturn(view("PENDING", "t", "WS"));

        mockMvc.perform(post("/api/devices/12/remote").with(csrf()))
                .andExpect(status().isCreated());

        verify(remoteSessionService).start(eq(12L), eq(false), anyString());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void start_viewOnlyTrue_isPassedThrough() throws Exception {
        when(remoteSessionService.start(eq(12L), eq(true), anyString()))
                .thenReturn(view("PENDING", "t", "WS"));

        mockMvc.perform(post("/api/devices/12/remote").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"viewOnly\":true}"))
                .andExpect(status().isCreated());

        verify(remoteSessionService).start(eq(12L), eq(true), anyString());
    }

    @Test
    @WithMockUser(username = "operator1", roles = "OPERATOR")
    void start_recordsTheCallerAsTheIssuer() throws Exception {
        when(remoteSessionService.start(eq(12L), anyBoolean(), eq("operator1")))
                .thenReturn(view("PENDING", "t", "WS"));

        mockMvc.perform(post("/api/devices/12/remote").with(csrf()))
                .andExpect(status().isCreated());

        verify(remoteSessionService).start(12L, false, "operator1");
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void start_offlineDevice_isStill201WithDeliveredViaHeartbeat() throws Exception {
        when(remoteSessionService.start(eq(12L), anyBoolean(), anyString()))
                .thenReturn(view("PENDING", "t", "HEARTBEAT"));

        mockMvc.perform(post("/api/devices/12/remote").with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deliveredVia").value("HEARTBEAT"));
    }

    // ----- POST /remote : negative -----

    @Test
    @WithMockUser(roles = "VIEWER")
    void start_asViewer_is403() throws Exception {
        mockMvc.perform(post("/api/devices/12/remote").with(csrf()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(remoteSessionService);
    }

    @Test
    @WithMockUser(roles = "ADVERTISER")
    void start_asAdvertiser_is403() throws Exception {
        mockMvc.perform(post("/api/devices/12/remote").with(csrf()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(remoteSessionService);
    }

    @Test
    void start_withNoAuth_is401() throws Exception {
        mockMvc.perform(post("/api/devices/12/remote").with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void start_withADeviceToken_is403() throws Exception {
        // A device must never be able to open a session on itself — start is an operator action.
        mockMvc.perform(post("/api/devices/12/remote").with(device(12)).with(csrf()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(remoteSessionService);
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void start_softDeletedOrUnknownDevice_is404() throws Exception {
        when(remoteSessionService.start(eq(99L), anyBoolean(), anyString()))
                .thenThrow(new ResourceNotFoundException("Device", 99L));

        mockMvc.perform(post("/api/devices/99/remote").with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void start_outOfOperatorScope_is404BeforeTheServiceIsCalled() throws Exception {
        doThrow(new ResourceNotFoundException("Device", 12L))
                .when(deviceManagementService).assertScopeForDevice(12L);

        mockMvc.perform(post("/api/devices/12/remote").with(csrf()))
                .andExpect(status().isNotFound());
        verify(remoteSessionService, never()).start(anyLong(), anyBoolean(), anyString());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void start_secondSession_is409WithANonEmptyHumanReadableMessage() throws Exception {
        // A 409 without a message is a silent failure — the frontend renders it verbatim.
        when(remoteSessionService.start(eq(12L), anyBoolean(), anyString()))
                .thenThrow(new IllegalStateException(
                        "A remote session for device \"TV-1\" is already ACTIVE. Stop it before starting a new one."));

        mockMvc.perform(post("/api/devices/12/remote").with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("already ACTIVE")));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void start_deviceReportedUnsupported_is422() throws Exception {
        when(remoteSessionService.start(eq(12L), anyBoolean(), anyString()))
                .thenThrow(new RemoteCapabilityUnsupportedException(
                        "Device \"TV-1\" reported that it does not support remote control."));

        mockMvc.perform(post("/api/devices/12/remote").with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void start_whenFeatureDisabled_is503() throws Exception {
        when(remoteSessionService.start(eq(12L), anyBoolean(), anyString()))
                .thenThrow(new RemoteControlDisabledException("Remote control is not enabled on this server."));

        mockMvc.perform(post("/api/devices/12/remote").with(csrf()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    // ----- DELETE /remote/{sessionKey} -----

    @Test
    @WithMockUser(username = "operator1", roles = "OPERATOR")
    void stop_returns204AndNamesTheActor() throws Exception {
        mockMvc.perform(delete("/api/devices/12/remote/" + SESSION).with(csrf()))
                .andExpect(status().isNoContent());

        verify(remoteSessionService).stop(12L, SESSION, "operator1");
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void stop_alreadyTerminal_isStill204() throws Exception {
        // The service is idempotent; the controller must not invent an error status for it.
        mockMvc.perform(delete("/api/devices/12/remote/" + SESSION).with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void stop_asViewer_is403() throws Exception {
        mockMvc.perform(delete("/api/devices/12/remote/" + SESSION).with(csrf()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(remoteSessionService);
    }

    @Test
    @WithMockUser(roles = "ADVERTISER")
    void stop_asAdvertiser_is403() throws Exception {
        mockMvc.perform(delete("/api/devices/12/remote/" + SESSION).with(csrf()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(remoteSessionService);
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void stop_unknownSession_is404() throws Exception {
        doThrow(new ResourceNotFoundException("RemoteSession", SESSION))
                .when(remoteSessionService).stop(eq(12L), eq(SESSION), anyString());

        mockMvc.perform(delete("/api/devices/12/remote/" + SESSION).with(csrf()))
                .andExpect(status().isNotFound());
    }

    // ----- GET /remote -----

    @Test
    @WithMockUser(roles = "OPERATOR")
    void current_returns200WithoutAViewerTicket() throws Exception {
        // Tickets are single-issue — a reconnecting viewer must POST again.
        when(remoteSessionService.current(12L))
                .thenReturn(Optional.of(view("ACTIVE", "must-not-be-returned", "WS")));

        mockMvc.perform(get("/api/devices/12/remote"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(SESSION))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.viewerTicket").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void current_noLiveSession_is404() throws Exception {
        when(remoteSessionService.current(12L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/devices/12/remote"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void current_asViewer_is403() throws Exception {
        mockMvc.perform(get("/api/devices/12/remote"))
                .andExpect(status().isForbidden());
    }

    // ----- POST /remote/{sessionKey}/ack (device surface) -----

    @Test
    void ack_withADeviceToken_returns200() throws Exception {
        when(remoteSessionService.ack(eq(12L), eq(SESSION), eq(AckStatus.READY), eq(1280), eq(720), any(), any()))
                .thenReturn(view("ACTIVE", null, null));

        mockMvc.perform(post("/api/devices/12/remote/" + SESSION + "/ack").with(device(12)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"READY\",\"width\":1280,\"height\":720}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(SESSION))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.viewerTicket").doesNotExist());
    }

    @Test
    void ack_failedStatus_passesTheErrorThrough() throws Exception {
        when(remoteSessionService.ack(eq(12L), eq(SESSION), eq(AckStatus.FAILED), any(), any(),
                eq("su: not found"), any()))
                .thenReturn(view("FAILED", null, null));

        mockMvc.perform(post("/api/devices/12/remote/" + SESSION + "/ack").with(device(12)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"FAILED\",\"error\":\"su: not found\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
    }

    @Test
    void ack_endedStatus_passesTheReasonThrough() throws Exception {
        when(remoteSessionService.ack(eq(12L), eq(SESSION), eq(AckStatus.ENDED), any(), any(),
                any(), eq("RELAY_LOST")))
                .thenReturn(view("ENDED", null, null));

        mockMvc.perform(post("/api/devices/12/remote/" + SESSION + "/ack").with(device(12)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ENDED\",\"reason\":\"RELAY_LOST\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENDED"));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void ack_withAnOperatorJwt_is403() throws Exception {
        // /ack is the DEVICE surface. An operator JWT must never be able to fake a device ack.
        mockMvc.perform(post("/api/devices/12/remote/" + SESSION + "/ack").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"READY\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(remoteSessionService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void ack_withAnAdminJwt_is403() throws Exception {
        mockMvc.perform(post("/api/devices/12/remote/" + SESSION + "/ack").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"READY\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(remoteSessionService);
    }

    @Test
    void ack_withATokenForADifferentDevice_is403() throws Exception {
        // Cross-device IDOR: device 6's token cannot ack on device 12's path.
        mockMvc.perform(post("/api/devices/12/remote/" + SESSION + "/ack").with(device(6)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"READY\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(remoteSessionService);
    }

    @Test
    void ack_withNoAuth_is401() throws Exception {
        mockMvc.perform(post("/api/devices/12/remote/" + SESSION + "/ack").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"READY\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void ack_missingStatus_is400() throws Exception {
        mockMvc.perform(post("/api/devices/12/remote/" + SESSION + "/ack").with(device(12)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(remoteSessionService);
    }

    @Test
    void ack_unknownStatusValue_is400NotASilentlyIgnoredAck() throws Exception {
        mockMvc.perform(post("/api/devices/12/remote/" + SESSION + "/ack").with(device(12)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"TELEPORTED\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(remoteSessionService);
    }

    @Test
    void ack_onATerminalSession_is409() throws Exception {
        when(remoteSessionService.ack(anyLong(), anyString(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException(
                        "Remote session " + SESSION + " is already ENDED and cannot be acknowledged again."));

        mockMvc.perform(post("/api/devices/12/remote/" + SESSION + "/ack").with(device(12)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"READY\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void ack_forASessionOwnedByAnotherDevice_is404NotForbidden() throws Exception {
        when(remoteSessionService.ack(anyLong(), anyString(), any(), any(), any(), any(), any()))
                .thenThrow(new ResourceNotFoundException("RemoteSession", SESSION));

        mockMvc.perform(post("/api/devices/12/remote/" + SESSION + "/ack").with(device(12)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"READY\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void ack_doesNotRunTheOperatorScopeGate() throws Exception {
        // The device path has no operator; calling the scope resolver there would explode.
        when(remoteSessionService.ack(anyLong(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(view("ACTIVE", null, null));

        mockMvc.perform(post("/api/devices/12/remote/" + SESSION + "/ack").with(device(12)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"READY\"}"))
                .andExpect(status().isOk());

        verifyNoInteractions(deviceManagementService);
    }
}
