package uz.orientadvertise.services.api.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uz.orientadvertise.services.service.ApiKeyAuthenticationService;
import uz.orientadvertise.services.service.ApiKeyFailureRateLimiter;
import uz.orientadvertise.services.service.ApiKeyRateLimiter;

/**
 * Wires {@link ApiKeyAuthFilter} only when its collaborators are available in the context.
 * Splitting registration out of {@code SecurityConfig} keeps the JWT-only test slices
 * ({@code @WebMvcTest}) from failing to wire — Spring won't attempt to instantiate the
 * filter unless the {@code service} module beans are present.
 */
@Configuration
@ConditionalOnBean({ApiKeyAuthenticationService.class, ApiKeyRateLimiter.class,
        ApiKeyFailureRateLimiter.class})
public class ApiKeySecurityConfig {

    @Bean
    public ApiKeyAuthFilter apiKeyAuthFilter(ApiKeyAuthenticationService authService,
                                              ApiKeyRateLimiter rateLimiter,
                                              ApiKeyFailureRateLimiter failureRateLimiter) {
        return new ApiKeyAuthFilter(authService, rateLimiter, failureRateLimiter);
    }
}
