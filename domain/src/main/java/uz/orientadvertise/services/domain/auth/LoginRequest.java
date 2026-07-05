package uz.orientadvertise.services.domain.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank(message = "Username is required")
        String username,

        @NotBlank(message = "Password is required")
        @Size(min = 4, message = "Password must be at least 4 characters")
        String password
) {}
