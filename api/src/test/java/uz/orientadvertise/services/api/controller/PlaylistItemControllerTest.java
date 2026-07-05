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
import uz.orientadvertise.services.api.security.JwtAuthenticationFilter;
import uz.orientadvertise.services.api.security.SecurityConfig;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.audit.AuditRecorder;
import uz.orientadvertise.services.domain.auth.TokenValidator;
import uz.orientadvertise.services.domain.auth.UserActiveChecker;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.domain.model.PlaylistItem;
import uz.orientadvertise.services.service.PlaylistItemService;
import uz.orientadvertise.services.service.PlaylistManagementService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PlaylistController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class PlaylistItemControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PlaylistItemService itemService;

    @MockitoBean
    private PlaylistManagementService playlistService;

    @MockitoBean
    private TokenValidator tokenValidator;

    @MockitoBean
    private UserActiveChecker userActiveChecker;

    @MockitoBean
    private AuditRecorder auditRecorder;

    // ----- POST /items -----

    @Test
    @WithMockUser(roles = "OPERATOR")
    void addItem_returns201WithItem() throws Exception {
        var item = stubItem(50L, 1, 100L, "ad.mp4", 30, null);
        when(itemService.addItem(eq(5L), eq(100L), eq(1), isNull())).thenReturn(item);

        mockMvc.perform(post("/api/playlists/5/items")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "contentFileId": 100, "position": 1 }"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(50))
                .andExpect(jsonPath("$.position").value(1))
                .andExpect(jsonPath("$.contentFileId").value(100))
                .andExpect(jsonPath("$.contentFileName").value("ad.mp4"))
                .andExpect(jsonPath("$.durationSeconds").value(30))
                .andExpect(jsonPath("$.durationOverride").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void addItem_appendWhenPositionOmitted() throws Exception {
        var item = stubItem(50L, 2, 100L, "ad.mp4", 30, null);
        when(itemService.addItem(eq(5L), eq(100L), isNull(), isNull())).thenReturn(item);

        mockMvc.perform(post("/api/playlists/5/items")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "contentFileId": 100 }"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.position").value(2));

        verify(itemService).addItem(5L, 100L, null, null);
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void addItem_nonReady_returns400() throws Exception {
        when(itemService.addItem(any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException(
                        "Content file 100 is not READY (status: TRANSCODING)"));

        mockMvc.perform(post("/api/playlists/5/items")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "contentFileId": 100 }"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("not READY")));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void addItem_softDeletedContent_returns404() throws Exception {
        when(itemService.addItem(any(), any(), any(), any()))
                .thenThrow(new ResourceNotFoundException("ContentFile", 100L));

        mockMvc.perform(post("/api/playlists/5/items")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "contentFileId": 100 }"""))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void addItem_outOfRangePosition_returns400() throws Exception {
        when(itemService.addItem(any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Position must be in [0, 2], got: 5"));

        mockMvc.perform(post("/api/playlists/5/items")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "contentFileId": 100, "position": 5 }"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("Position must be in")));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void addItem_missingContentFileId_returns400() throws Exception {
        mockMvc.perform(post("/api/playlists/5/items")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "position": 0 }"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void addItem_viewer_returns403() throws Exception {
        mockMvc.perform(post("/api/playlists/5/items")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "contentFileId": 100 }"""))
                .andExpect(status().isForbidden());
        verify(itemService, never()).addItem(any(), any(), any(), any());
    }

    // ----- DELETE /items/{itemId} -----

    @Test
    @WithMockUser(roles = "OPERATOR")
    void removeItem_returns204() throws Exception {
        mockMvc.perform(delete("/api/playlists/5/items/50").with(csrf()))
                .andExpect(status().isNoContent());
        verify(itemService).removeItem(5L, 50L);
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void removeItem_unknown_returns404() throws Exception {
        doThrow(new ResourceNotFoundException("PlaylistItem", 999L))
                .when(itemService).removeItem(5L, 999L);

        mockMvc.perform(delete("/api/playlists/5/items/999").with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void removeItem_viewer_returns403() throws Exception {
        mockMvc.perform(delete("/api/playlists/5/items/50").with(csrf()))
                .andExpect(status().isForbidden());
        verify(itemService, never()).removeItem(any(), any());
    }

    // ----- PUT /items/{itemId}/move -----

    @Test
    @WithMockUser(roles = "OPERATOR")
    void moveItem_returns200WithMoved() throws Exception {
        var item = stubItem(50L, 3, 100L, "ad.mp4", 30, null);
        when(itemService.moveItem(5L, 50L, 3)).thenReturn(item);

        mockMvc.perform(put("/api/playlists/5/items/50/move")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "toPosition": 3 }"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(50))
                .andExpect(jsonPath("$.position").value(3));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void moveItem_negativePosition_returns400ViaBeanValidation() throws Exception {
        // @Min(0) on the request record fails before the service is reached.
        mockMvc.perform(put("/api/playlists/5/items/50/move")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "toPosition": -1 }"""))
                .andExpect(status().isBadRequest());
        verify(itemService, never()).moveItem(any(), any(), any(int.class));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void moveItem_outOfRange_returns400FromService() throws Exception {
        when(itemService.moveItem(any(), any(), any(int.class)))
                .thenThrow(new IllegalArgumentException("toPosition must be in [0, 2], got: 5"));

        mockMvc.perform(put("/api/playlists/5/items/50/move")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "toPosition": 5 }"""))
                .andExpect(status().isBadRequest());
    }

    // ----- PUT /items/reorder -----

    @Test
    @WithMockUser(roles = "OPERATOR")
    void reorderItems_returns200WithNewOrder() throws Exception {
        var first = stubItem(20L, 0, 200L, "second.mp4", 60, null);
        var second = stubItem(10L, 1, 100L, "first.mp4", 30, null);
        when(itemService.reorderAll(eq(5L), eq(List.of(20L, 10L))))
                .thenReturn(List.of(first, second));

        mockMvc.perform(put("/api/playlists/5/items/reorder")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "orderedItemIds": [20, 10] }"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(20))
                .andExpect(jsonPath("$[0].position").value(0))
                .andExpect(jsonPath("$[1].id").value(10))
                .andExpect(jsonPath("$[1].position").value(1));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void reorderItems_idMismatch_returns400() throws Exception {
        when(itemService.reorderAll(any(), any()))
                .thenThrow(new IllegalArgumentException(
                        "orderedItemIds must exactly match the current item set; expected [10, 20], got [10, 30]"));

        mockMvc.perform(put("/api/playlists/5/items/reorder")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "orderedItemIds": [10, 30] }"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("must exactly match")));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void reorderItems_emptyList_returns400() throws Exception {
        // @NotEmpty on the request record fails before the service is reached.
        mockMvc.perform(put("/api/playlists/5/items/reorder")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "orderedItemIds": [] }"""))
                .andExpect(status().isBadRequest());
        verify(itemService, never()).reorderAll(any(), any());
    }

    // ----- PUT /items/{itemId}/duration -----

    @Test
    @WithMockUser(roles = "OPERATOR")
    void setItemDuration_setsOverrideAndReturns200() throws Exception {
        var item = stubItem(50L, 0, 100L, "ad.mp4", 30, 45);
        when(itemService.setDuration(5L, 50L, 45)).thenReturn(item);

        mockMvc.perform(put("/api/playlists/5/items/50/duration")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "durationSeconds": 45 }"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(50))
                .andExpect(jsonPath("$.durationSeconds").value(30))
                .andExpect(jsonPath("$.durationOverride").value(45));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void setItemDuration_nullClearsOverride() throws Exception {
        // After clearing, the response shows the override field absent (null fields are
        // dropped by Jackson with our defaults), proving the device-side /playlist will
        // fall back to the source file's duration.
        var item = stubItem(50L, 0, 100L, "ad.mp4", 30, null);
        when(itemService.setDuration(5L, 50L, null)).thenReturn(item);

        mockMvc.perform(put("/api/playlists/5/items/50/duration")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "durationSeconds": null }"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.durationOverride").doesNotExist())
                .andExpect(jsonPath("$.durationSeconds").value(30));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void setItemDuration_zero_returns400ViaBeanValidation() throws Exception {
        // @Min(1) on the request record fails before the service is reached.
        mockMvc.perform(put("/api/playlists/5/items/50/duration")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "durationSeconds": 0 }"""))
                .andExpect(status().isBadRequest());
        verify(itemService, never()).setDuration(any(), any(), any());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void setItemDuration_overOneDay_returns400ViaBeanValidation() throws Exception {
        // @Max(86400) — 86401 is one second over the cap.
        mockMvc.perform(put("/api/playlists/5/items/50/duration")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "durationSeconds": 86401 }"""))
                .andExpect(status().isBadRequest());
        verify(itemService, never()).setDuration(any(), any(), any());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void setItemDuration_unknownItem_returns404() throws Exception {
        when(itemService.setDuration(5L, 999L, 30))
                .thenThrow(new ResourceNotFoundException("PlaylistItem", 999L));

        mockMvc.perform(put("/api/playlists/5/items/999/duration")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "durationSeconds": 30 }"""))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void setItemDuration_softDeletedPlaylist_returns404() throws Exception {
        when(itemService.setDuration(5L, 50L, 30))
                .thenThrow(new ResourceNotFoundException("Playlist", 5L));

        mockMvc.perform(put("/api/playlists/5/items/50/duration")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "durationSeconds": 30 }"""))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void setItemDuration_viewer_returns403() throws Exception {
        mockMvc.perform(put("/api/playlists/5/items/50/duration")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "durationSeconds": 30 }"""))
                .andExpect(status().isForbidden());
        verify(itemService, never()).setDuration(any(), any(), any());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void reorderItems_viewer_returns403() throws Exception {
        mockMvc.perform(put("/api/playlists/5/items/reorder")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "orderedItemIds": [10, 20] }"""))
                .andExpect(status().isForbidden());
        verify(itemService, never()).reorderAll(any(), any());
    }

    private static PlaylistItem stubItem(long id, int position, long contentFileId,
                                           String contentFileName, Integer naturalDuration,
                                           Integer override) {
        var cf = mock(ContentFile.class);
        when(cf.getId()).thenReturn(contentFileId);
        when(cf.getName()).thenReturn(contentFileName);
        when(cf.getDurationSeconds()).thenReturn(naturalDuration);
        var item = mock(PlaylistItem.class);
        when(item.getId()).thenReturn(id);
        when(item.getPosition()).thenReturn(position);
        when(item.getContentFile()).thenReturn(cf);
        when(item.getDurationSeconds()).thenReturn(override);
        return item;
    }
}
