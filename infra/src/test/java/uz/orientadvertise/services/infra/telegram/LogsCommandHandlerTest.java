package uz.orientadvertise.services.infra.telegram;

import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.orientadvertise.services.infra.diagnostic.RetainedErrorBuffer;
import uz.orientadvertise.services.infra.diagnostic.RetainedErrorBuffer.RetainedError;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LogsCommandHandlerTest {

    private OrientTelegramBot bot;
    private LogsCommandHandler handler;

    @BeforeEach
    void setUp() {
        bot = mock(OrientTelegramBot.class);
        when(bot.send(anyString(), anyString(), anyString())).thenReturn(true);
        when(bot.send(anyString(), anyString())).thenReturn(true);
        handler = new LogsCommandHandler(bot);
        RetainedErrorBuffer.resetForTests();
    }

    @AfterEach
    void tearDown() {
        RetainedErrorBuffer.resetForTests();
    }

    @Test
    void name_isSlashLogs() {
        assertEquals("/logs", handler.name());
    }

    @Test
    void handle_sendsMarkdown_toRequestingChat() {
        seed("ERROR", "boom");
        handler.handle(100L, new String[0]);
        verify(bot).send(eq("100"), anyString(), eq("Markdown"));
    }

    // ---------- empty-buffer non-silent reply ----------

    @Test
    void handle_emptyBuffer_repliesNoRecentErrors_notSilence() {
        // Spec calls this out specifically — silence is confusing. The reply must
        // explicitly say "no recent errors" so an operator can tell the bot is alive.
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        when(bot.send(eq("100"), body.capture(), eq("Markdown"))).thenReturn(true);

        handler.handle(100L, new String[0]);

        assertTrue(body.getValue().contains("no recent errors"),
                "must contain 'no recent errors': " + body.getValue());
        // INFO severity (green) so absence of errors reads as positive.
        assertTrue(body.getValue().contains("🟢"),
                "INFO severity emoji on the empty case: " + body.getValue());
    }

    @Test
    void handle_onlyWarn_repliesNoRecentErrors() {
        // Buffer has WARN entries but no ERRORs — /logs filters to ERROR-only, so the
        // reply is the empty-case message even though the buffer isn't actually empty.
        seed("WARN", "warning one");
        seed("WARN", "warning two");

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        when(bot.send(eq("100"), body.capture(), eq("Markdown"))).thenReturn(true);

        handler.handle(100L, new String[0]);

        assertTrue(body.getValue().contains("no recent errors"));
    }

    // ---------- default 10 ----------

    @Test
    void handle_noArg_returnsDefault10Errors() {
        // Distinct, prefix-free tokens so substring assertions don't false-match
        // across "msg-1" / "msg-10".
        for (int i = 0; i < 15; i++) seed("ERROR", "<entry-" + i + ">");

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        when(bot.send(eq("100"), body.capture(), eq("Markdown"))).thenReturn(true);

        handler.handle(100L, new String[0]);

        // Response must contain 10 of the most recent errors (entry-5 through entry-14).
        for (int i = 5; i < 15; i++) {
            assertTrue(body.getValue().contains("<entry-" + i + ">"),
                    "expected entry-" + i + " in body: " + body.getValue());
        }
        // The 5 oldest must NOT be in the response.
        for (int i = 0; i < 5; i++) {
            assertFalse(body.getValue().contains("<entry-" + i + ">"),
                    "entry-" + i + " was older — should be excluded");
        }
        // Title reflects actual count.
        assertTrue(body.getValue().contains("Recent error logs (10)"),
                "title with count: " + body.getValue());
    }

    // ---------- /logs N argument ----------

    @Test
    void handle_validArgument_returnsThatMany() {
        for (int i = 0; i < 10; i++) seed("ERROR", "<entry-" + i + ">");

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        when(bot.send(eq("100"), body.capture(), eq("Markdown"))).thenReturn(true);

        handler.handle(100L, new String[] {"5"});

        assertTrue(body.getValue().contains("Recent error logs (5)"),
                "title shows 5: " + body.getValue());
        // Most-recent 5 → entry-5..entry-9.
        for (int i = 5; i < 10; i++) {
            assertTrue(body.getValue().contains("<entry-" + i + ">"));
        }
    }

    @Test
    void parseLimit_noArgs_default() {
        var p = LogsCommandHandler.parseLimit(new String[0]);
        assertEquals(LogsCommandHandler.DEFAULT_LIMIT, p.limit());
        assertNull(p.warning());
    }

    @Test
    void parseLimit_validNumber_used() {
        var p = LogsCommandHandler.parseLimit(new String[] {"7"});
        assertEquals(7, p.limit());
        assertNull(p.warning());
    }

    @Test
    void parseLimit_nonNumeric_fallsBackToDefault_withWarning() {
        // Non-numeric input must NOT silently succeed — the operator might think their
        // "verbose" flag did something. Surface the rejection.
        var p = LogsCommandHandler.parseLimit(new String[] {"verbose"});
        assertEquals(LogsCommandHandler.DEFAULT_LIMIT, p.limit());
        assertNotNull(p.warning());
        assertTrue(p.warning().contains("verbose"));
    }

    @Test
    void parseLimit_zero_fallsBackToDefault_withWarning() {
        var p = LogsCommandHandler.parseLimit(new String[] {"0"});
        assertEquals(LogsCommandHandler.DEFAULT_LIMIT, p.limit());
        assertNotNull(p.warning());
    }

    @Test
    void parseLimit_negative_fallsBackToDefault_withWarning() {
        var p = LogsCommandHandler.parseLimit(new String[] {"-3"});
        assertEquals(LogsCommandHandler.DEFAULT_LIMIT, p.limit());
        assertNotNull(p.warning());
    }

    @Test
    void parseLimit_aboveMax_clampedDown_withWarning() {
        var p = LogsCommandHandler.parseLimit(new String[] {"500"});
        assertEquals(LogsCommandHandler.MAX_LIMIT, p.limit());
        assertNotNull(p.warning());
        assertTrue(p.warning().contains("clamped"));
    }

    @Test
    void handle_invalidArgument_warningInResponseBody() {
        seed("ERROR", "boom");
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        when(bot.send(eq("100"), body.capture(), eq("Markdown"))).thenReturn(true);

        handler.handle(100L, new String[] {"not-a-number"});

        assertTrue(body.getValue().contains("not-a-number"),
                "warning surfaces the rejected token: " + body.getValue());
    }

    // ---------- format contract ----------

    @Test
    void formatLine_includesAllRequiredFields() {
        // Spec: timestamp, logger short name, message first 200 chars, exception class.
        var e = new RetainedError(
                Instant.parse("2026-05-07T09:30:00Z"),
                "ERROR",
                "uz.orientadvertise.services.service.FooService",
                "connection refused",
                "java.sql.SQLException: connection refused");

        String line = LogsCommandHandler.formatLine(e);

        assertTrue(line.contains("2026-05-07"), "date present: " + line);
        assertTrue(line.contains(":"), "time separator: " + line);
        assertTrue(line.contains("FooService"), "short logger name: " + line);
        assertFalse(line.contains("uz.orientadvertise.services"),
                "FQCN truncated to last segment: " + line);
        assertTrue(line.contains("connection refused"), "message: " + line);
        assertTrue(line.contains("[SQLException]"), "exception class in brackets: " + line);
    }

    @Test
    void formatLine_truncatesMessageAt200Chars() {
        String longMsg = "a".repeat(500);
        var e = new RetainedError(Instant.now(), "ERROR", "pl.x.Y", longMsg, null);

        String line = LogsCommandHandler.formatLine(e);

        // 200 'a's plus the ellipsis is the truncation contract.
        assertTrue(line.contains("a".repeat(200)));
        assertTrue(line.contains("…"), "ellipsis on truncation: " + line);
        assertFalse(line.contains("a".repeat(201)),
                "no 201st 'a' should appear (truncated): "
                        + line.length());
    }

    @Test
    void formatLine_messageBelow200_noEllipsis() {
        var e = new RetainedError(Instant.now(), "ERROR", "pl.x.Y", "short msg", null);
        String line = LogsCommandHandler.formatLine(e);
        assertTrue(line.contains("short msg"));
        assertFalse(line.contains("…"));
    }

    @Test
    void formatLine_nullMessage_substitutesPlaceholder() {
        var e = new RetainedError(Instant.now(), "ERROR", "pl.x.Y", null, null);
        String line = LogsCommandHandler.formatLine(e);
        assertTrue(line.contains("(no message)"));
    }

    @Test
    void formatLine_noException_noBrackets() {
        var e = new RetainedError(Instant.now(), "ERROR", "pl.x.Y", "msg", null);
        String line = LogsCommandHandler.formatLine(e);
        assertFalse(line.contains("["), "no exception bracket: " + line);
    }

    @Test
    void shortLoggerName_lastSegmentOfFqcn() {
        assertEquals("FooService", LogsCommandHandler.shortLoggerName("uz.x.y.FooService"));
        assertEquals("Bar", LogsCommandHandler.shortLoggerName("Bar")); // already short
        assertEquals("?", LogsCommandHandler.shortLoggerName(null));
        assertEquals("?", LogsCommandHandler.shortLoggerName(""));
    }

    @Test
    void exceptionClass_extractsSimpleNameFromStoredFormat() {
        // RetainedError.exception() is stored as either "FQCN" or "FQCN: msg" (matching
        // TelegramAppender.exceptionClassLine format).
        assertEquals("RuntimeException",
                LogsCommandHandler.exceptionClass("java.lang.RuntimeException: boom"));
        assertEquals("SQLException",
                LogsCommandHandler.exceptionClass("java.sql.SQLException"));
        assertNull(LogsCommandHandler.exceptionClass(null));
        assertNull(LogsCommandHandler.exceptionClass(""));
    }

    // ---------- buffer capacity & restart-clear semantics ----------

    @Test
    void capacityEnforcedAtBufferLevel() {
        // Spec: max 100 entries. The handler doesn't enforce this — RetainedErrorBuffer
        // does — but verify the read path respects the bound: 200 appended, only the
        // 100 newest survive in the ring.
        for (int i = 0; i < 200; i++) seed("ERROR", "<entry-" + i + ">");

        var entries = RetainedErrorBuffer.recentByLevel("ERROR", 200);
        assertTrue(entries.size() <= RetainedErrorBuffer.CAPACITY,
                "capacity " + RetainedErrorBuffer.CAPACITY + " enforced");
        // The newest one is still there, the oldest are gone.
        assertEquals("<entry-199>", entries.get(0).message());
    }

    @Test
    void resetForTests_simulatesRestart() {
        // The buffer is in-memory only — restart clears it. resetForTests is the test
        // hook that exercises the same observable behaviour.
        seed("ERROR", "before");
        assertTrue(RetainedErrorBuffer.size() > 0);

        RetainedErrorBuffer.resetForTests();
        assertEquals(0, RetainedErrorBuffer.size());

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        when(bot.send(eq("100"), body.capture(), eq("Markdown"))).thenReturn(true);
        handler.handle(100L, new String[0]);
        assertTrue(body.getValue().contains("no recent errors"),
                "post-restart buffer reads as empty");
    }

    // ---------- never-throws guarantee ----------

    @Test
    void handle_neverThrows_evenIfMarkdownSendThrows() {
        // /state and /health both have an outer no-throw envelope; /logs follows the
        // same posture. A failed first send must trigger the plain-text fallback.
        seed("ERROR", "boom");
        doThrow(new RuntimeException("ssl handshake"))
                .when(bot).send(eq("100"), anyString(), eq("Markdown"));

        handler.handle(100L, new String[0]);

        verify(bot, times(1)).send(eq("100"), anyString(), eq("Markdown"));
        ArgumentCaptor<String> plain = ArgumentCaptor.forClass(String.class);
        verify(bot, atLeastOnce()).send(eq("100"), plain.capture());
        assertTrue(plain.getValue().contains("/logs failed"),
                "plain-text fallback: " + plain.getValue());
    }

    // ---------- newest-first ordering ----------

    @Test
    void payload_listsNewestFirst() {
        // Three errors at distinct timestamps — the rendered body must show the newest
        // first.
        seed(Instant.now().minusSeconds(60), "ERROR", "oldest");
        seed(Instant.now().minusSeconds(30), "ERROR", "middle");
        seed(Instant.now().minusSeconds(1),  "ERROR", "newest");

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        when(bot.send(eq("100"), body.capture(), eq("Markdown"))).thenReturn(true);

        handler.handle(100L, new String[0]);

        String s = body.getValue();
        int newestIdx = s.indexOf("newest");
        int middleIdx = s.indexOf("middle");
        int oldestIdx = s.indexOf("oldest");
        assertTrue(newestIdx >= 0 && middleIdx >= 0 && oldestIdx >= 0);
        assertTrue(newestIdx < middleIdx, "newest before middle");
        assertTrue(middleIdx < oldestIdx, "middle before oldest");
    }

    // ---------- helpers ----------

    private static void seed(String level, String msg) {
        seed(Instant.now(), level, msg);
    }

    private static void seed(Instant ts, String level, String msg) {
        RetainedErrorBuffer.append(ts, level, "uz.orientadvertise.services.X", msg, null);
    }
}
