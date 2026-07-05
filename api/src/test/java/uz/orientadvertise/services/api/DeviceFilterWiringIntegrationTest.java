package uz.orientadvertise.services.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import uz.orientadvertise.services.Application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Full-context regression guard for the {@code DeviceTokenAuthFilter} wiring.
 *
 * <p>A former {@code @ConditionalOnBean(DeviceRepository.class)} guard on a separate
 * {@code @Configuration} false-negatived against the Spring Data JPA repository at
 * configuration-parse time, so the filter was silently dropped in the real app and every
 * device-agent endpoint returned {@code 401 "Authentication required"} regardless of a
 * valid {@code X-Device-Token}. No slice test caught it because the bug only appears once
 * the full JPA layer is present. This boots the whole {@link Application} context (the
 * scenario where it broke) and asserts the filter is actually in the chain.
 *
 * <p>A request carrying a <em>bogus</em> token is the discriminator: with the filter wired,
 * the filter itself rejects the unknown token with {@code "Invalid or revoked device token"};
 * with the filter missing, the request falls through to the security entry point and gets the
 * generic {@code "Authentication required"}. No device row needs seeding — the bogus token
 * simply fails the lookup. (The API test profile excludes Redis auto-config, so this needs no
 * external infra, exactly like {@code HealthIntegrationTest}.)
 */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class DeviceFilterWiringIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void deviceEndpoint_withBogusToken_isRejectedByTheFilter_notTheEntryPoint() {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Device-Token", "dtk_regression_bogus");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/devices/1/heartbeat", HttpMethod.POST,
                new HttpEntity<>("{\"contentVersion\":null}", headers), String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertTrue(response.getBody() != null
                        && response.getBody().contains("Invalid or revoked device token"),
                "DeviceTokenAuthFilter is not wired into the chain — a bogus X-Device-Token fell "
                        + "through to the entry point instead of being rejected by the filter. Body: "
                        + response.getBody());
    }
}
