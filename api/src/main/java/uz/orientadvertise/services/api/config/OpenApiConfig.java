package uz.orientadvertise.services.api.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI / Swagger configuration.
 *
 * <p>Two top-level security schemes are exposed in the spec — {@code bearerAuth} (JWT)
 * and {@code apiKeyAuth} (the {@code X-API-Key} header used by the external integration
 * endpoints). Operations declare which scheme(s) they accept via {@code @Operation
 * (security = ...)}; controllers without an explicit declaration inherit the default
 * {@code bearerAuth} requirement set on the top-level OpenAPI. The external endpoints
 * override this with {@code apiKeyAuth} so the Swagger UI's "Authorize" dialog shows
 * the right input.
 *
 * <p>The spec is split into module groups via {@link GroupedOpenApi}: each group
 * filters to a path pattern, so the docs page surfaces one group selector per module
 * instead of a single 100+-endpoint list. The groups use names that match the URL
 * segment they serve so links to {@code /v3/service-docs/{group}} stay obvious.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";
    private static final String API_KEY_SCHEME = "apiKeyAuth";

    @Value("${spring.application.name:Orient-advertise-backend}")
    private String applicationName;

    @Value("${app.version:1.0.55}")
    private String version;

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title(applicationName + " API")
                        .version(version)
                        .description("""
                                Multi-module Spring Boot platform for TV-box content delivery — \
                                device management, content uploads + transcoding, playlists, \
                                scheduling, remote actions, incident lifecycle, proof-of-play, \
                                aggregated reporting, Excel export, and external partner integrations.

                                **Authentication.** Most endpoints require a JWT access token \
                                (`Authorization: Bearer <token>`) obtained from `POST /auth/login`. \
                                External integration endpoints under `/service/external/**` use an \
                                `X-API-Key` header instead — see the *External* group.

                                **Sensitive endpoints.** Operations marked with `[SENSITIVE]` in \
                                their summary affect production state, expose user data, or \
                                irreversibly delete records. Audit logging is enabled and the \
                                operator's username is recorded.
                                """)
                        .contact(new Contact().name("orientadvertise.services"))
                        .license(new License().name("Apache 2.0").url("https://www.apache.org/licenses/LICENSE-2.0")))
                .servers(List.of(
                        new Server().url("/").description("Current host"),
                        new Server().url("https://api.example.com").description("Production")))
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("JWT access token from `POST /auth/login`. "
                                        + "Roles encoded in the token: ADMIN, OPERATOR, VIEWER, ADVERTISER."))
                        .addSecuritySchemes(API_KEY_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-API-Key")
                                .description("External-integration API key. Issued by ADMIN via "
                                        + "`POST /service/admin/service-keys`. Rate-limited 100 req/hr per key. "
                                        + "Revocation is immediate — no in-memory cache widens the window.")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME))
                .tags(List.of(
                        new Tag().name("Auth").description("Login, refresh, logout — JWT lifecycle"),
                        new Tag().name("Devices").description("Device registration, sync, heartbeat, control"),
                        new Tag().name("Content").description("Content upload, transcoding, assignment"),
                        new Tag().name("Playback").description("Proof-of-play recording and statistics"),
                        new Tag().name("Incidents").description("Operational incident lifecycle"),
                        new Tag().name("Reports").description("Aggregated reports and Excel export"),
                        new Tag().name("Admin").description("ADMIN-only user/key/lifecycle operations"),
                        new Tag().name("External").description("Partner integration via X-API-Key"),
                        new Tag().name("Sensitive").description("Operations that mutate production state "
                                + "or expose protected data — audit logged")));
    }

    /**
     * Catch-all group that surfaces every endpoint in a single Swagger UI page so a
     * developer can grep through the whole surface without flipping between module
     * dropdowns. Listed first so it is the default view in the UI.
     */
    @Bean
    public GroupedOpenApi allGroup() {
        return GroupedOpenApi.builder()
                .group("all")
                .displayName("All endpoints")
                .pathsToMatch("/**")
                .build();
    }

    @Bean
    public GroupedOpenApi authGroup() {
        return GroupedOpenApi.builder()
                .group("auth")
                .displayName("Authentication")
                .pathsToMatch("/auth/**", "/api/me")
                .build();
    }

    @Bean
    public GroupedOpenApi devicesGroup() {
        return GroupedOpenApi.builder()
                .group("devices")
                .displayName("Devices")
                // Match the actual @RequestMapping prefixes; the listing and CRUD for
                // device groups live under /api/device-groups/**.
                .pathsToMatch("/api/devices/**", "/api/device-groups/**",
                        "/api/regions/**", "/api/facilities/**", "/api/projects/**")
                .build();
    }

    @Bean
    public GroupedOpenApi contentGroup() {
        // Path prefixes match the actual @RequestMapping values on the controllers
        // (/api/...). The matcher on /api/content/** picks up both the listing and the
        // detail endpoint under that controller.
        return GroupedOpenApi.builder()
                .group("content")
                .displayName("Content & Files")
                .pathsToMatch("/api/content/**", "/api/files/**", "/api/assignments/**", "/api/schedules/**", "/api/playlists/**")
                .build();
    }

    @Bean
    public GroupedOpenApi reportsGroup() {
        return GroupedOpenApi.builder()
                .group("reports")
                .displayName("Reports & Stats")
                .pathsToMatch("/service/reports/**", "/service/stats/**", "/service/events/**", "/service/incidents/**")
                .build();
    }

    @Bean
    public GroupedOpenApi adminGroup() {
        return GroupedOpenApi.builder()
                .group("admin")
                .displayName("Admin")
                .pathsToMatch("/service/admin/**", "/service/users/**")
                .build();
    }

    @Bean
    public GroupedOpenApi externalGroup() {
        return GroupedOpenApi.builder()
                .group("external")
                .displayName("External (X-API-Key)")
                .pathsToMatch("/service/external/**")
                .build();
    }
}
