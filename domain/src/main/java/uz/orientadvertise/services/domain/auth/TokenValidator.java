package uz.orientadvertise.services.domain.auth;

import java.util.List;

public interface TokenValidator {

    void validateOrThrow(String token);

    String extractUsername(String token);

    List<String> extractRoles(String token);
}
