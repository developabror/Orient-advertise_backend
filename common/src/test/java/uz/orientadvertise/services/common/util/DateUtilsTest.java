package uz.orientadvertise.services.common.util;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DateUtilsTest {

    @Test
    void nowIso_returnsNonNullFormattedString() {
        String result = DateUtils.nowIso();
        assertNotNull(result);
        assertTrue(result.contains("T"));
        assertTrue(result.endsWith("Z"));
    }

    @Test
    void toIso_formatsInstantCorrectly() {
        Instant instant = Instant.parse("2024-01-15T10:30:00Z");
        String result = DateUtils.toIso(instant);
        assertEquals("2024-01-15T10:30:00Z", result);
    }

    @Test
    void toIso_throwsOnNull() {
        assertThrows(IllegalArgumentException.class, () -> DateUtils.toIso(null));
    }
}
