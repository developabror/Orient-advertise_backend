package uz.orientadvertise.services.api.ws;

import java.net.URI;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import uz.orientadvertise.services.api.ws.DashboardHandshakeInterceptor;
import uz.orientadvertise.services.common.exception.AuthenticationException;
import uz.orientadvertise.services.domain.auth.TokenValidator;
import uz.orientadvertise.services.service.OperatorScopeResolver;
import uz.orientadvertise.services.service.OperatorScopeResolver.ScopedProjects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DashboardHandshakeInterceptorTest {

    private TokenValidator tokenValidator;
    private OperatorScopeResolver operatorScopeResolver;
    private DashboardHandshakeInterceptor interceptor;

    @BeforeEach
    void setUp() {
        tokenValidator = mock(TokenValidator.class);
        operatorScopeResolver = mock(OperatorScopeResolver.class);
        // Default: any accepted handshake resolves to an unrestricted (ADMIN-like) scope.
        lenient().when(operatorScopeResolver.resolveForUsername(any()))
                .thenReturn(new ScopedProjects(null, null, null, false));
        interceptor = new DashboardHandshakeInterceptor(tokenValidator, operatorScopeResolver);
    }

    @Test
    void noToken_returns401_andRejects() throws Exception {
        var req = wrap(new MockHttpServletRequest("GET", "/ws/dashboard"));
        var res = wrap(new MockHttpServletResponse());
        var attrs = new HashMap<String, Object>();

        boolean ok = interceptor.beforeHandshake(req, res, null, attrs);

        assertFalse(ok);
        assertEquals(HttpStatus.UNAUTHORIZED.value(),
                ((MockHttpServletResponse) res.getServletResponse()).getStatus());
    }

    @Test
    void invalidToken_returns401() throws Exception {
        var raw = new MockHttpServletRequest("GET", "/ws/dashboard");
        raw.addHeader("Authorization", "Bearer bogus");
        var req = wrap(raw);
        var res = wrap(new MockHttpServletResponse());
        doThrow(new AuthenticationException("invalid"))
                .when(tokenValidator).validateOrThrow(anyString());

        boolean ok = interceptor.beforeHandshake(req, res, null, new HashMap<>());

        assertFalse(ok);
        assertEquals(HttpStatus.UNAUTHORIZED.value(),
                ((MockHttpServletResponse) res.getServletResponse()).getStatus());
    }

    @Test
    void viewerRole_returns403() throws Exception {
        // Edge case: spec calls out 403 specifically for VIEWER and ADVERTISER.
        var raw = new MockHttpServletRequest("GET", "/ws/dashboard");
        raw.addHeader("Authorization", "Bearer good");
        var req = wrap(raw);
        var res = wrap(new MockHttpServletResponse());
        when(tokenValidator.extractRoles("good")).thenReturn(List.of("ROLE_VIEWER"));

        boolean ok = interceptor.beforeHandshake(req, res, null, new HashMap<>());

        assertFalse(ok);
        assertEquals(HttpStatus.FORBIDDEN.value(),
                ((MockHttpServletResponse) res.getServletResponse()).getStatus());
    }

    @Test
    void advertiserRole_returns403() throws Exception {
        var raw = new MockHttpServletRequest("GET", "/ws/dashboard");
        raw.addHeader("Authorization", "Bearer good");
        var req = wrap(raw);
        var res = wrap(new MockHttpServletResponse());
        when(tokenValidator.extractRoles("good")).thenReturn(List.of("ROLE_ADVERTISER"));

        boolean ok = interceptor.beforeHandshake(req, res, null, new HashMap<>());

        assertFalse(ok);
        assertEquals(HttpStatus.FORBIDDEN.value(),
                ((MockHttpServletResponse) res.getServletResponse()).getStatus());
    }

    @Test
    void adminRole_accepted() throws Exception {
        var raw = new MockHttpServletRequest("GET", "/ws/dashboard");
        raw.addHeader("Authorization", "Bearer good");
        var req = wrap(raw);
        var res = wrap(new MockHttpServletResponse());
        when(tokenValidator.extractRoles("good")).thenReturn(List.of("ROLE_ADMIN"));
        when(tokenValidator.extractUsername("good")).thenReturn("alice");
        var attrs = new HashMap<String, Object>();

        boolean ok = interceptor.beforeHandshake(req, res, null, attrs);

        assertTrue(ok);
        assertEquals("alice", attrs.get(DashboardHandshakeInterceptor.ATTR_USERNAME));
    }

    @Test
    void operatorRole_accepted() throws Exception {
        var raw = new MockHttpServletRequest("GET", "/ws/dashboard");
        raw.addHeader("Authorization", "Bearer good");
        var req = wrap(raw);
        var res = wrap(new MockHttpServletResponse());
        when(tokenValidator.extractRoles("good")).thenReturn(List.of("ROLE_OPERATOR"));
        when(tokenValidator.extractUsername("good")).thenReturn("bob");

        assertTrue(interceptor.beforeHandshake(req, res, null, new HashMap<>()));
    }

    @Test
    void browserFallback_acceptsAccessTokenQueryParam() throws Exception {
        // Browsers can't set Authorization on the WebSocket constructor — the dashboard
        // FE appends ?access_token=... to the URL. Interceptor must accept that.
        var raw = new MockHttpServletRequest("GET", "/ws/dashboard");
        raw.addParameter("access_token", "good");
        raw.setRequestURI("/ws/dashboard");
        var req = new ServletServerHttpRequest(raw);
        var res = wrap(new MockHttpServletResponse());
        when(tokenValidator.extractRoles("good")).thenReturn(List.of("ROLE_OPERATOR"));
        when(tokenValidator.extractUsername("good")).thenReturn("bob");

        assertTrue(interceptor.beforeHandshake(req, res, null, new HashMap<>()));
    }

    private static ServletServerHttpRequest wrap(MockHttpServletRequest req) {
        return new ServletServerHttpRequest(req);
    }

    private static ServletServerHttpResponse wrap(MockHttpServletResponse res) {
        return new ServletServerHttpResponse(res);
    }

    @SuppressWarnings("unused")
    private static URI uri(String s) { return URI.create(s); }
}
