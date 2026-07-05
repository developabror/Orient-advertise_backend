package uz.orientadvertise.services.api.controller;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uz.orientadvertise.services.api.controller.DeviceGroupController;
import uz.orientadvertise.services.api.security.JwtAuthenticationFilter;
import uz.orientadvertise.services.api.security.SecurityConfig;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.audit.AuditRecorder;
import uz.orientadvertise.services.domain.auth.TokenValidator;
import uz.orientadvertise.services.domain.auth.UserActiveChecker;
import uz.orientadvertise.services.service.BulkRemoteActionService;
import uz.orientadvertise.services.service.BulkRemoteActionService.BulkActionResult;
import uz.orientadvertise.services.service.BulkRemoteActionService.DeviceFailure;
import uz.orientadvertise.services.service.DeviceGroupManagementService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DeviceGroupController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
@WithMockUser(roles = "ADMIN")
class DeviceGroupControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BulkRemoteActionService bulkService;

    @MockitoBean
    private DeviceGroupManagementService managementService;

    @MockitoBean
    private uz.orientadvertise.services.service.DeviceManagementService deviceManagementService;

    @MockitoBean
    private TokenValidator tokenValidator;

    @MockitoBean
    private UserActiveChecker userActiveChecker;

    @MockitoBean
    private AuditRecorder auditRecorder;

    @Test
    void issueAction_allSucceed_returns200WithSummary() throws Exception {
        when(bulkService.issueToGroup(eq(1L), eq("REBOOT"), any(), anyString()))
                .thenReturn(new BulkActionResult(1L, "REBOOT", 3,
                        List.of(101L, 102L, 103L), List.of(), List.of()));

        mockMvc.perform(post("/api/device-groups/1/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actionType\":\"REBOOT\",\"payload\":\"{}\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalDevices").value(3))
                .andExpect(jsonPath("$.succeededCount").value(3))
                .andExpect(jsonPath("$.skippedCount").value(0))
                .andExpect(jsonPath("$.failedCount").value(0))
                .andExpect(jsonPath("$.succeededActionIds.length()").value(3));
    }

    @Test
    void issueAction_partialFailure_returns200WithSummary() throws Exception {
        when(bulkService.issueToGroup(eq(1L), eq("SYNC_CONTENT"), any(), anyString()))
                .thenReturn(new BulkActionResult(1L, "SYNC_CONTENT", 5,
                        List.of(101L, 102L, 103L),
                        List.of(new DeviceFailure(20L, "Device 20 already has a PENDING SYNC_CONTENT")),
                        List.of(new DeviceFailure(40L, "DB write failed"))));

        mockMvc.perform(post("/api/device-groups/1/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actionType\":\"SYNC_CONTENT\"}"))
                .andExpect(status().isOk()) // NOT 5xx — partial failure returns summary
                .andExpect(jsonPath("$.succeededCount").value(3))
                .andExpect(jsonPath("$.skippedCount").value(1))
                .andExpect(jsonPath("$.failedCount").value(1))
                .andExpect(jsonPath("$.skipped[0].deviceId").value(20))
                .andExpect(jsonPath("$.failed[0].deviceId").value(40));
    }

    @Test
    void issueAction_unknownGroup_returns404() throws Exception {
        when(bulkService.issueToGroup(anyLong(), anyString(), any(), anyString()))
                .thenThrow(new ResourceNotFoundException("DeviceGroup", 99L));

        mockMvc.perform(post("/api/device-groups/99/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actionType\":\"REBOOT\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void issueAction_missingActionType_returns400() throws Exception {
        mockMvc.perform(post("/api/device-groups/1/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    @Test
    void issueAction_invalidActionType_returns400() throws Exception {
        // IllegalArgumentException from service is mapped to 400 by GlobalExceptionHandler.
        when(bulkService.issueToGroup(anyLong(), eq("BAD"), any(), anyString()))
                .thenThrow(new IllegalArgumentException("Unsupported action type: BAD"));

        mockMvc.perform(post("/api/device-groups/1/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actionType\":\"BAD\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void issueAction_viewerRole_forbidden() throws Exception {
        mockMvc.perform(post("/api/device-groups/1/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actionType\":\"REBOOT\"}"))
                .andExpect(status().isForbidden());
    }
}
