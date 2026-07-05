package uz.orientadvertise.services.api.security;

import java.util.Optional;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import uz.orientadvertise.services.api.security.ApiKeyAuthFilter;
import uz.orientadvertise.services.domain.model.ApiKey;
import uz.orientadvertise.services.service.ApiKeyAuthenticationService;
import uz.orientadvertise.services.service.ApiKeyFailureRateLimiter;
import uz.orientadvertise.services.service.ApiKeyRateLimiter;
import uz.orientadvertise.services.service.ApiKeyRateLimiter.Decision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiKeyAuthFilterTest {

    private ApiKeyAuthenticationService authService;
    private ApiKeyRateLimiter rateLimiter;
    private ApiKeyFailureRateLimiter failureRateLimiter;
    private ApiKeyAuthFilter filter;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        authService = mock(ApiKeyAuthenticationService.class);
        rateLimiter = mock(ApiKeyRateLimiter.class);
        failureRateLimiter = mock(ApiKeyFailureRateLimiter.class);
        // Default: failed-auth attempts are under the per-IP limit (→ 401, not 429).
        when(failureRateLimiter.allowFailure(any())).thenReturn(true);
        filter = new ApiKeyAuthFilter(authService, rateLimiter, failureRateLimiter);
        chain = mock(FilterChain.class);
        SecurityContextHolder.clearContext();
    }

    @Test
    void noHeader_passesThroughWithoutTouchingAuthOrRate() throws Exception {
        var req = new MockHttpServletRequest();
        var res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
        verify(authService, never()).authenticate(any());
        verify(rateLimiter, never()).tryAcquire(any());
        // No rate-limit headers when there was no API-key auth attempt.
        assertNull(res.getHeader(ApiKeyAuthFilter.HEADER_LIMIT));
    }

    @Test
    void invalidKey_returns401_withoutCallingChain() throws Exception {
        var req = new MockHttpServletRequest();
        req.addHeader(ApiKeyAuthFilter.HEADER, "bogus-key");
        when(authService.authenticate("bogus-key")).thenReturn(Optional.empty());
        var res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        assertEquals(401, res.getStatus());
        verify(chain, never()).doFilter(any(), any());
        // No rate-limit headers on auth failure — limiter wasn't even consulted.
        verify(rateLimiter, never()).tryAcquire(any());
    }

    @Test
    void invalidKey_overFailureLimit_returns429() throws Exception {
        // ext-dev-1: too many failed-auth attempts from one source → 429, not another 401,
        // so the key space can't be brute-forced through the 401 path.
        var req = new MockHttpServletRequest();
        req.addHeader(ApiKeyAuthFilter.HEADER, "bogus-key");
        when(authService.authenticate("bogus-key")).thenReturn(Optional.empty());
        when(failureRateLimiter.allowFailure(any())).thenReturn(false);
        var res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        assertEquals(429, res.getStatus());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void revokedKey_returns401_immediately() throws Exception {
        // Edge case: the auth service treats revoked exactly like unknown — both return
        // empty. The filter therefore writes 401 on the very next request after revoke.
        var req = new MockHttpServletRequest();
        req.addHeader(ApiKeyAuthFilter.HEADER, "revoked-key");
        when(authService.authenticate("revoked-key")).thenReturn(Optional.empty());
        var res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        assertEquals(401, res.getStatus());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void validKey_underLimit_setsHeadersAndContinues() throws Exception {
        var apiKey = mock(ApiKey.class);
        when(apiKey.getId()).thenReturn(7L);
        when(apiKey.getKeyPrefix()).thenReturn("pfx12345");
        when(authService.authenticate("good-key")).thenReturn(Optional.of(apiKey));
        when(rateLimiter.tryAcquire(eq(7L)))
                .thenReturn(new Decision(true, 100L, 99L, 1746547200L));

        var req = new MockHttpServletRequest();
        req.addHeader(ApiKeyAuthFilter.HEADER, "good-key");
        var res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
        assertEquals("100", res.getHeader(ApiKeyAuthFilter.HEADER_LIMIT));
        assertEquals("99", res.getHeader(ApiKeyAuthFilter.HEADER_REMAINING));
        assertEquals("1746547200", res.getHeader(ApiKeyAuthFilter.HEADER_RESET));
    }

    @Test
    void validKey_overLimit_returns429_withRateHeaders() throws Exception {
        // Headers must appear on 429 too — clients use them for backoff regardless of
        // whether their request was accepted.
        var apiKey = mock(ApiKey.class);
        when(apiKey.getId()).thenReturn(7L);
        when(apiKey.getKeyPrefix()).thenReturn("pfx12345");
        when(authService.authenticate("good-key")).thenReturn(Optional.of(apiKey));
        when(rateLimiter.tryAcquire(eq(7L)))
                .thenReturn(new Decision(false, 100L, 0L, 1746547200L));

        var req = new MockHttpServletRequest();
        req.addHeader(ApiKeyAuthFilter.HEADER, "good-key");
        var res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        assertEquals(429, res.getStatus());
        verify(chain, never()).doFilter(any(), any());
        assertEquals("100", res.getHeader(ApiKeyAuthFilter.HEADER_LIMIT));
        assertEquals("0", res.getHeader(ApiKeyAuthFilter.HEADER_REMAINING));
        assertEquals("1746547200", res.getHeader(ApiKeyAuthFilter.HEADER_RESET));
    }

    @Test
    void validKey_securityContextPrincipalIsPrefixNotInternalId() throws Exception {
        // "Never expose internal user IDs" — the SecurityContext principal is the public
        // 8-char prefix, never the numeric DB id.
        var apiKey = mock(ApiKey.class);
        when(apiKey.getId()).thenReturn(7L);
        when(apiKey.getKeyPrefix()).thenReturn("pfx12345");
        when(authService.authenticate("good-key")).thenReturn(Optional.of(apiKey));
        when(rateLimiter.tryAcquire(eq(7L)))
                .thenReturn(new Decision(true, 100L, 99L, 1746547200L));

        var req = new MockHttpServletRequest();
        req.addHeader(ApiKeyAuthFilter.HEADER, "good-key");

        var captured = new java.util.concurrent.atomic.AtomicReference<Object>();
        FilterChain capturingChain = (rq, rs) -> {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            assertNotNull(auth);
            captured.set(auth.getName());
            assertTrue(auth.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals(ApiKeyAuthFilter.ROLE)));
        };

        filter.doFilter(req, new MockHttpServletResponse(), capturingChain);

        assertEquals("pfx12345", captured.get());
        // Context cleared after the chain returns so a downstream request can't inherit.
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
}
