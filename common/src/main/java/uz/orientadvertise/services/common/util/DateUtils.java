package uz.orientadvertise.services.common.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class DateUtils {

    private static final DateTimeFormatter ISO_FORMATTER =
            DateTimeFormatter.ISO_INSTANT.withZone(ZoneId.of("UTC"));

    private DateUtils() {
    }

    public static String nowIso() {
        return ISO_FORMATTER.format(Instant.now());
    }

    public static String toIso(Instant instant) {
        if (instant == null) {
            throw new IllegalArgumentException("instant must not be null");
        }
        return ISO_FORMATTER.format(instant);
    }
}
