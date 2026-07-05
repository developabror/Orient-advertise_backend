package uz.orientadvertise.services.api.controller;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uz.orientadvertise.services.api.controller.PlaybackController;
import uz.orientadvertise.services.api.security.JwtAuthenticationFilter;
import uz.orientadvertise.services.api.security.SecurityConfig;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.audit.AuditRecorder;
import uz.orientadvertise.services.domain.auth.TokenValidator;
import uz.orientadvertise.services.domain.auth.UserActiveChecker;
import uz.orientadvertise.services.service.PlaybackLogService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PlaybackController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class PlaybackControllerTest {

    @Autowired
    private MockMvc mockMvc;

    /** Device-agent auth: principal = device id (Long), authority ROLE_DEVICE. */
    private static org.springframework.test.web.servlet.request.RequestPostProcessor device(long id) {
        return authentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                id, null, List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_DEVICE"))));
    }

    @MockitoBean
    private PlaybackLogService playbackLogService;

    @MockitoBean
    private TokenValidator tokenValidator;

    @MockitoBean
    private UserActiveChecker userActiveChecker;

    @MockitoBean
    private AuditRecorder auditRecorder;

    @Test
    void singleEvent_acceptedAsObject() throws Exception {
        when(playbackLogService.recordBatch(eq(1L), argThat(list -> list != null && list.size() == 1)))
                .thenReturn(new PlaybackLogService.BatchRecordResult(1, 0, 0, List.of()));

        mockMvc.perform(post("/api/devices/1/playback").with(device(1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentFileId\":10,\"playedAt\":\"2026-05-06T01:00:00Z\",\"durationSeconds\":30}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.created").value(1));
    }

    @Test
    void batchArray_accepted() throws Exception {
        when(playbackLogService.recordBatch(eq(2L), argThat(list -> list != null && list.size() == 3)))
                .thenReturn(new PlaybackLogService.BatchRecordResult(2, 1, 0, List.of()));

        String body = "[" +
                "{\"contentFileId\":10,\"playedAt\":\"2026-05-06T01:00:00Z\"}," +
                "{\"contentFileId\":10,\"playedAt\":\"2026-05-06T01:01:00Z\"}," +
                "{\"contentFileId\":10,\"playedAt\":\"2026-05-06T01:02:00Z\"}]";

        mockMvc.perform(post("/api/devices/2/playback").with(device(2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.created").value(2))
                .andExpect(jsonPath("$.duplicate").value(1));
    }

    @Test
    void emptyArray_returns200WithZeros() throws Exception {
        when(playbackLogService.recordBatch(eq(3L), any()))
                .thenReturn(new PlaybackLogService.BatchRecordResult(0, 0, 0, List.of()));

        mockMvc.perform(post("/api/devices/3/playback").with(device(3))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void rejectionsIncluded_inResponse() throws Exception {
        when(playbackLogService.recordBatch(eq(4L), any()))
                .thenReturn(new PlaybackLogService.BatchRecordResult(1, 0, 1,
                        List.of(new PlaybackLogService.EntryRejection(1, "played_at is in the future..."))));

        String body = "[" +
                "{\"contentFileId\":10,\"playedAt\":\"2026-05-06T00:00:00Z\"}," +
                "{\"contentFileId\":10,\"playedAt\":\"2099-01-01T00:00:00Z\"}]";

        mockMvc.perform(post("/api/devices/4/playback").with(device(4))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected").value(1))
                .andExpect(jsonPath("$.rejections[0].index").value(1))
                .andExpect(jsonPath("$.rejections[0].reason").value(
                        org.hamcrest.Matchers.containsString("future")));
    }

    @Test
    void batchOver500_returns400FromService() throws Exception {
        when(playbackLogService.recordBatch(eq(5L), any()))
                .thenThrow(new IllegalArgumentException("Batch size 501 exceeds maximum 500"));

        // Service throws on >500 — controller forwards via the global IAE→400 handler.
        // Single-event request triggers the throw via the mock; in production it'd be
        // 501+ entries.
        mockMvc.perform(post("/api/devices/5/playback").with(device(5))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentFileId\":10,\"playedAt\":\"2026-05-06T00:00:00Z\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("500")));
    }

    @Test
    void unknownDevice_returns404() throws Exception {
        when(playbackLogService.recordBatch(eq(999L), any()))
                .thenThrow(new ResourceNotFoundException("Device", 999L));

        mockMvc.perform(post("/api/devices/999/playback").with(device(999))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentFileId\":10,\"playedAt\":\"2026-05-06T00:00:00Z\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void scalarBody_returns400() throws Exception {
        mockMvc.perform(post("/api/devices/6/playback").with(device(6))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("\"not-an-object\""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void noToken_returns401() throws Exception {
        mockMvc.perform(post("/api/devices/7/playback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[]"))
                .andExpect(status().isUnauthorized());
    }
}
