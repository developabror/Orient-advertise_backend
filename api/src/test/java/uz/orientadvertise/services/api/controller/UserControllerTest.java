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
import uz.orientadvertise.services.api.controller.UserController;
import uz.orientadvertise.services.api.security.JwtAuthenticationFilter;
import uz.orientadvertise.services.api.security.SecurityConfig;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.audit.AuditRecorder;
import uz.orientadvertise.services.domain.auth.Role;
import uz.orientadvertise.services.domain.auth.TokenValidator;
import uz.orientadvertise.services.domain.auth.UserActiveChecker;
import uz.orientadvertise.services.domain.model.AppUser;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.service.AdvertiserContentService;
import uz.orientadvertise.services.service.OperatorContentService;
import uz.orientadvertise.services.service.UserManagementService;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserManagementService userManagementService;

    @MockitoBean
    private AdvertiserContentService advertiserContentService;

    @MockitoBean
    private OperatorContentService operatorContentService;

    @MockitoBean
    private TokenValidator tokenValidator;

    @MockitoBean
    private UserActiveChecker userActiveChecker;

    @MockitoBean
    private AuditRecorder auditRecorder;

    @Test
    @WithMockUser(roles = "ADMIN")
    void list_admin_returnsPagedUsers() throws Exception {
        var alice = mock(AppUser.class);
        when(alice.getId()).thenReturn(1L);
        when(alice.getUsername()).thenReturn("alice");
        when(alice.getRole()).thenReturn(Role.ADMIN);
        when(alice.isActive()).thenReturn(true);
        var bob = mock(AppUser.class);
        when(bob.getId()).thenReturn(2L);
        when(bob.getUsername()).thenReturn("bob");
        when(bob.getRole()).thenReturn(Role.OPERATOR);
        when(bob.isActive()).thenReturn(false);
        Page<AppUser> page = new PageImpl<>(List.of(alice, bob), PageRequest.of(0, 20), 2);
        when(userManagementService.list(any())).thenReturn(page);

        mockMvc.perform(get("/api/users").param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].username").value("alice"))
                .andExpect(jsonPath("$.content[0].role").value("ADMIN"))
                .andExpect(jsonPath("$.content[0].active").value(true))
                .andExpect(jsonPath("$.content[1].username").value("bob"))
                .andExpect(jsonPath("$.content[1].active").value(false))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void list_nonAdmin_returns403() throws Exception {
        mockMvc.perform(get("/api/users"))
                .andExpect(status().isForbidden());
        verify(userManagementService, never()).list(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void list_emptyPage_returnsEmptyContent_not404() throws Exception {
        // Zero users matching a filter is NOT a 404 — the admin UI renders an empty
        // table without a special branch.
        when(userManagementService.list(any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/api/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_advertiser_returns201() throws Exception {
        var user = mock(AppUser.class);
        when(user.getId()).thenReturn(50L);
        when(user.getUsername()).thenReturn("alice");
        when(user.getRole()).thenReturn(Role.ADVERTISER);
        when(user.isActive()).thenReturn(true);
        // No email in the request → service called with a null email.
        when(userManagementService.create(eq("alice"), eq("secret123"), eq(Role.ADVERTISER), isNull()))
                .thenReturn(user);

        mockMvc.perform(post("/api/users")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"alice","password":"secret123","role":"ADVERTISER"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(50))
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.role").value("ADVERTISER"))
                .andExpect(jsonPath("$.active").value(true))
                // email is always present in the response (explicitly null here, not absent).
                .andExpect(jsonPath("$.email").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_withEmail_threadsEmailAndEchoesIt() throws Exception {
        var user = mock(AppUser.class);
        when(user.getId()).thenReturn(51L);
        when(user.getUsername()).thenReturn("bob");
        when(user.getRole()).thenReturn(Role.ADVERTISER);
        when(user.isActive()).thenReturn(true);
        when(user.getEmail()).thenReturn("bob@example.com");
        when(userManagementService.create(eq("bob"), eq("secret123"), eq(Role.ADVERTISER), eq("bob@example.com")))
                .thenReturn(user);

        mockMvc.perform(post("/api/users")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"bob","password":"secret123","role":"ADVERTISER","email":"bob@example.com"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("bob@example.com"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_invalidEmail_returns400() throws Exception {
        mockMvc.perform(post("/api/users")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"bob","password":"secret123","role":"ADVERTISER","email":"not-an-email"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void create_nonAdmin_returns403() throws Exception {
        mockMvc.perform(post("/api/users")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"alice","password":"secret123","role":"ADVERTISER"}"""))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_blankUsername_returns400() throws Exception {
        mockMvc.perform(post("/api/users")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"","password":"secret123","role":"ADVERTISER"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_unknownRole_returns400() throws Exception {
        mockMvc.perform(post("/api/users")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"alice","password":"secret123","role":"NOPE"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void delete_returns204() throws Exception {
        mockMvc.perform(delete("/api/users/7").with(csrf()))
                .andExpect(status().isNoContent());
        verify(userManagementService).delete(7L);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void delete_unknown_returns404() throws Exception {
        doThrow(new ResourceNotFoundException("User", 999L))
                .when(userManagementService).delete(999L);

        mockMvc.perform(delete("/api/users/999").with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void linkContent_returns201() throws Exception {
        mockMvc.perform(post("/api/users/7/content/10").with(csrf()))
                .andExpect(status().isCreated());
        verify(advertiserContentService).linkContent(eq(7L), eq(10L), any());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void linkContent_nonAdmin_returns403() throws Exception {
        mockMvc.perform(post("/api/users/7/content/10").with(csrf()))
                .andExpect(status().isForbidden());
        verify(advertiserContentService, never()).linkContent(any(), any(), any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void unlinkContent_returns204_evenWhenMissing() throws Exception {
        // Idempotent: missing grant returns 204 same as deleting an existing grant.
        when(advertiserContentService.unlinkContent(7L, 10L)).thenReturn(false);

        mockMvc.perform(delete("/api/users/7/content/10").with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void listLinkedContent_zeroLinks_returnsEmptyArray() throws Exception {
        // Edge case: zero linked content returns [], not 404.
        when(advertiserContentService.getAccessibleContent(7L)).thenReturn(List.of());

        mockMvc.perform(get("/api/users/7/content"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void listLinkedContent_returnsContentDescriptors() throws Exception {
        var f1 = mock(ContentFile.class);
        when(f1.getId()).thenReturn(10L);
        when(f1.getName()).thenReturn("ad.mp4");
        when(f1.getStatus()).thenReturn(ContentFile.Status.READY);
        when(advertiserContentService.getAccessibleContent(7L)).thenReturn(List.of(f1));

        mockMvc.perform(get("/api/users/7/content"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(10))
                .andExpect(jsonPath("$[0].name").value("ad.mp4"))
                .andExpect(jsonPath("$[0].status").value("READY"));
    }

    @Test
    void create_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/users")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"alice","password":"secret123","role":"ADVERTISER"}"""))
                .andExpect(status().isUnauthorized());
    }
}
