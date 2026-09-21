package uz.orientadvertise.services.api.security;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import uz.orientadvertise.services.Application;
import uz.orientadvertise.services.service.LoginRateLimiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;

/**
 * AUTH-04 on a real Tomcat (MockMvc bypasses valves): with {@code server.forward-headers-strategy:
 * native}, the IP that reaches a per-IP rate limiter is resolved by {@code RemoteIpValve}. The test
 * client connects over loopback — a trusted proxy by default, as Caddy → Docker is in production —
 * so {@code X-Forwarded-For} is honoured, read right to left past trusted hops. Asserted through the
 * production path: the address {@code AuthController.refresh} hands to the refresh limiter (the
 * limiter runs before the missing-cookie 401, so no DB or Redis is touched).
 */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ForwardedClientIpIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @MockitoBean
    private LoginRateLimiter loginRateLimiter;

    static String ipSeenByTheLimiter(TestRestTemplate rest, LoginRateLimiter limiter, String forwardedFor) {
        var headers = new HttpHeaders();
        if (forwardedFor != null) {
            headers.add("X-Forwarded-For", forwardedFor);
        }
        rest.exchange("/api/auth/refresh", HttpMethod.POST, new HttpEntity<>(headers), String.class);
        var ip = ArgumentCaptor.forClass(String.class);
        verify(limiter).checkRefreshAllowed(ip.capture());
        return ip.getValue();
    }

    @Test
    void forwardedForFromATrustedProxy_isTheClientIp() {
        assertEquals("203.0.113.50", ipSeenByTheLimiter(rest, loginRateLimiter, "203.0.113.50"));
    }

    @Test
    void aClientPrependedFakeEntry_isSkipped() {
        // The proxy appends the real client (203.0.113.50) after whatever the client sent. The old
        // code took the FIRST entry — the attacker's choice.
        assertEquals("203.0.113.50", ipSeenByTheLimiter(rest, loginRateLimiter, "6.6.6.6, 203.0.113.50"));
    }

    @Test
    void trailingTrustedHops_arePeeledOff() {
        assertEquals("203.0.113.50", ipSeenByTheLimiter(rest, loginRateLimiter, "203.0.113.50, 10.0.0.2"));
    }
}
