package uz.orientadvertise.services.api.openapi;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import uz.orientadvertise.services.Application;

/**
 * Programmatic exporter: writes the live springdoc spec to {@code frontend/docs/openapi.json}.
 *
 * <p>Gated by {@code -Dexport.openapi=true} so a normal {@code ./gradlew :api:test} run
 * skips this entirely — CI is unaffected. Run after touching any API surface:
 *
 * <pre>
 *   ./gradlew :api:test --tests "*OpenApiExporter*" -Dexport.openapi=true
 * </pre>
 *
 * <p>The {@code test} profile re-routes the datasource to in-memory H2, but the
 * non-conditional {@code RedisMessageListenerContainer} bean still tries to open a
 * real Redis connection on startup — so before running the exporter, bring up Redis
 * locally ({@code docker compose up -d redis}). The exporter does NOT mock those
 * beans, because mocking the listener container would skew the springdoc scan if a
 * controller happens to reference a Redis-backed collaborator at bean post-processing.
 *
 * <p>The exporter overrides {@code springdoc.api-docs.enabled} back to {@code true} —
 * the {@code test} profile turns it off for unrelated test slices.
 */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "springdoc.api-docs.enabled=true",
        "springdoc.swagger-ui.enabled=true",
        "app.openapi.admin-only=false"
})
@EnabledIfSystemProperty(named = "export.openapi", matches = "true")
class OpenApiExporter {

    private static final String OUTPUT_RELATIVE = "frontend/docs/openapi.json";

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void exportOpenApiSpecToFrontendDocs() throws Exception {
        var json = restTemplate.getForObject("/v3/api-docs", String.class);
        if (json == null || json.isBlank()) {
            throw new IllegalStateException("springdoc returned no body — is /v3/api-docs reachable in this profile?");
        }

        var mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        var tree = mapper.readTree(json);

        Path target = resolveOutputPath();
        Files.createDirectories(target.getParent());
        Files.writeString(target, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(tree));
    }

    /**
     * Anchor {@code frontend/docs/openapi.json} relative to whichever directory Gradle
     * launched the test from — {@code :api:test} runs in {@code api/}, the IDE typically
     * runs in the project root. Walk up until we find the {@code frontend/} sibling.
     */
    private Path resolveOutputPath() {
        Path cwd = Paths.get("").toAbsolutePath();
        for (Path candidate = cwd; candidate != null; candidate = candidate.getParent()) {
            Path docs = candidate.resolve("frontend").resolve("docs");
            if (Files.exists(candidate.resolve("frontend"))) {
                return docs.resolve("openapi.json");
            }
        }
        // Fallback: write into the cwd so the file is at least visible — better than
        // an opaque NPE when the project layout shifts.
        return cwd.resolve(OUTPUT_RELATIVE);
    }
}
