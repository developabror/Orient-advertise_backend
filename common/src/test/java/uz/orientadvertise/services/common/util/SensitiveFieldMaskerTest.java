package uz.orientadvertise.services.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensitiveFieldMaskerTest {

    @Test
    void mask_redactsPasswordField() {
        var json = "{\"username\":\"admin\",\"password\":\"secret123\"}";
        var result = SensitiveFieldMasker.mask(json);
        assertFalse(result.contains("secret123"));
        assertTrue(result.contains("***REDACTED***"));
        assertTrue(result.contains("\"username\":\"admin\""));
    }

    @Test
    void mask_redactsAccessToken() {
        var json = "{\"accessToken\":\"eyJhbGciOiJIUzI1NiJ9.abc\",\"refreshToken\":\"uuid-123\"}";
        var result = SensitiveFieldMasker.mask(json);
        assertFalse(result.contains("eyJhbGciOiJIUzI1NiJ9"));
        assertFalse(result.contains("uuid-123"));
        assertTrue(result.contains("***REDACTED***"));
    }

    @Test
    void mask_redactsSecretKey() {
        var json = "{\"secretKey\":\"my-secret-key\",\"url\":\"http://minio\"}";
        var result = SensitiveFieldMasker.mask(json);
        assertFalse(result.contains("my-secret-key"));
        assertTrue(result.contains("http://minio"));
    }

    @Test
    void mask_handlesNullInput() {
        assertNull(SensitiveFieldMasker.mask(null));
    }

    @Test
    void mask_handlesBlankInput() {
        assertEquals("", SensitiveFieldMasker.mask(""));
    }

    @Test
    void mask_preservesNonSensitiveFields() {
        var json = "{\"username\":\"admin\",\"email\":\"a@b.com\"}";
        var result = SensitiveFieldMasker.mask(json);
        assertEquals(json, result);
    }

    @Test
    void mask_caseInsensitive() {
        var json = "{\"Password\":\"secret\",\"TOKEN\":\"abc\"}";
        var result = SensitiveFieldMasker.mask(json);
        assertFalse(result.contains("secret"));
    }

    @Test
    void mask_handlesMultipleSensitiveFields() {
        var json = "{\"password\":\"p1\",\"token\":\"t1\",\"ssn\":\"123-45-6789\"}";
        var result = SensitiveFieldMasker.mask(json);
        assertFalse(result.contains("p1"));
        assertFalse(result.contains("t1"));
        assertFalse(result.contains("123-45-6789"));
    }
}
