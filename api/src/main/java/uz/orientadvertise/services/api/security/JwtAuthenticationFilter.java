package uz.orientadvertise.services.api.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import uz.orientadvertise.services.common.exception.AuthenticationException;
import uz.orientadvertise.services.domain.auth.TokenValidator;
import uz.orientadvertise.services.domain.auth.UserActiveChecker;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final TokenValidator tokenValidator;
    private final UserActiveChecker userActiveChecker;

    public JwtAuthenticationFilter(TokenValidator tokenValidator, UserActiveChecker userActiveChecker) {
        this.tokenValidator = tokenValidator;
        this.userActiveChecker = userActiveChecker;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        var token = extractToken(request);

        if (token != null) {
            try {
                tokenValidator.validateOrThrow(token);
                var username = tokenValidator.extractUsername(token);

                // Deactivated users are rejected even with a valid token
                if (!userActiveChecker.isActive(username)) {
                    SecurityContextHolder.clearContext();
                    filterChain.doFilter(request, response);
                    return;
                }

                var roles = tokenValidator.extractRoles(token);
                var authorities = roles.stream()
                        .map(SimpleGrantedAuthority::new)
                        .toList();
                var authentication = new UsernamePasswordAuthenticationToken(username, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (AuthenticationException e) {
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        var header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}
