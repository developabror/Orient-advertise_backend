package uz.orientadvertise.services.api.audit;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditBodySanitizerTest {

    private static String sanitize(String json) {
        return AuditBodySanitizer.sanitize(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void passwordChangeFields_areMasked() {
        var out = sanitize("{\"currentPassword\":\"old-Pw1\",\"newPassword\":\"new-Pw2\",\"confirmPassword\":\"new-Pw2\"}");
        assertFalse(out.contains("old-Pw1"));
        assertFalse(out.contains("new-Pw2"));
        assertTrue(out.contains("***REDACTED***"));
    }

    @Test
    void mintedApiKey_isMasked_butItsIdentifiersStayReadable() {
        var out = sanitize("{\"id\":3,\"rawKey\":\"ak_live_secret\",\"prefix\":\"ak_live_\",\"keyPrefix\":\"ak_live_\",\"name\":\"partner\"}");
        assertFalse(out.contains("ak_live_secret"));
        assertTrue(out.contains("\"keyPrefix\":\"ak_live_\""));
        assertTrue(out.contains("\"name\":\"partner\""));
    }

    @Test
    void nestedObjectsArraysAndNonStringValues_areMasked() {
        var out = sanitize("{\"users\":[{\"name\":\"a\",\"password\":\"p1\"}],\"pin\":1234,"
                + "\"credentials\":{\"token\":{\"value\":\"t1\"}}}");
        assertFalse(out.contains("p1"));
        assertFalse(out.contains("1234"));
        assertFalse(out.contains("t1"));
        assertTrue(out.contains("\"name\":\"a\""));
    }

    @Test
    void escapedQuotes_cannotCutTheMaskShort() {
        // The old regex stopped at the first \" and stored everything after it.
        var out = sanitize("{\"password\":\"ab\\\"cd-rest-of-secret\"}");
        assertFalse(out.contains("rest-of-secret"));
    }

    @Test
    void aSecretStraddlingTheSizeLimit_isNeverPartlyStored() {
        // Truncating first (the old order) cut the value's closing quote off, so the mask missed it.
        var padding = "x".repeat(AuditBodySanitizer.MAX_CHARS - 20);
        var out = sanitize("{\"note\":\"" + padding + "\",\"password\":\"SECRET-" + "s".repeat(50) + "\"}");
        assertFalse(out.contains("SECRET-"));
        assertTrue(out.endsWith(AuditBodySanitizer.TRUNCATED_SUFFIX));
        assertEquals(AuditBodySanitizer.MAX_CHARS + AuditBodySanitizer.TRUNCATED_SUFFIX.length(), out.length());
    }

    @Test
    void multibyteText_survivesIntact() {
        assertTrue(sanitize("{\"name\":\"Ташкент — экран\"}").contains("Ташкент — экран"));
    }

    @Test
    void truncation_neverSplitsASurrogatePair() {
        var s = "a".repeat(AuditBodySanitizer.MAX_CHARS - 1) + "😀" + "tail";
        var out = AuditBodySanitizer.truncate(s);
        assertFalse(Character.isHighSurrogate(out.charAt(out.length() - AuditBodySanitizer.TRUNCATED_SUFFIX.length() - 1)));
    }

    @Test
    void nonJsonBodies_areOmitted_notStoredRaw() {
        assertEquals(AuditBodySanitizer.NON_JSON, sanitize("username=alice&password=hunter2"));
        assertEquals(AuditBodySanitizer.NON_JSON, sanitize("{\"password\":\"unterminated"));
    }

    @Test
    void emptyBody_isNull() {
        assertNull(AuditBodySanitizer.sanitize(new byte[0]));
        assertNull(AuditBodySanitizer.sanitize(null));
    }
}
