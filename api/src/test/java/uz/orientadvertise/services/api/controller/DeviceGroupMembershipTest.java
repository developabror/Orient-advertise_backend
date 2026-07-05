package uz.orientadvertise.services.api.controller;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uz.orientadvertise.services.api.security.JwtAuthenticationFilter;
import uz.orientadvertise.services.api.security.SecurityConfig;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.audit.AuditRecorder;
import uz.orientadvertise.services.domain.auth.TokenValidator;
import uz.orientadvertise.services.domain.auth.UserActiveChecker;
import uz.orientadvertise.services.service.BulkRemoteActionService;
import uz.orientadvertise.services.service.DeviceGroupManagementService;
import uz.orientadvertise.services.service.DeviceGroupManagementService.AddDevicesResult;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DeviceGroupController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class DeviceGroupMembershipTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DeviceGroupManagementService managementService;

    @MockitoBean
    private uz.orientadvertise.services.service.DeviceManagementService deviceManagementService;

    @MockitoBean
    private BulkRemoteActionService bulkService;

    @MockitoBean
    private TokenValidator tokenValidator;

    @MockitoBean
    private UserActiveChecker userActiveChecker;

    @MockitoBean
    private AuditRecorder auditRecorder;

    // ----- POST /devices -----

    @Test
    @WithMockUser(roles = "OPERATOR")
    void addDevices_returnsAddedCountAlreadyMemberAndMovedFrom() throws Exception {
        // 3 added (one of which was moved from group 7), 1 already member, 1 moved from
        // group 8. The response surfaces all three buckets so the FE can render an
        // accurate summary without a follow-up fetch.
        var result = new AddDevicesResult(
                3,
                List.of(101L),
                Map.of(102L, 7L, 103L, 8L));
        when(managementService.addDevices(eq(5L), eq(List.of(100L, 101L, 102L, 103L))))
                .thenReturn(result);

        mockMvc.perform(post("/api/device-groups/5/devices")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "deviceIds": [100, 101, 102, 103] }"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.addedCount").value(3))
                .andExpect(jsonPath("$.alreadyMember.length()").value(1))
                .andExpect(jsonPath("$.alreadyMember[0]").value(101))
                .andExpect(jsonPath("$.movedFrom.102").value(7))
                .andExpect(jsonPath("$.movedFrom.103").value(8));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void addDevices_missingDevice_returns404WithIdsInMessage() throws Exception {
        when(managementService.addDevices(eq(5L), any()))
                .thenThrow(new ResourceNotFoundException("Devices", List.of(101L, 102L)));

        mockMvc.perform(post("/api/device-groups/5/devices")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "deviceIds": [100, 101, 102] }"""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("[101, 102]")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void addDevices_crossProject_returns400WithOffendingIds() throws Exception {
        // Cross-project membership is rejected — the service surfaces the offending ids
        // in the message and the controller maps IllegalArgumentException → 400.
        when(managementService.addDevices(eq(5L), any()))
                .thenThrow(new IllegalArgumentException(
                        "Devices belong to a different project (move cross-project first): [200]"));

        mockMvc.perform(post("/api/device-groups/5/devices")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "deviceIds": [200] }"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("[200]")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void addDevices_unknownGroup_returns404() throws Exception {
        when(managementService.addDevices(eq(999L), any()))
                .thenThrow(new ResourceNotFoundException("DeviceGroup", 999L));

        mockMvc.perform(post("/api/device-groups/999/devices")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "deviceIds": [100] }"""))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void addDevices_emptyList_returns400ViaBeanValidation() throws Exception {
        // @NotEmpty on the request record stops the request before it reaches the service.
        mockMvc.perform(post("/api/device-groups/5/devices")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "deviceIds": [] }"""))
                .andExpect(status().isBadRequest());
        verify(managementService, never()).addDevices(anyLong(), any());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void addDevices_viewer_returns403() throws Exception {
        mockMvc.perform(post("/api/device-groups/5/devices")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "deviceIds": [100] }"""))
                .andExpect(status().isForbidden());
        verify(managementService, never()).addDevices(anyLong(), any());
    }

    @Test
    void addDevices_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/device-groups/5/devices")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "deviceIds": [100] }"""))
                .andExpect(status().isUnauthorized());
    }

    // ----- DELETE /devices/{deviceId} -----

    @Test
    @WithMockUser(roles = "OPERATOR")
    void removeDevice_returns204() throws Exception {
        mockMvc.perform(delete("/api/device-groups/5/devices/100").with(csrf()))
                .andExpect(status().isNoContent());
        verify(managementService).removeDevice(5L, 100L);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void removeDevice_notInThisGroup_returns404() throws Exception {
        // The service collapses "device exists but is in another group" into the same
        // 404 it returns for a missing device — by design, so the API can't leak the
        // device's actual group to a caller who guessed wrong.
        doThrow(new ResourceNotFoundException("Device", 100L))
                .when(managementService).removeDevice(5L, 100L);

        mockMvc.perform(delete("/api/device-groups/5/devices/100").with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void removeDevice_unknownGroup_returns404() throws Exception {
        doThrow(new ResourceNotFoundException("DeviceGroup", 999L))
                .when(managementService).removeDevice(999L, 100L);

        mockMvc.perform(delete("/api/device-groups/999/devices/100").with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void removeDevice_viewer_returns403() throws Exception {
        mockMvc.perform(delete("/api/device-groups/5/devices/100").with(csrf()))
                .andExpect(status().isForbidden());
        verify(managementService, never()).removeDevice(anyLong(), anyLong());
    }
}
