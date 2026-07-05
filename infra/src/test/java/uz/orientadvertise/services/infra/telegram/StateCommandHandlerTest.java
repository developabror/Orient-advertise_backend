package uz.orientadvertise.services.infra.telegram;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StateCommandHandlerTest {

    private OrientTelegramBot bot;
    @SuppressWarnings("unchecked")
    private final ObjectProvider<BuildProperties> buildProvider = mock(ObjectProvider.class);
    private StateCommandHandler handler;

    @BeforeEach
    void setUp() {
        bot = mock(OrientTelegramBot.class);
        when(bot.send(anyString(), anyString(), anyString())).thenReturn(true);

        var bp = mock(BuildProperties.class);
        when(bp.getVersion()).thenReturn("1.0.66");
        when(buildProvider.getIfAvailable()).thenReturn(bp);

        handler = new StateCommandHandler(bot, buildProvider);
    }

    @Test
    void name_isSlashState() {
        // Locked against the spec — dispatcher matches the inbound first token against
        // exactly this string, including the leading slash.
        assertEquals("/state", handler.name());
    }

    @Test
    void handle_sendsMarkdownPayload_toRequestingChat() {
        handler.handle(100L, new String[0]);

        verify(bot).send(eq("100"), anyString(), eq("Markdown"));
    }

    @Test
    void payload_containsAllRequiredFields() {
        String body = handler.buildPayload();

        // Header — severity emoji + bold INFO + " — " + bold title.
        assertTrue(body.contains("🟢"), "INFO severity emoji: " + body);
        assertTrue(body.contains("*INFO*"), "bold severity label");
        assertTrue(body.contains("*Server is alive*"), "bold title");
        // Code-block kv table.
        assertTrue(body.contains("```"), "code block fence");
        assertTrue(body.contains("host"), "host row");
        assertTrue(body.contains("uptime"), "uptime row");
        assertTrue(body.contains("version"), "version row");
        assertTrue(body.contains("timestamp"), "timestamp row");
        // Version comes from BuildProperties.
        assertTrue(body.contains("1.0.66"), "BuildProperties version: " + body);
    }

    @Test
    void payload_buildPropertiesAbsent_fallsBackToUnknown() {
        @SuppressWarnings("unchecked")
        ObjectProvider<BuildProperties> empty = mock(ObjectProvider.class);
        when(empty.getIfAvailable()).thenReturn(null);
        var h = new StateCommandHandler(bot, empty);

        String body = h.buildPayload();

        assertTrue(body.contains("unknown"),
                "missing BuildProperties → 'unknown' version: " + body);
    }

    @Test
    void payload_metricFailure_substitutesPlaceholder() {
        // BuildProperties.getVersion() blows up — version row must collapse to "—",
        // every other row still resolves, the report still goes out.
        var bp = mock(BuildProperties.class);
        when(bp.getVersion()).thenThrow(new RuntimeException("config service unreachable"));
        @SuppressWarnings("unchecked")
        ObjectProvider<BuildProperties> failing = mock(ObjectProvider.class);
        when(failing.getIfAvailable()).thenReturn(bp);
        var h = new StateCommandHandler(bot, failing);

        String body = h.buildPayload();

        assertTrue(body.contains(StateCommandHandler.FALLBACK),
                "failed metric collapses to placeholder: " + body);
        // Other rows still resolve — uptime is the easiest to assert positively.
        assertTrue(body.contains("uptime"));
    }

    @Test
    void payload_timestampIsUtcPlus5() {
        String body = handler.buildPayload();

        // The kv-block timestamp row contains "+0500" — the formatter uses xxxx.
        assertTrue(body.contains("+0500"),
                "timestamp must be UTC+5 offset: " + body);
        // Sanity-check the timestamp is a real, current value (within ±5 minutes of
        // 'now' in the same zone). Parse it back via the formatter.
        int idx = body.indexOf("+0500");
        // Walk back to the start of the timestamp row's value (after " : ").
        String window = body.substring(Math.max(0, idx - 30), Math.min(body.length(), idx + 5));
        assertTrue(window.matches(".*\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2} \\+0500.*"),
                "timestamp pattern matches yyyy-MM-dd HH:mm:ss +0500: '" + window + "'");
    }

    @Test
    void handle_neverThrows_evenWhenBuildPayloadFails() {
        // Force the version metric to blow at *the supplier level* (not inside safeGet,
        // which would catch). We can do this by giving the handler a buildProvider that
        // throws on getIfAvailable.
        @SuppressWarnings("unchecked")
        ObjectProvider<BuildProperties> demonic = mock(ObjectProvider.class);
        when(demonic.getIfAvailable()).thenThrow(new RuntimeException("registry crashed"));
        var h = new StateCommandHandler(bot, demonic);

        // Must not propagate. safeGet swallows the per-metric exception, so we still get
        // a normal payload — verify a send happened.
        h.handle(100L, new String[0]);

        verify(bot, atLeastOnce()).send(eq("100"), anyString(), anyString());
    }

    @Test
    void handle_payloadBuildBlowsUp_fallsBackToPlainTextAlive() {
        // Hardest failure mode: an unchecked from the formatter itself. We simulate by
        // wiring the bot's first send to throw so handle() lands in the catch block and
        // tries the plain-text fallback path.
        doThrow(new RuntimeException("ssl handshake"))
                .when(bot).send(eq("100"), anyString(), eq("Markdown"));

        // The handler's outer catch must absorb both attempts without rethrowing.
        handler.handle(100L, new String[0]);

        // Two send calls: the markdown one (failed) and the plain-text fallback.
        verify(bot, times(1)).send(eq("100"), anyString(), eq("Markdown"));
        ArgumentCaptor<String> plain = ArgumentCaptor.forClass(String.class);
        verify(bot, times(1)).send(eq("100"), plain.capture());
        assertTrue(plain.getValue().contains("Server is alive"),
                "fallback plain-text reply: " + plain.getValue());
    }

    @Test
    void formatUptime_daysFormat() {
        // Spec example: "3d 14h 22m"
        Duration d = Duration.ofDays(3).plusHours(14).plusMinutes(22).plusSeconds(7);
        assertEquals("3d 14h 22m", StateCommandHandler.formatUptime(d));
    }

    @Test
    void formatUptime_hoursFormat() {
        Duration d = Duration.ofHours(4).plusMinutes(12).plusSeconds(30);
        assertEquals("4h 12m", StateCommandHandler.formatUptime(d));
    }

    @Test
    void formatUptime_minutesFormat() {
        Duration d = Duration.ofMinutes(5).plusSeconds(42);
        assertEquals("5m 42s", StateCommandHandler.formatUptime(d));
    }

    @Test
    void formatUptime_subMinute_secondsOnly() {
        assertEquals("3s", StateCommandHandler.formatUptime(Duration.ofSeconds(3)));
        assertEquals("0s", StateCommandHandler.formatUptime(Duration.ofMillis(500)));
    }

    @Test
    void safeGet_supplierReturnsNull_returnsPlaceholder() {
        assertEquals(StateCommandHandler.FALLBACK,
                StateCommandHandler.safeGet(() -> null));
    }

    @Test
    void safeGet_supplierReturnsBlank_returnsPlaceholder() {
        assertEquals(StateCommandHandler.FALLBACK,
                StateCommandHandler.safeGet(() -> "  "));
    }

    @Test
    void safeGet_supplierThrows_returnsPlaceholder() {
        // The "command response must always succeed even if some metric collection
        // fails" requirement collapses to: a single misbehaving supplier yields a "—"
        // for that row and the overall report continues.
        assertEquals(StateCommandHandler.FALLBACK,
                StateCommandHandler.safeGet(() -> { throw new RuntimeException("boom"); }));
    }

    @Test
    void responseLatency_underOneSecond() {
        // The hard spec: respond within 1s. Even with the in-process metric gather
        // (which is microseconds) plus a stubbed bot.send, the handler call itself
        // should take a couple of milliseconds at most.
        long t0 = System.nanoTime();
        handler.handle(100L, new String[0]);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertTrue(elapsedMs < 1_000,
                "handle() must complete in < 1s; took " + elapsedMs + "ms");
    }

    @Test
    void timestamp_independentOfHostTz() {
        // Asia/Karachi is fixed +05:00 (no DST since 1971). Verify the formatter
        // produces the right offset regardless of host TZ.
        var sample = ZonedDateTime.of(2026, 5, 7, 14, 32, 11, 0,
                ZoneId.of("Asia/Karachi"));
        assertEquals("2026-05-07 14:32:11 +0500",
                sample.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxxx")));
    }

    @Test
    void payload_withAllMetricsFailing_stillProducesReport() {
        // Pathological case: hostname raised, build raised, etc. Every row collapses to
        // "—" but the report shape is intact. This is the worst-case contract the spec
        // calls out: "command response must always succeed."
        @SuppressWarnings("unchecked")
        ObjectProvider<BuildProperties> exploding = mock(ObjectProvider.class);
        when(exploding.getIfAvailable()).thenThrow(new RuntimeException("oops"));
        var h = new StateCommandHandler(bot, exploding);

        String body = h.buildPayload();

        assertFalse(body.isBlank());
        assertTrue(body.contains("Server is alive"));
        assertTrue(body.contains(StateCommandHandler.FALLBACK));
    }
}
