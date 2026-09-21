package uz.orientadvertise.services.api.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uz.orientadvertise.services.api.controller.DeviceController;
import uz.orientadvertise.services.api.security.JwtAuthenticationFilter;
import uz.orientadvertise.services.api.security.SecurityConfig;
import uz.orientadvertise.services.domain.audit.AuditRecorder;
import uz.orientadvertise.services.domain.auth.TokenValidator;
import uz.orientadvertise.services.domain.auth.UserActiveChecker;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.service.DeviceHeartbeatService;
import uz.orientadvertise.services.service.DeviceHeartbeatService.HeartbeatResult;
import uz.orientadvertise.services.service.DeviceRegistrationService;
import uz.orientadvertise.services.service.DeviceRegistrationService.RegistrationResult;
import uz.orientadvertise.services.service.DeviceSyncService;
import uz.orientadvertise.services.service.DeviceSyncService.ConfirmResult;
import uz.orientadvertise.services.service.DeviceSyncService.ConfirmStatus;
import uz.orientadvertise.services.service.DeviceSyncService.PlaylistView;
import uz.orientadvertise.services.service.DeviceSyncService.PlaylistViewItem;
import uz.orientadvertise.services.service.DeviceSyncService.SyncFileToAdd;
import uz.orientadvertise.services.service.DeviceSyncService.PlaylistEntry;
import uz.orientadvertise.services.service.DeviceSyncService.SyncPlan;
import uz.orientadvertise.services.service.PlaylistControlService;
import uz.orientadvertise.services.service.PlaylistControlService.ControlAction;
import uz.orientadvertise.services.domain.model.RemoteAction;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DeviceController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class DeviceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DeviceRegistrationService registrationService;

    @MockitoBean
    private uz.orientadvertise.services.service.DeviceRegistrationRateLimiter registrationRateLimiter;

    @MockitoBean
    private DeviceHeartbeatService heartbeatService;

    @MockitoBean
    private uz.orientadvertise.services.service.DeviceManagementService managementService;

    @MockitoBean
    private DeviceSyncService syncService;

    @MockitoBean
    private PlaylistControlService playlistControlService;

    @MockitoBean
    private uz.orientadvertise.services.service.DeviceActionService deviceActionService;

    @MockitoBean
    private uz.orientadvertise.services.service.RemoteActionService remoteActionService;

    @MockitoBean
    private uz.orientadvertise.services.service.DeviceDiagnosticsService diagnosticsService;

    /** Live socket map behind GET /{id}/connection — the port, not the WS handler itself. */
    @MockitoBean
    private uz.orientadvertise.services.domain.content.DevicePushChannel devicePushChannel;

    @MockitoBean
    private TokenValidator tokenValidator;

    @MockitoBean
    private UserActiveChecker userActiveChecker;

    @MockitoBean
    private AuditRecorder auditRecorder;

    /**
     * Device-agent auth: principal = the device id (Long), authority ROLE_DEVICE — exactly
     * what DeviceTokenAuthFilter sets. The id must match the path id, or @PreAuthorize 403s.
     */
    private static org.springframework.test.web.servlet.request.RequestPostProcessor device(long id) {
        return authentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                id, null, List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_DEVICE"))));
    }

    @Test
    void register_newDevice_returns201() throws Exception {
        when(registrationService.register(eq("SN-001"), any()))
                .thenReturn(new RegistrationResult(1L, "dtk_abc123", "SN-001", true, "reg--1"));

        mockMvc.perform(post("/api/devices/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serialNumber\":\"SN-001\",\"deviceName\":\"TV-1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deviceId").value(1))
                .andExpect(jsonPath("$.deviceToken").value("dtk_abc123"))
                .andExpect(jsonPath("$.serialNumber").value("SN-001"))
                .andExpect(jsonPath("$.status").value("registered"))
                // A freshly-registered device sits on the sentinel region until an operator places it,
                // so its sync group is the region-level fallback; the real group arrives via heartbeat.
                .andExpect(jsonPath("$.syncGroupId").value("reg--1"));
    }

    @Test
    void register_existingDevice_returns200() throws Exception {
        when(registrationService.register(eq("SN-002"), any()))
                .thenReturn(new RegistrationResult(2L, "dtk_xyz789", "SN-002", false, null));

        mockMvc.perform(post("/api/devices/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serialNumber\":\"SN-002\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("re-registered"));
    }

    @Test
    void register_alreadyRegisteredWithoutAdminWindow_returns409_onTheReregistrationBudget() throws Exception {
        // AUTH-02: refused takeover. Counted on the re-registration budget, not the new-device
        // one, so a wiped box's retries don't starve new boxes behind the same NAT.
        when(registrationService.isRegistered("SN-TAKEN")).thenReturn(true);
        when(registrationService.register(eq("SN-TAKEN"), any()))
                .thenThrow(new uz.orientadvertise.services.service.exception.DeviceAlreadyRegisteredException("SN-TAKEN", 9L));

        mockMvc.perform(post("/api/devices/register")
                        .with(request -> { request.setRemoteAddr("203.0.113.7"); return request; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serialNumber\":\"SN-TAKEN\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.deviceToken").doesNotExist());

        verify(registrationRateLimiter).checkReregistration("203.0.113.7");
        org.mockito.Mockito.verify(registrationRateLimiter, org.mockito.Mockito.never()).check(any());
    }

    @Test
    void register_newSerial_usesTheNewDeviceBudget() throws Exception {
        when(registrationService.register(eq("SN-OK"), any()))
                .thenReturn(new RegistrationResult(4L, "dtk_ok", "SN-OK", true, null));

        mockMvc.perform(post("/api/devices/register")
                        .with(request -> { request.setRemoteAddr("203.0.113.8"); return request; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serialNumber\":\"SN-OK\"}"))
                .andExpect(status().isCreated());

        verify(registrationRateLimiter).check("203.0.113.8");
        org.mockito.Mockito.verify(registrationRateLimiter, org.mockito.Mockito.never()).checkReregistration(any());
    }

    @Test
    void register_rateLimitKey_isTheResolvedRemoteAddress_notAClientSuppliedForwardedFor() throws Exception {
        // AUTH-04: RemoteIpValve resolves forwarding (trusted proxies only); the controller must not.
        when(registrationService.register(eq("SN-XFF"), any()))
                .thenReturn(new RegistrationResult(6L, "dtk_xff", "SN-XFF", true, null));

        mockMvc.perform(post("/api/devices/register")
                        .with(request -> { request.setRemoteAddr("203.0.113.9"); return request; })
                        .header("X-Forwarded-For", "9.9.9.9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serialNumber\":\"SN-XFF\"}"))
                .andExpect(status().isCreated());

        verify(registrationRateLimiter).check("203.0.113.9");
    }

    @Test
    void register_serialWithTrailingNewline_returns400() throws Exception {
        mockMvc.perform(post("/api/devices/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serialNumber\":\"SN-1\\n\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(registrationService);
    }

    @Test
    void allowReregistration_anonymous_returns401() throws Exception {
        mockMvc.perform(post("/api/devices/7/reregistration-window"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(registrationService);
    }

    @Test
    void register_overlongSerial_returns400() throws Exception {
        mockMvc.perform(post("/api/devices/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serialNumber\":\"" + "A".repeat(101) + "\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(registrationService);
    }

    @Test
    void register_serialWithFormulaOrIllegalCharacters_returns400() throws Exception {
        for (String serial : new String[] {"=HYPERLINK(1)", "-SN", "SN 1", "SN\\u0000"}) {
            mockMvc.perform(post("/api/devices/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"serialNumber\":\"" + serial + "\"}"))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(registrationService);
    }

    @Test
    void register_realWorldSerialFormats_areAccepted() throws Exception {
        // FAKE-TV-n seeds, ANDROID_ID hex, a persisted UUID, a MAC-style hardware id.
        for (String serial : new String[] {"FAKE-TV-1", "9774d56d682e549c", "3f2504e0-4f89-11d3-9a0c-0305e82c3301",
                "00:1A:2B:3C:4D:5E"}) {
            when(registrationService.register(eq(serial), any()))
                    .thenReturn(new RegistrationResult(5L, "dtk_fmt", serial, true, null));
            mockMvc.perform(post("/api/devices/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"serialNumber\":\"" + serial + "\"}"))
                    .andExpect(status().isCreated());
        }
    }

    @Test
    void register_overlongDeviceName_returns400() throws Exception {
        mockMvc.perform(post("/api/devices/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serialNumber\":\"SN-NAME\",\"deviceName\":\"" + "n".repeat(201) + "\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(registrationService);
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
    void allowReregistration_admin_returnsWindowEnd() throws Exception {
        when(registrationService.allowReregistration(7L)).thenReturn(Instant.parse("2026-09-19T12:00:00Z"));

        mockMvc.perform(post("/api/devices/7/reregistration-window"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowedUntil").value("2026-09-19T12:00:00Z"));
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "OPERATOR")
    void allowReregistration_operator_forbidden() throws Exception {
        mockMvc.perform(post("/api/devices/7/reregistration-window"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(registrationService);
    }

    @Test
    void allowReregistration_deviceToken_forbidden() throws Exception {
        mockMvc.perform(post("/api/devices/7/reregistration-window").with(device(7L)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(registrationService);
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
    void allowReregistration_unknownDevice_returns404() throws Exception {
        when(registrationService.allowReregistration(404L)).thenThrow(new ResourceNotFoundException("Device", 404L));

        mockMvc.perform(post("/api/devices/404/reregistration-window"))
                .andExpect(status().isNotFound());
    }

    @Test
    void register_missingSerialNumber_returns400() throws Exception {
        mockMvc.perform(post("/api/devices/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceName\":\"TV-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    @Test
    void register_noAuthRequired() throws Exception {
        when(registrationService.register(eq("SN-003"), any()))
                .thenReturn(new RegistrationResult(3L, "dtk_noauth", "SN-003", true, null));

        // No Authorization header — should still work (permitAll)
        mockMvc.perform(post("/api/devices/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serialNumber\":\"SN-003\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void unregisteredDevice_callingProtectedEndpoint_gets401() throws Exception {
        // Any endpoint other than /service/devices/register requires authentication
        // An unregistered device has no JWT → 401
        mockMvc.perform(post("/api/files")
                        .contentType(MediaType.MULTIPART_FORM_DATA_VALUE))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void heartbeat_knownDevice_returns200WithPendingActions() throws Exception {
        when(heartbeatService.processHeartbeat(org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new HeartbeatResult(1L, Device.Status.ONLINE, List.of()));

        mockMvc.perform(post("/api/devices/1/heartbeat").with(device(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceId").value(1))
                .andExpect(jsonPath("$.status").value("ONLINE"))
                .andExpect(jsonPath("$.pendingActions").isArray())
                .andExpect(jsonPath("$.pendingActions").isEmpty())
                .andExpect(jsonPath("$.serverTime").exists());
    }

    @Test
    void heartbeat_resolvesRecoveredIncidents_afterTheBeatReturns_withItsResult() throws Exception {
        // processHeartbeat commits when it returns; only then may the recovered incidents be
        // closed — one pooled connection at a time, and never able to roll the beat back.
        var result = new HeartbeatResult(1L, Device.Status.ONLINE, List.of(), null, false, 100, null, null,
                List.of("DEVICE_OFFLINE"));
        when(heartbeatService.processHeartbeat(eq(1L), any(), any(), any(), any())).thenReturn(result);

        mockMvc.perform(post("/api/devices/1/heartbeat").with(device(1)))
                .andExpect(status().isOk())
                // Server-internal: the resolve list never reaches the wire.
                .andExpect(jsonPath("$.resolveIncidentTypes").doesNotExist());

        var order = org.mockito.Mockito.inOrder(heartbeatService);
        order.verify(heartbeatService).processHeartbeat(eq(1L), any(), any(), any(), any());
        order.verify(heartbeatService).resolveRecoveredIncidents(1L, result);
    }

    @Test
    void heartbeat_unknownDevice_neverResolvesAnything() throws Exception {
        when(heartbeatService.processHeartbeat(eq(998L), any(), any(), any(), any()))
                .thenThrow(new ResourceNotFoundException("Device", 998L));

        mockMvc.perform(post("/api/devices/998/heartbeat").with(device(998)))
                .andExpect(status().isNotFound());

        verify(heartbeatService, org.mockito.Mockito.never()).resolveRecoveredIncidents(any(), any());
    }

    @Test
    void heartbeat_unknownDevice_returns404() throws Exception {
        when(heartbeatService.processHeartbeat(org.mockito.ArgumentMatchers.eq(999L), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new ResourceNotFoundException("Device", 999L));

        mockMvc.perform(post("/api/devices/999/heartbeat").with(device(999)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void heartbeat_emptyPendingActions_stillReturnsArray() throws Exception {
        when(heartbeatService.processHeartbeat(org.mockito.ArgumentMatchers.eq(2L), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new HeartbeatResult(2L, Device.Status.ONLINE, List.of()));

        mockMvc.perform(post("/api/devices/2/heartbeat").with(device(2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingActions").isArray())
                .andExpect(jsonPath("$.pendingActions.length()").value(0));
    }

    @Test
    void heartbeat_noToken_returns401() throws Exception {
        // Device endpoints now require a device token — no auth → 401.
        mockMvc.perform(post("/api/devices/5/heartbeat"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void heartbeat_tokenForDifferentDevice_returns403() throws Exception {
        // A valid token for device 6 cannot drive device 5's heartbeat (cross-device IDOR).
        mockMvc.perform(post("/api/devices/5/heartbeat").with(device(6)))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------------------------------
    // Volume control (PROMPT-device-volume-control §8)
    // ---------------------------------------------------------------------------------------

    @Test
    void heartbeat_roundTripsVolumeIntoDesiredVolume() throws Exception {
        // Device reports its current volume (45); the resolved target (70) comes back as desiredVolume.
        // The resolved sync group ("fac-9") is echoed alongside so a relocated device re-groups every beat.
        when(heartbeatService.processHeartbeat(eq(1L), any(), any(), eq(45), any()))
                .thenReturn(new HeartbeatResult(1L, Device.Status.ONLINE, List.of(), null, false, 70, "fac-9"));

        mockMvc.perform(post("/api/devices/1/heartbeat").with(device(1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentVersion\":\"v1\",\"volume\":45}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceId").value(1))
                .andExpect(jsonPath("$.desiredVolume").value(70))
                .andExpect(jsonPath("$.syncGroupId").value("fac-9"));
    }

    // ---------------------------------------------------------------------------------------
    // Remote view/control — heartbeat capability up / desired state down, and live liveness
    // ---------------------------------------------------------------------------------------

    @Test
    void heartbeat_withRemoteCapabilityBlock_isLiftedOntoTheService() throws Exception {
        var captured = org.mockito.ArgumentCaptor.forClass(
                uz.orientadvertise.services.service.DeviceHeartbeatService.RemoteCapabilityReport.class);
        when(heartbeatService.processHeartbeat(eq(1L), any(), any(), any(), any()))
                .thenReturn(new HeartbeatResult(1L, Device.Status.ONLINE, List.of()));

        mockMvc.perform(post("/api/devices/1/heartbeat").with(device(1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contentVersion":"c6fe","volume":45,
                                 "remote":{"supported":true,"input":"ROOT","transport":"SCRCPY_WS",
                                           "maxWidth":1280,"maxHeight":720}}"""))
                .andExpect(status().isOk());

        verify(heartbeatService).processHeartbeat(eq(1L), eq("c6fe"), any(), eq(45), captured.capture());
        org.junit.jupiter.api.Assertions.assertEquals(true, captured.getValue().supported());
        org.junit.jupiter.api.Assertions.assertEquals("ROOT", captured.getValue().input());
        org.junit.jupiter.api.Assertions.assertEquals("SCRCPY_WS", captured.getValue().transport());
        org.junit.jupiter.api.Assertions.assertEquals(1280, captured.getValue().maxWidth());
        org.junit.jupiter.api.Assertions.assertEquals(720, captured.getValue().maxHeight());
    }

    @Test
    void heartbeat_oldClientWithoutRemoteField_succeedsAndSendsNullCapability() throws Exception {
        // The back-compat guarantee: an un-upgraded device's exact body still works, and the
        // response is byte-for-byte the old one apart from the new nullable key.
        var captured = org.mockito.ArgumentCaptor.forClass(
                uz.orientadvertise.services.service.DeviceHeartbeatService.RemoteCapabilityReport.class);
        when(heartbeatService.processHeartbeat(eq(1L), any(), any(), any(), any()))
                .thenReturn(new HeartbeatResult(1L, Device.Status.ONLINE, List.of(), "c6fe", false, 70, "fac-9"));

        mockMvc.perform(post("/api/devices/1/heartbeat").with(device(1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentVersion\":\"c6fe\",\"volume\":45}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceId").value(1))
                .andExpect(jsonPath("$.status").value("ONLINE"))
                .andExpect(jsonPath("$.expectedContentVersion").value("c6fe"))
                .andExpect(jsonPath("$.syncRequired").value(false))
                .andExpect(jsonPath("$.desiredVolume").value(70))
                .andExpect(jsonPath("$.syncGroupId").value("fac-9"))
                .andExpect(jsonPath("$.pendingActions").isArray())
                .andExpect(jsonPath("$.serverTime").exists())
                .andReturn();

        // Pin the actual wire: the ONLY difference from the pre-feature response is the added
        // key, emitted as null. Existing clients ignore unknown/extra fields, so they are
        // unaffected — this is the "byte-for-byte unchanged apart from the new nullable field"
        // guarantee, asserted on the raw body rather than through a null-tolerant JsonPath.
        var body = mockMvc.perform(post("/api/devices/1/heartbeat").with(device(1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentVersion\":\"c6fe\",\"volume\":45}"))
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("\"desiredRemoteSession\":null"), body);

        verify(heartbeatService, org.mockito.Mockito.atLeastOnce())
                .processHeartbeat(eq(1L), eq("c6fe"), any(), eq(45), captured.capture());
        org.junit.jupiter.api.Assertions.assertNull(captured.getValue());
    }

    @Test
    void heartbeat_unknownFieldsInRemoteBlock_doNotFailTheBeat() throws Exception {
        // Forward compatibility in the other direction: a newer device sending extra keys.
        when(heartbeatService.processHeartbeat(eq(1L), any(), any(), any(), any()))
                .thenReturn(new HeartbeatResult(1L, Device.Status.ONLINE, List.of()));

        mockMvc.perform(post("/api/devices/1/heartbeat").with(device(1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"remote\":{\"supported\":true,\"input\":\"ROOT\",\"codec\":\"h265\"}}"))
                .andExpect(status().isOk());
    }

    @Test
    void heartbeat_desiredRemoteSession_isRenderedWhenTheServerWantsOne() throws Exception {
        var desired = new uz.orientadvertise.services.service.RemoteSessionService.DesiredRemoteSession(
                "rs_7f3a91c4b8e24d5a", "wss://relay.example.uz/agent", "opaque.agent.ticket",
                Instant.parse("2026-08-27T10:45:00Z"), false, 1280, 15, 2_000_000);
        when(heartbeatService.processHeartbeat(eq(1L), any(), any(), any(), any()))
                .thenReturn(new HeartbeatResult(1L, Device.Status.ONLINE, List.of(), null, false,
                        70, "fac-9", desired));

        mockMvc.perform(post("/api/devices/1/heartbeat").with(device(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.desiredRemoteSession.sessionId").value("rs_7f3a91c4b8e24d5a"))
                .andExpect(jsonPath("$.desiredRemoteSession.relayUrl").value("wss://relay.example.uz/agent"))
                .andExpect(jsonPath("$.desiredRemoteSession.agentTicket").value("opaque.agent.ticket"))
                .andExpect(jsonPath("$.desiredRemoteSession.viewOnly").value(false))
                .andExpect(jsonPath("$.desiredRemoteSession.maxWidth").value(1280))
                .andExpect(jsonPath("$.desiredRemoteSession.maxFps").value(15))
                .andExpect(jsonPath("$.desiredRemoteSession.bitRate").value(2000000));
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "OPERATOR")
    void connection_reportsLiveSocketState_notTheLaggingView() throws Exception {
        when(devicePushChannel.isConnected(7L)).thenReturn(true);

        mockMvc.perform(get("/api/devices/7/connection"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceId").value(7))
                .andExpect(jsonPath("$.connected").value(true));
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "VIEWER")
    void connection_isReadableByViewer() throws Exception {
        when(devicePushChannel.isConnected(7L)).thenReturn(false);

        mockMvc.perform(get("/api/devices/7/connection"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(false));
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "ADVERTISER")
    void connection_asAdvertiser_is403() throws Exception {
        mockMvc.perform(get("/api/devices/7/connection"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(devicePushChannel);
    }

    @Test
    void connection_withNoAuth_is401() throws Exception {
        mockMvc.perform(get("/api/devices/7/connection"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "OPERATOR")
    void connection_outOfOperatorScope_is404() throws Exception {
        org.mockito.Mockito.doThrow(new ResourceNotFoundException("Device", 7L))
                .when(managementService).assertScopeForDevice(7L);

        mockMvc.perform(get("/api/devices/7/connection"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(devicePushChannel);
    }

    @Test
    void time_returnsServerUnixMs_andRequiresDeviceToken() throws Exception {
        // The clock endpoint is deliberately service-free (no processHeartbeat interaction): a device
        // pings it several times to estimate its offset, so it must stay allocation-cheap.
        long before = System.currentTimeMillis();
        mockMvc.perform(get("/api/devices/1/time").with(device(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serverUnixMs").isNumber())
                .andExpect(jsonPath("$.serverUnixMs").value(org.hamcrest.Matchers.greaterThanOrEqualTo(before)));

        verifyNoInteractions(heartbeatService);
    }

    @Test
    void time_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/devices/1/time"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void time_tokenForDifferentDevice_returns403() throws Exception {
        // Same cross-device IDOR guard as the other device endpoints (token must own the path id).
        mockMvc.perform(get("/api/devices/5/time").with(device(6)))
                .andExpect(status().isForbidden());
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "OPERATOR")
    void setDeviceVolume_valid_returns204_andDelegates() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/devices/140/volume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"volume\":40}"))
                .andExpect(status().isNoContent());

        verify(managementService).setVolume(140L, 40);
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "OPERATOR")
    void setDeviceVolume_outOfRange_returns400_byBeanValidation() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/devices/141/volume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"volume\":150}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("volume"));
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "OPERATOR")
    void setDeviceVolume_unknownOrOutOfScope_returns404() throws Exception {
        // assertInScope / missing device surfaces as ResourceNotFoundException → 404.
        org.mockito.Mockito.doThrow(new ResourceNotFoundException("Device", 142L))
                .when(managementService).setVolume(eq(142L), eq(40));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/devices/142/volume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"volume\":40}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "OPERATOR")
    void clearDeviceVolume_returns204_andDelegates() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/devices/143/volume"))
                .andExpect(status().isNoContent());

        verify(managementService).clearVolume(143L);
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "OPERATOR")
    void applyVolumeToAll_operator_returnsAffectedCount() throws Exception {
        // Operator scope is enforced inside the service; the controller just returns the count.
        when(managementService.setVolumeForAll(60)).thenReturn(3);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/devices/volume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"volume\":60}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affected").value(3));
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
    void applyVolumeToAll_admin_returnsLargerAffectedCount() throws Exception {
        // ADMIN has no scope restriction — the stubbed count just passes through.
        when(managementService.setVolumeForAll(60)).thenReturn(42);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/devices/volume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"volume\":60}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affected").value(42));
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "OPERATOR")
    void applyVolumeToAll_outOfRange_returns400() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/devices/volume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"volume\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("volume"));
    }

    @Test
    void sync_freshDevice_nullVersion_returnsFullSync() throws Exception {
        var plan = new SyncPlan(7L, "v-abc", true,
                List.of(new SyncFileToAdd(10L, "ad.mp4", "video/mp4", 1024L, 30, "sha", "https://minio/p?sig=1")),
                List.of(),
                List.of(new PlaylistEntry(0, 0, 10L, 30, 0L, 30_000L)),
                60, Instant.parse("2030-01-01T00:00:00Z"),
                "fac-9", 1_719_830_400_000L, 30_000L, 1_719_830_400_000L);
        when(syncService.computeSyncPlan(eq(7L), any(), any())).thenReturn(plan);

        mockMvc.perform(get("/api/devices/7/sync").with(device(7)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceId").value(7))
                .andExpect(jsonPath("$.fullSync").value(true))
                .andExpect(jsonPath("$.expectedContentVersion").value("v-abc"))
                .andExpect(jsonPath("$.filesToAdd[0].fileId").value(10))
                .andExpect(jsonPath("$.filesToAdd[0].presignedUrl").value("https://minio/p?sig=1"))
                .andExpect(jsonPath("$.filesToDelete").isArray())
                .andExpect(jsonPath("$.filesToDelete").isEmpty())
                .andExpect(jsonPath("$.playlistOrder[0].index").value(0))
                .andExpect(jsonPath("$.playlistOrder[0].position").value(0))
                .andExpect(jsonPath("$.playlistOrder[0].fileId").value(10))
                .andExpect(jsonPath("$.playlistOrder[0].durationSeconds").value(30))
                // Synchronized-playback schedule block (§1.3): slot timeline + group + anchor.
                .andExpect(jsonPath("$.playlistOrder[0].slotStartMs").value(0))
                .andExpect(jsonPath("$.playlistOrder[0].slotDurationMs").value(30000))
                .andExpect(jsonPath("$.syncGroupId").value("fac-9"))
                .andExpect(jsonPath("$.anchorEpochMs").value(1719830400000L))
                .andExpect(jsonPath("$.loopDurationMs").value(30000))
                .andExpect(jsonPath("$.activateAt").value(1719830400000L))
                .andExpect(jsonPath("$.presignedUrlExpiryMinutes").value(60));
    }

    @Test
    void sync_withCurrentFileIds_returnsDiff() throws Exception {
        var plan = new SyncPlan(8L, "v-new", false,
                List.of(new SyncFileToAdd(20L, "new.mp4", "video/mp4", 5000L, 15, "h", "https://minio/new")),
                List.of(11L),
                List.of(new PlaylistEntry(0, 0, 12L, 10, 0L, 10_000L),
                        new PlaylistEntry(1, 1, 20L, 15, 10_000L, 15_000L)),
                60, Instant.parse("2030-01-01T00:00:00Z"),
                "grp-3", 1_719_830_400_000L, 25_000L, 1_719_830_400_000L);
        when(syncService.computeSyncPlan(eq(8L), eq("v-old"), eq(Set.of(11L, 12L)))).thenReturn(plan);

        mockMvc.perform(get("/api/devices/8/sync").with(device(8))
                        .param("currentVersion", "v-old")
                        .param("currentFileIds", "11", "12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullSync").value(false))
                .andExpect(jsonPath("$.filesToAdd.length()").value(1))
                .andExpect(jsonPath("$.filesToAdd[0].fileId").value(20))
                .andExpect(jsonPath("$.filesToDelete[0]").value(11))
                // Second slot starts exactly where the first ends; loop length is the sum.
                .andExpect(jsonPath("$.playlistOrder[1].slotStartMs").value(10000))
                .andExpect(jsonPath("$.playlistOrder[1].slotDurationMs").value(15000))
                .andExpect(jsonPath("$.loopDurationMs").value(25000))
                .andExpect(jsonPath("$.syncGroupId").value("grp-3"));
    }

    @Test
    void sync_unknownDevice_returns404() throws Exception {
        when(syncService.computeSyncPlan(eq(999L), any(), any()))
                .thenThrow(new ResourceNotFoundException("Device", 999L));

        mockMvc.perform(get("/api/devices/999/sync").with(device(999)))
                .andExpect(status().isNotFound());
    }

    @Test
    void sync_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/devices/3/sync"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void syncConfirm_matchingVersion_returnsConfirmed() throws Exception {
        when(syncService.confirmSync(eq(7L), eq("v-7")))
                .thenReturn(new ConfirmResult(7L, ConfirmStatus.CONFIRMED, "v-7", "v-7", false));

        mockMvc.perform(post("/api/devices/7/sync/confirm").with(device(7))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reportedVersion\":\"v-7\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.syncRequired").value(false))
                .andExpect(jsonPath("$.reportedVersion").value("v-7"));
    }

    @Test
    void syncConfirm_mismatchVersion_returnsMismatchAndAsksForResync() throws Exception {
        when(syncService.confirmSync(eq(8L), eq("v-wrong")))
                .thenReturn(new ConfirmResult(8L, ConfirmStatus.MISMATCH, "v-expected", "v-wrong", true));

        mockMvc.perform(post("/api/devices/8/sync/confirm").with(device(8))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reportedVersion\":\"v-wrong\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MISMATCH"))
                .andExpect(jsonPath("$.syncRequired").value(true))
                .andExpect(jsonPath("$.expectedVersion").value("v-expected"));
    }

    @Test
    void syncConfirm_missingReportedVersion_returns400() throws Exception {
        mockMvc.perform(post("/api/devices/9/sync/confirm").with(device(9))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    @Test
    void syncConfirm_unknownDevice_returns404() throws Exception {
        when(syncService.confirmSync(eq(999L), any()))
                .thenThrow(new ResourceNotFoundException("Device", 999L));

        mockMvc.perform(post("/api/devices/999/sync/confirm").with(device(999))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reportedVersion\":\"v-x\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void playlist_returnsOrderedItemsWithUrls() throws Exception {
        var view = new PlaylistView(11L, 100L, "Mall Loop", "v-11", 75, List.of(
                new PlaylistViewItem(0, 0, 10L, "ad.mp4", "video/mp4",
                        "https://minio/a", 30, "sha-a", 1024L),
                new PlaylistViewItem(1, 1, 11L, "promo.mp4", "video/mp4",
                        "https://minio/b", 45, "sha-b", 2048L)
        ));
        when(syncService.getPlaylistView(11L)).thenReturn(view);

        mockMvc.perform(get("/api/devices/11/playlist").with(device(11)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceId").value(11))
                .andExpect(jsonPath("$.playlistId").value(100))
                .andExpect(jsonPath("$.playlistName").value("Mall Loop"))
                .andExpect(jsonPath("$.contentVersion").value("v-11"))
                .andExpect(jsonPath("$.totalDurationSeconds").value(75))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].index").value(0))
                .andExpect(jsonPath("$.items[0].position").value(0))
                .andExpect(jsonPath("$.items[0].fileId").value(10))
                .andExpect(jsonPath("$.items[0].presignedUrl").value("https://minio/a"))
                .andExpect(jsonPath("$.items[1].index").value(1))
                .andExpect(jsonPath("$.items[1].position").value(1));
    }

    @Test
    void playlist_noAssignment_returnsEmptyArray_not404() throws Exception {
        // Edge case requirement: device with no assigned playlist must get 200 + empty
        // items, NOT 404. 404 is reserved for unknown deviceId.
        var view = new PlaylistView(12L, null, null, null, 0, List.of());
        when(syncService.getPlaylistView(12L)).thenReturn(view);

        mockMvc.perform(get("/api/devices/12/playlist").with(device(12)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playlistId").doesNotExist())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalDurationSeconds").value(0));
    }

    @Test
    void playlist_unknownDevice_returns404() throws Exception {
        when(syncService.getPlaylistView(999L))
                .thenThrow(new ResourceNotFoundException("Device", 999L));

        mockMvc.perform(get("/api/devices/999/playlist").with(device(999)))
                .andExpect(status().isNotFound());
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "OPERATOR")
    void diagnostics_liveDevice_returnsFullEnvelope() throws Exception {
        var view = new uz.orientadvertise.services.service.DeviceDiagnosticsService.DiagnosticsView(
                70L, "SN-70", "TV-Bar", "ONLINE",
                Instant.parse("2026-05-06T01:00:00Z"), "v-abc", "10.0.0.42", 3L,
                List.of(new uz.orientadvertise.services.service.DeviceDiagnosticsService.EventSummary(
                        1L, "DEVICE_OFFLINE", "CRITICAL", "{}",
                        Instant.parse("2026-05-06T00:50:00Z"))),
                List.of(new uz.orientadvertise.services.service.DeviceDiagnosticsService.ActionSummary(
                        2L, "REBOOT", "PENDING", "{}", "alice",
                        Instant.parse("2026-05-06T00:55:00Z"),
                        Instant.parse("2026-05-06T01:05:00Z"), null)),
                Instant.parse("2026-05-06T01:00:30Z"));
        when(diagnosticsService.getDiagnostics(70L)).thenReturn(view);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/devices/70/diagnostics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceId").value(70))
                .andExpect(jsonPath("$.lastKnownIp").value("10.0.0.42"))
                .andExpect(jsonPath("$.currentContentVersion").value("v-abc"))
                .andExpect(jsonPath("$.pendingActionCount").value(3))
                .andExpect(jsonPath("$.recentEvents.length()").value(1))
                .andExpect(jsonPath("$.recentEvents[0].priority").value("CRITICAL"))
                .andExpect(jsonPath("$.recentActions.length()").value(1));
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "VIEWER")
    void diagnostics_neverHeartbeated_returnsEmptyEnvelope_not404() throws Exception {
        var view = new uz.orientadvertise.services.service.DeviceDiagnosticsService.DiagnosticsView(
                71L, "SN-71", "TV-New", "UNREGISTERED",
                null, null, null, 0L,
                List.of(), List.of(),
                Instant.parse("2026-05-06T01:00:00Z"));
        when(diagnosticsService.getDiagnostics(71L)).thenReturn(view);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/devices/71/diagnostics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastHeartbeatAt").doesNotExist())
                .andExpect(jsonPath("$.lastKnownIp").doesNotExist())
                .andExpect(jsonPath("$.recentEvents").isArray())
                .andExpect(jsonPath("$.recentEvents").isEmpty())
                .andExpect(jsonPath("$.pendingActionCount").value(0));
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "OPERATOR")
    void diagnostics_unknownDevice_returns404() throws Exception {
        when(diagnosticsService.getDiagnostics(999L))
                .thenThrow(new ResourceNotFoundException("Device", 999L));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/devices/999/diagnostics"))
                .andExpect(status().isNotFound());
    }

    @Test
    void diagnostics_unauthenticated_returns401() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/devices/72/diagnostics"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "ADVERTISER")
    void diagnostics_advertiser_forbidden() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/devices/73/diagnostics"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getPendingActions_returnsList() throws Exception {
        var a = mockRemoteAction(50L, 40L, "{}", "REBOOT");
        when(remoteActionService.getPendingByDevice(40L)).thenReturn(List.of(a));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/devices/40/actions/pending").with(device(40)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].actionId").value(50))
                .andExpect(jsonPath("$[0].actionType").value("REBOOT"));
    }

    @Test
    void getPendingActions_emptyList_returns200() throws Exception {
        when(remoteActionService.getPendingByDevice(41L)).thenReturn(List.of());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/devices/41/actions/pending").with(device(41)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void getPendingActions_noToken_returns401() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/devices/42/actions/pending"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void confirmAction_success_returns200() throws Exception {
        when(remoteActionService.processDeviceConfirmation(eq(43L), eq(60L),
                eq(uz.orientadvertise.services.service.RemoteActionService.DeviceConfirmStatus.SUCCESS),
                any())).thenReturn(new uz.orientadvertise.services.service.RemoteActionService.DeviceConfirmResult(
                        60L,
                        uz.orientadvertise.services.service.RemoteActionService.DeviceConfirmOutcome.CONFIRMED,
                        uz.orientadvertise.services.domain.model.RemoteAction.Status.CONFIRMED));

        mockMvc.perform(post("/api/devices/43/actions/60/confirm").with(device(43))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUCCESS\",\"result\":\"{}\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("CONFIRMED"))
                .andExpect(jsonPath("$.finalStatus").value("CONFIRMED"));
    }

    @Test
    void confirmAction_unknownActionId_returns200WithUnknown() throws Exception {
        // Edge case requirement: unknown action ID — log and return 200.
        when(remoteActionService.processDeviceConfirmation(eq(44L), eq(99999L), any(), any()))
                .thenReturn(new uz.orientadvertise.services.service.RemoteActionService.DeviceConfirmResult(
                        99999L,
                        uz.orientadvertise.services.service.RemoteActionService.DeviceConfirmOutcome.UNKNOWN,
                        null));

        mockMvc.perform(post("/api/devices/44/actions/99999/confirm").with(device(44))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("UNKNOWN"));
    }

    @Test
    void confirmAction_lateConfirm_returnsConfirmedLate() throws Exception {
        when(remoteActionService.processDeviceConfirmation(eq(45L), eq(61L), any(), any()))
                .thenReturn(new uz.orientadvertise.services.service.RemoteActionService.DeviceConfirmResult(
                        61L,
                        uz.orientadvertise.services.service.RemoteActionService.DeviceConfirmOutcome.CONFIRMED_LATE,
                        uz.orientadvertise.services.domain.model.RemoteAction.Status.CONFIRMED_LATE));

        mockMvc.perform(post("/api/devices/45/actions/61/confirm").with(device(45))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("CONFIRMED_LATE"))
                .andExpect(jsonPath("$.finalStatus").value("CONFIRMED_LATE"));
    }

    @Test
    void confirmAction_failedReport_returns200WithFailed() throws Exception {
        when(remoteActionService.processDeviceConfirmation(eq(46L), eq(62L),
                eq(uz.orientadvertise.services.service.RemoteActionService.DeviceConfirmStatus.FAILED),
                any())).thenReturn(new uz.orientadvertise.services.service.RemoteActionService.DeviceConfirmResult(
                        62L,
                        uz.orientadvertise.services.service.RemoteActionService.DeviceConfirmOutcome.FAILED,
                        uz.orientadvertise.services.domain.model.RemoteAction.Status.FAILED));

        mockMvc.perform(post("/api/devices/46/actions/62/confirm").with(device(46))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"FAILED\",\"result\":\"err\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("FAILED"));
    }

    @Test
    void confirmAction_missingStatus_returns400() throws Exception {
        mockMvc.perform(post("/api/devices/47/actions/63/confirm").with(device(47))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    @Test
    void confirmAction_noToken_returns401() throws Exception {
        mockMvc.perform(post("/api/devices/48/actions/64/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUCCESS\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(username = "alice", roles = "OPERATOR")
    void issueAction_reboot_returns202() throws Exception {
        var action = mockRemoteAction(201L, 30L, "{}", "REBOOT");
        when(deviceActionService.issueAction(eq(30L),
                eq(uz.orientadvertise.services.domain.model.DeviceActionType.REBOOT),
                eq(null), any())).thenReturn(action);

        mockMvc.perform(post("/api/devices/30/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"REBOOT\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.actionType").value("REBOOT"))
                .andExpect(jsonPath("$.payload").value("{}"));
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "OPERATOR")
    void issueAction_volumeSet_returns202() throws Exception {
        var action = mockRemoteAction(202L, 31L, "{\"volume\":75}", "VOLUME_SET");
        when(deviceActionService.issueAction(eq(31L),
                eq(uz.orientadvertise.services.domain.model.DeviceActionType.VOLUME_SET),
                eq(75), any())).thenReturn(action);

        mockMvc.perform(post("/api/devices/31/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"VOLUME_SET\",\"volume\":75}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.payload").value("{\"volume\":75}"));
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "OPERATOR")
    void issueAction_volumeSetMissingVolume_returns400() throws Exception {
        when(deviceActionService.issueAction(eq(32L),
                eq(uz.orientadvertise.services.domain.model.DeviceActionType.VOLUME_SET),
                eq(null), any()))
                .thenThrow(new IllegalArgumentException("volume is required for VOLUME_SET"));

        mockMvc.perform(post("/api/devices/32/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"VOLUME_SET\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("volume")));
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "OPERATOR")
    void issueAction_queueCapHit_returns409() throws Exception {
        when(deviceActionService.issueAction(eq(33L),
                eq(uz.orientadvertise.services.domain.model.DeviceActionType.SYNC_CONTENT),
                eq(null), any()))
                .thenThrow(new IllegalStateException(
                        "Device 33 has 10 pending actions; queue cap is 10"));

        mockMvc.perform(post("/api/devices/33/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"SYNC_CONTENT\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("queue cap")));
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "OPERATOR")
    void issueAction_duplicateType_returns409() throws Exception {
        when(deviceActionService.issueAction(eq(34L),
                eq(uz.orientadvertise.services.domain.model.DeviceActionType.REBOOT),
                eq(null), any()))
                .thenThrow(new IllegalStateException(
                        "A PENDING 'REBOOT' action already exists for device 34"));

        mockMvc.perform(post("/api/devices/34/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"REBOOT\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("already exists")));
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "OPERATOR")
    void issueAction_invalidType_returns400() throws Exception {
        mockMvc.perform(post("/api/devices/35/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"NOT_A_REAL_TYPE\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "OPERATOR")
    void issueAction_missingType_returns400() throws Exception {
        mockMvc.perform(post("/api/devices/36/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "VIEWER")
    void issueAction_viewerRole_forbidden() throws Exception {
        mockMvc.perform(post("/api/devices/37/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"REBOOT\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "VIEWER")
    void volumeSet_viewerRole_forbidden() throws Exception {
        // Edge case requirement: VIEWER must NOT be able to issue VOLUME_SET.
        mockMvc.perform(post("/api/devices/130/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"VOLUME_SET\",\"volume\":50}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "ADVERTISER")
    void volumeSet_advertiserRole_forbidden() throws Exception {
        // Edge case requirement: ADVERTISER must NOT be able to issue VOLUME_SET.
        mockMvc.perform(post("/api/devices/131/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"VOLUME_SET\",\"volume\":50}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "ADVERTISER")
    void issueAction_advertiserRole_forbidden_forAnyAction() throws Exception {
        mockMvc.perform(post("/api/devices/132/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"REBOOT\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "OPERATOR")
    void volumeSet_negativeValue_rejectedByBeanValidation_returns400() throws Exception {
        // Bean Validation @Min(0) trips at request binding — the service is never called.
        mockMvc.perform(post("/api/devices/133/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"VOLUME_SET\",\"volume\":-10}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("volume"));
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "OPERATOR")
    void volumeSet_over100_rejectedByBeanValidation_returns400() throws Exception {
        mockMvc.perform(post("/api/devices/134/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"VOLUME_SET\",\"volume\":150}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("volume"));
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "OPERATOR")
    void volumeSet_exactlyZero_accepted() throws Exception {
        var action = mockRemoteAction(135L, 135L, "{\"volume\":0}", "VOLUME_SET");
        when(deviceActionService.issueAction(eq(135L),
                eq(uz.orientadvertise.services.domain.model.DeviceActionType.VOLUME_SET),
                eq(0), any())).thenReturn(action);

        mockMvc.perform(post("/api/devices/135/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"VOLUME_SET\",\"volume\":0}"))
                .andExpect(status().isAccepted());
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "OPERATOR")
    void volumeSet_exactly100_accepted() throws Exception {
        var action = mockRemoteAction(136L, 136L, "{\"volume\":100}", "VOLUME_SET");
        when(deviceActionService.issueAction(eq(136L),
                eq(uz.orientadvertise.services.domain.model.DeviceActionType.VOLUME_SET),
                eq(100), any())).thenReturn(action);

        mockMvc.perform(post("/api/devices/136/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"VOLUME_SET\",\"volume\":100}"))
                .andExpect(status().isAccepted());
    }

    @Test
    void issueAction_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/devices/38/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"REBOOT\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(username = "alice", roles = "OPERATOR")
    void playlistControl_next_returns202_withRemoteAction() throws Exception {
        var action = mockRemoteAction(101L, 7L, "{\"action\":\"NEXT\"}");
        when(playlistControlService.issueControl(eq(7L), eq(ControlAction.NEXT), eq(null), any()))
                .thenReturn(action);

        mockMvc.perform(post("/api/devices/7/playlist/control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"NEXT\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.actionId").value(101))
                .andExpect(jsonPath("$.deviceId").value(7))
                .andExpect(jsonPath("$.actionType").value("PLAYLIST_CONTROL"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.payload").value("{\"action\":\"NEXT\"}"));
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "OPERATOR")
    void playlistControl_jumpValid_returns202() throws Exception {
        var action = mockRemoteAction(102L, 8L, "{\"action\":\"JUMP\",\"position\":2}");
        when(playlistControlService.issueControl(eq(8L), eq(ControlAction.JUMP), eq(2), any()))
                .thenReturn(action);

        mockMvc.perform(post("/api/devices/8/playlist/control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"JUMP\",\"position\":2}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.payload").value("{\"action\":\"JUMP\",\"position\":2}"));
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "OPERATOR")
    void playlistControl_jumpOutOfRange_returns400_withoutQueuingAction() throws Exception {
        when(playlistControlService.issueControl(eq(9L), eq(ControlAction.JUMP), eq(99), any()))
                .thenThrow(new IllegalArgumentException("JUMP position 99 is out of range [0, 5)"));

        mockMvc.perform(post("/api/devices/9/playlist/control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"JUMP\",\"position\":99}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("out of range")));
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "OPERATOR")
    void playlistControl_missingAction_returns400() throws Exception {
        mockMvc.perform(post("/api/devices/10/playlist/control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "VIEWER")
    void playlistControl_viewerRole_forbidden() throws Exception {
        mockMvc.perform(post("/api/devices/11/playlist/control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"NEXT\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void playlistControl_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/devices/12/playlist/control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"NEXT\"}"))
                .andExpect(status().isUnauthorized());
    }

    private RemoteAction mockRemoteAction(Long actionId, Long deviceId, String payload) {
        return mockRemoteAction(actionId, deviceId, payload, "PLAYLIST_CONTROL");
    }

    private RemoteAction mockRemoteAction(Long actionId, Long deviceId, String payload, String actionType) {
        RemoteAction a = org.mockito.Mockito.mock(RemoteAction.class);
        when(a.getId()).thenReturn(actionId);
        when(a.getActionType()).thenReturn(actionType);
        when(a.getStatus()).thenReturn(RemoteAction.Status.PENDING);
        when(a.getPayload()).thenReturn(payload);
        when(a.getIssuedAt()).thenReturn(Instant.parse("2026-05-06T00:00:00Z"));
        when(a.getExpiresAt()).thenReturn(Instant.parse("2026-05-06T00:10:00Z"));
        when(a.getIssuedBy()).thenReturn("alice");
        uz.orientadvertise.services.domain.model.Device d = org.mockito.Mockito.mock(uz.orientadvertise.services.domain.model.Device.class);
        when(d.getId()).thenReturn(deviceId);
        when(a.getDevice()).thenReturn(d);
        return a;
    }

    @Test
    void playlist_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/devices/13/playlist"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "VIEWER")
    void list_unassignedTrueWithDeviceGroupId_returns400() throws Exception {
        when(managementService.list(any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException(
                        "unassigned=true is mutually exclusive with deviceGroupId"));

        mockMvc.perform(get("/api/devices")
                        .param("regionId", "5")
                        .param("deviceGroupId", "12")
                        .param("unassigned", "true"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("mutually exclusive")));
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "VIEWER")
    void list_hasActivePlaylist_trueFalseOmitted_allReturn200() throws Exception {
        when(managementService.list(any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        mockMvc.perform(get("/api/devices").param("hasActivePlaylist", "true"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/devices").param("hasActivePlaylist", "false"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/devices"))   // omitted
                .andExpect(status().isOk());
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "VIEWER")
    void list_hasActivePlaylist_nonBoolean_returns400() throws Exception {
        mockMvc.perform(get("/api/devices").param("hasActivePlaylist", "notabool"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "VIEWER")
    void list_responseCarriesActivePlaylistFields() throws Exception {
        var v = org.mockito.Mockito.mock(uz.orientadvertise.services.domain.model.DeviceStatusView.class);
        when(v.getId()).thenReturn(42L);
        when(v.getSerialNumber()).thenReturn("SN-001");
        when(v.getName()).thenReturn("Lobby TV");
        when(v.getComputedStatus()).thenReturn(Device.Status.ONLINE);
        when(v.getActivePlaylistId()).thenReturn(15L);
        when(v.getActivePlaylistName()).thenReturn("Summer Promo");
        when(managementService.list(any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(v)));

        mockMvc.perform(get("/api/devices").param("hasActivePlaylist", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].activePlaylistId").value(15))
                .andExpect(jsonPath("$.content[0].activePlaylistName").value("Summer Promo"));
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "VIEWER")
    void list_hasActivePlaylist_combinedWithUnassignedOrGroup_returns200_noGuard() throws Exception {
        when(managementService.list(any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        // hasActivePlaylist is orthogonal to unassigned AND to deviceGroupId — no 400.
        mockMvc.perform(get("/api/devices")
                        .param("hasActivePlaylist", "true").param("unassigned", "true"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/devices")
                        .param("hasActivePlaylist", "false").param("deviceGroupId", "3"))
                .andExpect(status().isOk());
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "VIEWER")
    void list_filtersByProjectId() throws Exception {
        when(managementService.list(any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        mockMvc.perform(get("/api/devices").param("projectId", "77"))
                .andExpect(status().isOk());

        // projectId is threaded into list() in position 3 — right AFTER regionId (which is
        // null here) and BEFORE facilityId. (status, regionId, projectId, facilityId, ...)
        verify(managementService).list(
                eq(null),   // status
                eq(null),   // regionId
                eq(77L),    // projectId
                eq(null),   // facilityId
                eq(null),   // deviceGroupId
                eq(null),   // unassigned
                eq(null),   // serial
                eq(null),   // name
                eq(null),   // facilityName
                eq(null),   // hasActivePlaylist
                eq(null),   // syncUnassigned
                any());     // pageable
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "OPERATOR")
    void setLocation_validRegionAndFacility_returns200_withUpdatedDetail() throws Exception {
        var device = mockDeviceForLocation(80L, "SN-80", "TV-Lobby",
                Device.Status.ONLINE, 5L, 9L, null);
        when(managementService.setLocation(80L, 5L, 9L)).thenReturn(device);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/devices/80/location")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"regionId\":5,\"facilityId\":9}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(80))
                .andExpect(jsonPath("$.regionId").value(5))
                .andExpect(jsonPath("$.facilityId").value(9));
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "OPERATOR")
    void setLocation_nullFacility_returns200_clearsFacility() throws Exception {
        var device = mockDeviceForLocation(81L, "SN-81", "TV-Region-Only",
                Device.Status.ONLINE, 5L, null, null);
        when(managementService.setLocation(81L, 5L, null)).thenReturn(device);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/devices/81/location")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"regionId\":5,\"facilityId\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regionId").value(5))
                .andExpect(jsonPath("$.facilityId").doesNotExist());
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "OPERATOR")
    void setLocation_facilityNotInRegion_returns400() throws Exception {
        when(managementService.setLocation(82L, 5L, 99L))
                .thenThrow(new IllegalArgumentException("Facility 99 is not in region 5"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/devices/82/location")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"regionId\":5,\"facilityId\":99}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("not in region")));
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "OPERATOR")
    void setLocation_crossProjectDeviceGroup_returns409() throws Exception {
        when(managementService.setLocation(83L, 7L, null))
                .thenThrow(new IllegalStateException(
                        "Device 83 is in device group 12 from a different project; "
                        + "remove from the group before relocating"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/devices/83/location")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"regionId\":7}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("different project")));
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "OPERATOR")
    void setLocation_sameProjectDifferentRegionGroupedDevice_returns200() throws Exception {
        // Relaxed model: a grouped device may move to a different region within the SAME
        // project — the service no longer throws, it returns the relocated device.
        var device = mockDeviceForLocation(89L, "SN-89", "TV-Span",
                Device.Status.ONLINE, 7L, null, 12L);
        when(managementService.setLocation(89L, 7L, null)).thenReturn(device);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/devices/89/location")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"regionId\":7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(89))
                .andExpect(jsonPath("$.regionId").value(7));
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "OPERATOR")
    void setLocation_missingRegion_returns404() throws Exception {
        when(managementService.setLocation(84L, 999L, null))
                .thenThrow(new ResourceNotFoundException("Region", 999L));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/devices/84/location")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"regionId\":999}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "OPERATOR")
    void setLocation_missingRegionId_returns400() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/devices/85/location")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "VIEWER")
    void setLocation_viewer_forbidden() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/devices/86/location")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"regionId\":5}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "ADVERTISER")
    void setLocation_advertiser_forbidden() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/devices/87/location")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"regionId\":5}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void setLocation_unauthenticated_returns401() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/devices/88/location")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"regionId\":5}"))
                .andExpect(status().isUnauthorized());
    }

    private uz.orientadvertise.services.domain.model.Device mockDeviceForLocation(
            Long id, String serial, String name, Device.Status status,
            Long regionId, Long facilityId, Long deviceGroupId) {
        var d = org.mockito.Mockito.mock(uz.orientadvertise.services.domain.model.Device.class);
        when(d.getId()).thenReturn(id);
        when(d.getSerialNumber()).thenReturn(serial);
        when(d.getName()).thenReturn(name);
        when(d.getStatus()).thenReturn(status);
        when(d.getCreatedAt()).thenReturn(Instant.parse("2026-05-01T00:00:00Z"));
        when(d.getUpdatedAt()).thenReturn(Instant.parse("2026-05-10T00:00:00Z"));
        if (regionId != null) {
            var r = org.mockito.Mockito.mock(uz.orientadvertise.services.domain.model.Region.class);
            when(r.getId()).thenReturn(regionId);
            when(d.getRegion()).thenReturn(r);
        }
        if (facilityId != null) {
            var f = org.mockito.Mockito.mock(uz.orientadvertise.services.domain.model.Facility.class);
            when(f.getId()).thenReturn(facilityId);
            when(d.getFacility()).thenReturn(f);
        }
        if (deviceGroupId != null) {
            var g = org.mockito.Mockito.mock(uz.orientadvertise.services.domain.model.DeviceGroup.class);
            when(g.getId()).thenReturn(deviceGroupId);
            when(d.getDeviceGroup()).thenReturn(g);
        }
        return d;
    }

    @Test
    void syncConfirm_noToken_returns401() throws Exception {
        mockMvc.perform(post("/api/devices/4/sync/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reportedVersion\":\"v-4\"}"))
                .andExpect(status().isUnauthorized());
    }
}
