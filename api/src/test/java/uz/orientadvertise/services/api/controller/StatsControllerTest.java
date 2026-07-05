package uz.orientadvertise.services.api.controller;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.test.context.support.WithMockUser;
import uz.orientadvertise.services.api.controller.StatsController;
import uz.orientadvertise.services.common.exception.AccessForbiddenException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uz.orientadvertise.services.api.security.JwtAuthenticationFilter;
import uz.orientadvertise.services.api.security.SecurityConfig;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.audit.AuditRecorder;
import uz.orientadvertise.services.domain.auth.TokenValidator;
import uz.orientadvertise.services.domain.auth.UserActiveChecker;
import uz.orientadvertise.services.service.ContentStatsService;
import uz.orientadvertise.services.service.OperatorScopeResolver;
import uz.orientadvertise.services.service.OperatorScopeResolver.ScopedProjects;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StatsController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class StatsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ContentStatsService contentStatsService;

    @MockitoBean
    private uz.orientadvertise.services.service.DevicePlaybackReportService devicePlaybackReportService;

    @MockitoBean
    private OperatorScopeResolver operatorScopeResolver;

    @MockitoBean
    private TokenValidator tokenValidator;

    @BeforeEach
    void stubUnrestrictedScope() {
        // Operator-only callers trigger operatorScopeResolver.resolve(); default to an
        // unrestricted scope so the controller passes a benign projectIds collection.
        lenient().when(operatorScopeResolver.resolve())
                .thenReturn(new ScopedProjects(null, null, null, false));
    }

    @MockitoBean
    private UserActiveChecker userActiveChecker;

    @MockitoBean
    private AuditRecorder auditRecorder;

    @Test
    @WithMockUser(roles = "OPERATOR")
    void getContentStats_returnsCountsAndTimestamps() throws Exception {
        var stats = new ContentStatsService.ContentStats(
                10L, "ad.mp4",
                Instant.parse("2026-04-29T00:00:00Z"),
                Instant.parse("2026-05-06T00:00:00Z"),
                42L,
                List.of(new ContentStatsService.DeviceCount(1L, "TV-1", 30L),
                        new ContentStatsService.DeviceCount(2L, "TV-2", 12L)),
                true,
                new PageImpl<>(List.of(
                        Instant.parse("2026-05-06T00:30:00Z"),
                        Instant.parse("2026-05-05T15:00:00Z"))));
        when(contentStatsService.getStats(eq(10L), any(), any(), any(), any(), any(), eq(false), anyBoolean(), any()))
                .thenReturn(stats);

        mockMvc.perform(get("/api/stats/content/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contentFileId").value(10))
                .andExpect(jsonPath("$.totalPlayCount").value(42))
                .andExpect(jsonPath("$.perDevice.length()").value(2))
                .andExpect(jsonPath("$.perDevice[0].deviceId").value(1))
                .andExpect(jsonPath("$.perDevice[0].playCount").value(30))
                .andExpect(jsonPath("$.timestampsIncluded").value(true))
                .andExpect(jsonPath("$.timestamps.content.length()").value(2));
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void getContentStats_rangeOver30Days_omitsTimestamps() throws Exception {
        var stats = new ContentStatsService.ContentStats(
                11L, "promo.mp4",
                Instant.parse("2026-02-06T00:00:00Z"),
                Instant.parse("2026-05-06T00:00:00Z"),
                500L,
                List.of(),
                false,
                new PageImpl<>(List.of()));
        when(contentStatsService.getStats(eq(11L), any(), any(), any(), any(), any(), eq(false), anyBoolean(), any()))
                .thenReturn(stats);

        mockMvc.perform(get("/api/stats/content/11")
                        .param("from", "2026-02-06T00:00:00Z")
                        .param("to", "2026-05-06T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timestampsIncluded").value(false))
                .andExpect(jsonPath("$.timestamps").doesNotExist())
                .andExpect(jsonPath("$.totalPlayCount").value(500));
    }

    @Test
    @WithMockUser(username = "alice", roles = "ADVERTISER")
    void getContentStats_advertiserPassesFlagToService() throws Exception {
        var stats = new ContentStatsService.ContentStats(
                12L, "advertiser-ad.mp4",
                Instant.parse("2026-04-29T00:00:00Z"),
                Instant.parse("2026-05-06T00:00:00Z"),
                10L, List.of(), true,
                new PageImpl<>(List.of()));
        when(contentStatsService.getStats(eq(12L), any(), any(), any(), any(),
                eq("alice"), eq(true), eq(false), any())).thenReturn(stats);

        mockMvc.perform(get("/api/stats/content/12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contentFileId").value(12));
    }

    @Test
    @WithMockUser(roles = "ADVERTISER")
    void getContentStats_advertiserWithoutAccess_returns403() throws Exception {
        when(contentStatsService.getStats(eq(13L), any(), any(), any(), any(), any(), eq(true), anyBoolean(), any()))
                .thenThrow(new AccessForbiddenException("no access"));

        mockMvc.perform(get("/api/stats/content/13"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void getContentStats_unknownContent_returns404() throws Exception {
        when(contentStatsService.getStats(eq(999L), any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), anyBoolean(), any()))
                .thenThrow(new ResourceNotFoundException("ContentFile", 999L));

        mockMvc.perform(get("/api/stats/content/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void getContentStats_rangeOver90Days_returns400() throws Exception {
        when(contentStatsService.getStats(any(), any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), anyBoolean(), any()))
                .thenThrow(new IllegalArgumentException("Date range cannot exceed 90 days"));

        mockMvc.perform(get("/api/stats/content/14")
                        .param("from", "2025-01-01T00:00:00Z")
                        .param("to", "2026-05-06T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("90")));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void getContentStats_pageSizeOver100_returns400() throws Exception {
        when(contentStatsService.getStats(any(), any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), anyBoolean(), any()))
                .thenThrow(new IllegalArgumentException("Page size cannot exceed 100"));

        mockMvc.perform(get("/api/stats/content/15").param("size", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("100")));
    }

    @Test
    void getContentStats_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/stats/content/16"))
                .andExpect(status().isUnauthorized());
    }
}
