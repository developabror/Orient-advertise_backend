package uz.orientadvertise.services.api.security;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import uz.orientadvertise.services.Application;
import uz.orientadvertise.services.service.LoginRateLimiter;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AUTH-04, the negative case on a real Tomcat: when the direct peer is NOT a trusted proxy — here
 * loopback is removed from {@code internal-proxies}, standing in for a client that reaches the app
 * without going through Caddy — a client-supplied {@code X-Forwarded-For} is ignored and the limiter
 * sees the socket address. Before the fix the controller trusted the header's first entry from
 * anyone.
 */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
// Only the documentation-range proxy 192.0.2.1 is trusted (the regex needs its dots escaped, and
// @TestPropertySource strings are read like a .properties file, so each backslash is doubled).
@TestPropertySource(properties = "server.tomcat.remoteip.internal-proxies=192\\\\.0\\\\.2\\\\.1")
class UntrustedPeerClientIpIntegrationTest {

    private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "0:0:0:0:0:0:0:1", "::1");

    @Autowired
    private TestRestTemplate rest;

    @MockitoBean
    private LoginRateLimiter loginRateLimiter;

    @Test
    void forwardedForFromAnUntrustedPeer_isIgnored() {
        var ip = ForwardedClientIpIntegrationTest.ipSeenByTheLimiter(rest, loginRateLimiter, "9.9.9.9");

        assertTrue(LOOPBACK.contains(ip), "expected the socket address, got " + ip);
    }
}
