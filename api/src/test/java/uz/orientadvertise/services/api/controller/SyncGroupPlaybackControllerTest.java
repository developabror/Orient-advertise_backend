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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import uz.orientadvertise.services.api.security.JwtAuthenticationFilter;
import uz.orientadvertise.services.api.security.SecurityConfig;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.audit.AuditRecorder;
import uz.orientadvertise.services.domain.auth.TokenValidator;
import uz.orientadvertise.services.domain.auth.UserActiveChecker;
import uz.orientadvertise.services.service.SyncGroupManagementService;
import uz.orientadvertise.services.service.SyncGroupPlaybackService;
import uz.orientadvertise.services.service.SyncGroupPlaybackService.PlaybackItemView;
import uz.orientadvertise.services.service.SyncGroupPlaybackService.PlaybackView;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SyncGroupController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class SyncGroupPlaybackControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SyncGroupPlaybackService playbackService;

    @MockitoBean
    private SyncGroupManagementService managementService;

    @MockitoBean
    private uz.orientadvertise.services.service.DeviceManagementService deviceManagementService;

    @MockitoBean
    private TokenValidator tokenValidator;

    @MockitoBean
    private UserActiveChecker userActiveChecker;

    @MockitoBean
    private AuditRecorder auditRecorder;

    /** Device-agent auth: principal = device id (Long), authority ROLE_DEVICE. */
    private static RequestPostProcessor device(long id) {
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                .authentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        id, null, List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_DEVICE"))));
    }

    // ----- POST /playback/jump -----

    @Test
    @WithMockUser(roles = "OPERATOR")
    void jump_happyPath_returns200WithAnchorAndDispatch() throws Exception {
        when(playbackService.jumpToIndex(eq(5L), eq(6), any()))
                .thenReturn(new SyncGroupPlaybackService.JumpResultView(
                        5L, 6, 1_700_000_000_000L, 1_700_000_060_000L, 4, 3, 1, 0));

        mockMvc.perform(post("/api/sync-groups/5/playback/jump")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"index\": 6 }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.syncGroupId").value(5))
                .andExpect(jsonPath("$.index").value(6))
                .andExpect(jsonPath("$.anchorEpochMs").value(1_700_000_000_000L))
                .andExpect(jsonPath("$.activateAtEpochMs").value(1_700_000_060_000L))
                .andExpect(jsonPath("$.activateAtIso").exists())
                .andExpect(jsonPath("$.memberCount").value(4))
                .andExpect(jsonPath("$.dispatched.sent").value(3))
                .andExpect(jsonPath("$.dispatched.skipped").value(1))
                .andExpect(jsonPath("$.dispatched.failed").value(0));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void jump_indexOutOfRange_returns400() throws Exception {
        when(playbackService.jumpToIndex(eq(5L), eq(9), any()))
                .thenThrow(new IllegalArgumentException("jump index 9 is out of range [0, 3)"));

        mockMvc.perform(post("/api/sync-groups/5/playback/jump")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"index\": 9 }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("jump index 9 is out of range [0, 3)"));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void jump_emptyOrIncoherentGroup_returns409Verbatim() throws Exception {
        when(playbackService.jumpToIndex(eq(5L), eq(0), any()))
                .thenThrow(new IllegalStateException(
                        "sync group is not content-coherent; members must share one playlist/version to jump as a unit"));

        mockMvc.perform(post("/api/sync-groups/5/playback/jump")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"index\": 0 }"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "sync group is not content-coherent; members must share one playlist/version to jump as a unit"));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void jump_unknownOrOutOfScope_returns404() throws Exception {
        when(playbackService.jumpToIndex(eq(99L), eq(0), any()))
                .thenThrow(new ResourceNotFoundException("SyncGroup", 99L));

        mockMvc.perform(post("/api/sync-groups/99/playback/jump")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"index\": 0 }"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void jump_missingIndex_returns400_validation() throws Exception {
        mockMvc.perform(post("/api/sync-groups/5/playback/jump")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ }"))
                .andExpect(status().isBadRequest());
        verify(playbackService, never()).jumpToIndex(any(), anyInt(), any());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void jump_viewer_returns403() throws Exception {
        mockMvc.perform(post("/api/sync-groups/5/playback/jump")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"index\": 1 }"))
                .andExpect(status().isForbidden());
        verify(playbackService, never()).jumpToIndex(any(), anyInt(), any());
    }

    @Test
    void jump_deviceToken_returns403() throws Exception {
        mockMvc.perform(post("/api/sync-groups/5/playback/jump")
                        .with(csrf())
                        .with(device(1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"index\": 1 }"))
                .andExpect(status().isForbidden());
        verify(playbackService, never()).jumpToIndex(any(), anyInt(), any());
    }

    @Test
    void jump_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/sync-groups/5/playback/jump")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"index\": 1 }"))
                .andExpect(status().isUnauthorized());
    }

    // ----- GET /playback -----

    @Test
    @WithMockUser(roles = "VIEWER")
    void playback_coherent_returns200WithItems() throws Exception {
        var items = List.of(
                new PlaybackItemView(0, 501L, "clip-a", 10, 0L, 10_000L),
                new PlaybackItemView(1, 502L, "clip-b", 20, 10_000L, 20_000L));
        when(playbackService.getPlaybackView(5L))
                .thenReturn(new PlaybackView(5L, true, null, 100L, "Loop", 30_000L, 2, items, null));

        mockMvc.perform(get("/api/sync-groups/5/playback"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coherent").value(true))
                .andExpect(jsonPath("$.playlistName").value("Loop"))
                .andExpect(jsonPath("$.loopDurationMs").value(30_000L))
                .andExpect(jsonPath("$.memberCount").value(2))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[1].slotStartMs").value(10_000L))
                .andExpect(jsonPath("$.activeJump").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void playback_incoherent_returns200WithReasonAndEmptyItems() throws Exception {
        when(playbackService.getPlaybackView(5L))
                .thenReturn(new PlaybackView(5L, false, "members resolve different content",
                        null, null, 0L, 2, List.of(), null));

        mockMvc.perform(get("/api/sync-groups/5/playback"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coherent").value(false))
                .andExpect(jsonPath("$.reason").value("members resolve different content"))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void playback_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/sync-groups/5/playback"))
                .andExpect(status().isUnauthorized());
    }
}
