package uz.orientadvertise.services.api.config;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springdoc.core.models.GroupedOpenApi;
import io.swagger.v3.oas.models.OpenAPI;
import uz.orientadvertise.services.Application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Confirms the OpenAPI surface is wired correctly: top-level info populated, both
 * security schemes registered, and a documentation group exists for every module
 * subgroup we expect external tooling to navigate to.
 */
@SpringBootTest(classes = Application.class)
@ActiveProfiles("test")
class OpenApiConfigTest {

    @Autowired
    private OpenAPI openAPI;

    @Autowired
    private List<GroupedOpenApi> groups;

    @Test
    void openApi_hasInfoTitleAndVersion() {
        assertNotNull(openAPI.getInfo());
        assertNotNull(openAPI.getInfo().getTitle());
        assertNotNull(openAPI.getInfo().getVersion());
    }

    @Test
    void openApi_hasBothSecuritySchemes() {
        // Both JWT and X-API-Key must be advertised so the Swagger UI Authorize dialog
        // surfaces the right input depending on which group the user is browsing.
        var schemes = openAPI.getComponents().getSecuritySchemes();
        assertNotNull(schemes);
        assertTrue(schemes.containsKey("bearerAuth"), "bearerAuth scheme missing");
        assertTrue(schemes.containsKey("apiKeyAuth"), "apiKeyAuth scheme missing");
        assertEquals("X-API-Key", schemes.get("apiKeyAuth").getName());
    }

    @Test
    void openApi_definesSensitiveTag() {
        // The Sensitive tag is what makes the SensitiveEndpoint marker visible in the UI.
        var tagNames = openAPI.getTags().stream()
                .map(io.swagger.v3.oas.models.tags.Tag::getName)
                .collect(Collectors.toSet());
        assertTrue(tagNames.contains("Sensitive"), "Sensitive tag missing");
    }

    @Test
    void groupedApis_includesAllModuleGroups() {
        Set<String> groupNames = groups.stream()
                .map(GroupedOpenApi::getGroup)
                .collect(Collectors.toSet());
        // External and admin groups are the security-critical surfaces — if the names
        // change, downstream doc tooling and operator runbooks break, so they're
        // pinned here.
        assertTrue(groupNames.contains("auth"));
        assertTrue(groupNames.contains("devices"));
        assertTrue(groupNames.contains("content"));
        assertTrue(groupNames.contains("reports"));
        assertTrue(groupNames.contains("admin"));
        assertTrue(groupNames.contains("external"));
    }
}
