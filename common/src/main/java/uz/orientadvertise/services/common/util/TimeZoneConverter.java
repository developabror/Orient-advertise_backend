package uz.orientadvertise.services.common.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

public final class TimeZoneConverter {

    public static final ZoneId DISPLAY_ZONE = ZoneOffset.ofHours(5);
    private static final DateTimeFormatter DISPLAY_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(DISPLAY_ZONE);

    private TimeZoneConverter() {
    }

    public static ZonedDateTime toDisplayZone(Instant utcInstant) {
        if (utcInstant == null) {
            return null;
        }
        return utcInstant.atZone(DISPLAY_ZONE);
    }

    public static String formatForDisplay(Instant utcInstant) {
        if (utcInstant == null) {
            return null;
        }
        return DISPLAY_FORMATTER.format(utcInstant);
    }

    public static Instant fromDisplayZone(ZonedDateTime displayTime) {
        if (displayTime == null) {
            return null;
        }
        return displayTime.toInstant();
    }

    public static Instant parseFromDisplay(String displayTimeStr) {
        if (displayTimeStr == null || displayTimeStr.isBlank()) {
            return null;
        }
        var local = java.time.LocalDateTime.parse(displayTimeStr,
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        return local.atZone(DISPLAY_ZONE).toInstant();
    }
}
