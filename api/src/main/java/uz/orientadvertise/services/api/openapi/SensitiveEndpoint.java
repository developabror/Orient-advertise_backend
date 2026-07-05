package uz.orientadvertise.services.api.openapi;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Marker for operations that mutate production state, expose protected data, or
 * irreversibly delete records. Adds the operation to the {@code Sensitive} tag — visible
 * as a dedicated section in the Swagger UI so reviewers can audit sensitive surface
 * area at a glance.
 *
 * <p>Endpoints SHOULD additionally prefix their {@code @Operation(summary = ...)} with
 * {@code "[SENSITIVE] "} so the marker is visible in the operation list view, not just
 * on the detail page.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Tag(name = "Sensitive", description = "Operations that mutate production state or expose protected data — audit logged")
public @interface SensitiveEndpoint {
}
