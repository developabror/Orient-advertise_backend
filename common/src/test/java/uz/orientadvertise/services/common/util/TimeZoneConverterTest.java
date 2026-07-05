package uz.orientadvertise.services.common.util;

import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TimeZoneConverterTest {

    @Test
    void toDisplayZone_convertsUtcToUtcPlus5() {
        var utc = Instant.parse("2025-06-01T00:00:00Z");
        var display = TimeZoneConverter.toDisplayZone(utc);
        assertEquals(5, display.getHour());
        assertEquals(1, display.getDayOfMonth());
    }

    @Test
    void toDisplayZone_nullReturnsNull() {
        assertNull(TimeZoneConverter.toDisplayZone(null));
    }

    @Test
    void formatForDisplay_producesCorrectString() {
        var utc = Instant.parse("2025-06-15T10:30:00Z");
        var formatted = TimeZoneConverter.formatForDisplay(utc);
        assertEquals("2025-06-15 15:30:00", formatted);
    }

    @Test
    void formatForDisplay_nullReturnsNull() {
        assertNull(TimeZoneConverter.formatForDisplay(null));
    }

    @Test
    void parseFromDisplay_convertsBackToUtc() {
        var utc = TimeZoneConverter.parseFromDisplay("2025-06-15 15:30:00");
        assertEquals(Instant.parse("2025-06-15T10:30:00Z"), utc);
    }

    @Test
    void roundTrip_preservesInstant() {
        var original = Instant.parse("2025-03-20T08:45:00Z");
        var displayed = TimeZoneConverter.formatForDisplay(original);
        var parsed = TimeZoneConverter.parseFromDisplay(displayed);
        assertEquals(original, parsed);
    }

    @Test
    void fromDisplayZone_convertsZonedToUtc() {
        // 2025-06-01 05:00 in UTC+5 = 2025-06-01 00:00 in UTC
        var displayTime = java.time.ZonedDateTime.of(2025, 6, 1, 5, 0, 0, 0, ZoneOffset.ofHours(5));
        var utc = TimeZoneConverter.fromDisplayZone(displayTime);
        assertEquals(Instant.parse("2025-06-01T00:00:00Z"), utc);
    }
}
