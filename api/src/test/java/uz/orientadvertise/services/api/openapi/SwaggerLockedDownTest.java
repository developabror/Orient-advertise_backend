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

/**
 * Mirror of {@link SwaggerAccessTest} with the lockdown ON — confirms anonymous access
 * is rejected with 401, matching the production default.
 */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.openapi.admin-only=true",
        "springdoc.api-docs.enabled=true",
        "springdoc.swagger-ui.enabled=true"
})
class SwaggerLockedDownTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void apiDocs_unauthenticated_returns401_inProductionMode() {
        var res = restTemplate.getForEntity("/v3/api-docs", String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, res.getStatusCode());
    }

    @Test
    void swaggerUi_unauthenticated_returns401_inProductionMode() {
        var res = restTemplate.getForEntity("/swagger-ui/index.html", String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, res.getStatusCode());
    }

    @Test
    void apiDocsViaApiPrefix_unauthenticated_returns401_inProductionMode() {
        var res = restTemplate.getForEntity("/api/v3/api-docs", String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, res.getStatusCode());
    }

    @Test
    void swaggerUiViaApiPrefix_unauthenticated_returns401_inProductionMode() {
        var res = restTemplate.getForEntity("/api/swagger-ui/index.html", String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, res.getStatusCode());
    }
}
