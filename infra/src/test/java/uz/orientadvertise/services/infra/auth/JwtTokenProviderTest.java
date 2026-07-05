package uz.orientadvertise.services.infra.auth;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.orientadvertise.services.common.exception.AuthenticationException;
import uz.orientadvertise.services.domain.auth.Role;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtTokenProviderTest {

    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        var properties = new JwtProperties();
        properties.setSecret("test-secret-key-must-be-at-least-32-chars");
        properties.setAccessTokenExpiryMinutes(15);
        provider = new JwtTokenProvider(properties);
    }

    @Test
    void createAccessToken_returnsNonNullToken() {
        var token = provider.createAccessToken("testuser", Role.VIEWER);
        assertNotNull(token);
        assertFalse(token.isBlank());
    }

    @Test
    void construct_blankSecret_failsFast() {
        var props = new JwtProperties();
        props.setSecret("");
        assertThrows(IllegalStateException.class, () -> new JwtTokenProvider(props));
    }

    @Test
    void construct_shortSecret_failsFast() {
        var props = new JwtProperties();
        props.setSecret("too-short"); // < 32 bytes
        assertThrows(IllegalStateException.class, () -> new JwtTokenProvider(props));
    }

    @Test
    void construct_nullSecret_failsFast() {
        var props = new JwtProperties(); // secret defaults to null now
        assertThrows(IllegalStateException.class, () -> new JwtTokenProvider(props));
    }

    @Test
    void extractUsername_returnsCorrectSubject() {
        var token = provider.createAccessToken("admin", Role.ADMIN);
        assertEquals("admin", provider.extractUsername(token));
    }

    @Test
    void extractRoles_returnsRoleFromToken() {
        var token = provider.createAccessToken("admin", Role.ADMIN);
        var roles = provider.extractRoles(token);
        assertEquals(List.of("ROLE_ADMIN"), roles);
    }

    @Test
    void extractRoles_returnsOperatorRole() {
        var token = provider.createAccessToken("op", Role.OPERATOR);
        var roles = provider.extractRoles(token);
        assertEquals(List.of("ROLE_OPERATOR"), roles);
    }

    @Test
    void isValid_returnsTrueForValidToken() {
        var token = provider.createAccessToken("user1", Role.VIEWER);
        assertTrue(provider.isValid(token));
    }

    @Test
    void isValid_returnsFalseForTamperedToken() {
        var token = provider.createAccessToken("user1", Role.VIEWER);
        assertFalse(provider.isValid(token + "x"));
    }

    @Test
    void isValid_returnsFalseForGarbageToken() {
        assertFalse(provider.isValid("not.a.jwt"));
    }

    @Test
    void validateOrThrow_doesNotThrowForValidToken() {
        var token = provider.createAccessToken("user1", Role.ADVERTISER);
        assertDoesNotThrow(() -> provider.validateOrThrow(token));
    }

    @Test
    void validateOrThrow_throwsForExpiredToken() {
        var properties = new JwtProperties();
        properties.setSecret("test-secret-key-must-be-at-least-32-chars");
        properties.setAccessTokenExpiryMinutes(0);
        var shortLivedProvider = new JwtTokenProvider(properties);

        var token = shortLivedProvider.createAccessToken("user1", Role.VIEWER);
        assertThrows(AuthenticationException.class, () -> shortLivedProvider.validateOrThrow(token));
    }

    @Test
    void validateOrThrow_throwsForInvalidToken() {
        assertThrows(AuthenticationException.class, () -> provider.validateOrThrow("invalid.token.here"));
    }

    @Test
    void differentSecrets_invalidateToken() {
        var token = provider.createAccessToken("user1", Role.ADMIN);

        var otherProperties = new JwtProperties();
        otherProperties.setSecret("different-secret-must-be-32-chars-too!");
        var otherProvider = new JwtTokenProvider(otherProperties);

        assertFalse(otherProvider.isValid(token));
    }
}
