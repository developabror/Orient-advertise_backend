package uz.orientadvertise.services.api.openapi;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import uz.orientadvertise.services.Application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Verifies the `app.openapi.admin-only` switch. With the test profile setting it to
 * {@code false}, the OpenAPI JSON and Swagger UI are reachable without auth.
 */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.openapi.admin-only=false",
        // Test profile defaults springdoc to off; flip it back on so we can hit the real endpoints.
        "springdoc.api-docs.enabled=true",
        "springdoc.swagger-ui.enabled=true"
})
class SwaggerAccessTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void apiDocs_publicWhenSwitchOff() {
        var res = restTemplate.getForEntity("/v3/api-docs", String.class);
        assertEquals(HttpStatus.OK, res.getStatusCode());
    }

    @Test
    void swaggerUi_publicWhenSwitchOff() {
        var res = restTemplate.getForEntity("/swagger-ui/index.html", String.class);
        // 200 (the page itself), or 302 to the loader; either way NOT a 401/403.
        assertNotEquals(HttpStatus.UNAUTHORIZED, res.getStatusCode());
        assertNotEquals(HttpStatus.FORBIDDEN, res.getStatusCode());
    }

    @Test
    void apiDocsViaApiPrefix_notAuthBlocked_whenSwitchOff() {
        // SecurityConfig contract: the /api/-prefixed paths are not auth-gated when
        // the switch is off. Whether springdoc serves there depends on the operator
        // (proxy strip vs. context-path vs. springdoc.api-docs.path override), so the
        // assertion is "not 401/403" — 200 (springdoc serves) and 404 (it doesn't,
        // because no proxy rewrite is in place yet) are both acceptable outcomes for
        // this code-level contract.
        var res = restTemplate.getForEntity("/api/v3/api-docs", String.class);
        assertNotEquals(HttpStatus.UNAUTHORIZED, res.getStatusCode());
        assertNotEquals(HttpStatus.FORBIDDEN, res.getStatusCode());
    }

    @Test
    void swaggerUiViaApiPrefix_notAuthBlocked_whenSwitchOff() {
        var res = restTemplate.getForEntity("/api/swagger-ui/index.html", String.class);
        assertNotEquals(HttpStatus.UNAUTHORIZED, res.getStatusCode());
        assertNotEquals(HttpStatus.FORBIDDEN, res.getStatusCode());
    }

    @Test
    void apiDocsByGroup_returnsTheGroupSpec() {
        var res = restTemplate.getForEntity("/v3/api-docs/external", String.class);
        assertEquals(HttpStatus.OK, res.getStatusCode());
        // The external group spec must reference the X-API-Key scheme — verifies grouping
        // didn't drop the per-controller security override.
        assertEquals(true, res.getBody() != null && res.getBody().contains("X-API-Key"));
    }
}
