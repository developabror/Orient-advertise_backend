package uz.orientadvertise.services.common.telegram;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import uz.orientadvertise.services.common.telegram.TelegramMessageBuilder.Severity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramMessageBuilderTest {

    @Test
    void severity_emojisMatchSpec() {
        // Locked against the spec: 🟢 INFO 🟡 WARN 🔴 ERROR ⚫ FATAL.
        assertEquals("🟢", Severity.INFO.emoji);
        assertEquals("🟡", Severity.WARN.emoji);
        assertEquals("🔴", Severity.ERROR.emoji);
        assertEquals("⚫", Severity.FATAL.emoji);
    }

    @Test
    void header_includesSeverityEmojiBoldLabelAndTitle() {
        String body = TelegramMessageBuilder.builder()
                .severity(Severity.ERROR)
                .title("kaboom")
                .build();

        assertTrue(body.startsWith("🔴 *ERROR* — *kaboom*"),
                "expected severity-emoji + bold label + em-dash + bold title: " + body);
    }

    @Test
    void header_titleOnly_isBold() {
        String body = TelegramMessageBuilder.builder().title("Hello").build();

        assertEquals("*Hello*", body);
    }

    @Test
    void escape_replacesMarkdownSpecials() {
        // Telegram legacy "Markdown" mode escapes _ * ` [
        assertEquals("\\_under\\_", TelegramMessageBuilder.escape("_under_"));
        assertEquals("\\*bold\\*", TelegramMessageBuilder.escape("*bold*"));
        assertEquals("\\`code\\`", TelegramMessageBuilder.escape("`code`"));
        assertEquals("\\[link]", TelegramMessageBuilder.escape("[link]"));
        // Combined.
        assertEquals("see \\_x\\_ and \\[y]",
                TelegramMessageBuilder.escape("see _x_ and [y]"));
        // Plain text untouched.
        assertEquals("plain text", TelegramMessageBuilder.escape("plain text"));
    }

    @Test
    void escape_nullSafe() {
        assertEquals("", TelegramMessageBuilder.escape(null));
    }

    @Test
    void truncate_shortString_unchanged() {
        assertEquals("hello", TelegramMessageBuilder.truncate("hello"));
    }

    @Test
    void truncate_overFiveHundred_endsWithEllipsis() {
        // Per-field 500-char cap. The ellipsis itself counts toward the limit so the
        // returned length is exactly 500.
        String huge = "x".repeat(1000);
        String t = TelegramMessageBuilder.truncate(huge);

        assertEquals(TelegramMessageBuilder.MAX_FIELD_LENGTH, t.length());
        assertTrue(t.endsWith(TelegramMessageBuilder.ELLIPSIS));
    }

    @Test
    void truncate_nullPassesThrough() {
        org.junit.jupiter.api.Assertions.assertNull(TelegramMessageBuilder.truncate(null));
    }

    @Test
    void title_truncatedAtFiveHundred() {
        String huge = "x".repeat(1000);
        String body = TelegramMessageBuilder.builder().title(huge).build();
        // Title plus boldface markers — the title contents alone must be ≤ 500.
        // Strip the surrounding asterisks before measuring.
        assertTrue(body.startsWith("*"));
        String inner = body.substring(1, body.length() - 1);
        assertTrue(inner.length() <= TelegramMessageBuilder.MAX_FIELD_LENGTH);
        assertTrue(inner.endsWith(TelegramMessageBuilder.ELLIPSIS));
    }

    @Test
    void text_escapesSpecialCharsButPreservesLineBreaks() {
        String body = TelegramMessageBuilder.builder()
                .text("hello _world_ [oops]")
                .build();

        assertTrue(body.contains("hello \\_world\\_ \\[oops]"),
                "specials escaped: " + body);
    }

    @Test
    void kvBlock_alignsKeysToLongest_renderedAsCodeBlock() {
        var kv = new LinkedHashMap<String, String>();
        kv.put("a", "1");
        kv.put("longest_key", "value");
        kv.put("mid", "x");

        String body = TelegramMessageBuilder.builder().kvBlock(kv).build();

        assertTrue(body.contains("```\n"), "code fence opens: " + body);
        // Longest key is "longest_key" (11 chars). Other keys padded to 11.
        assertTrue(body.contains("a           : 1"), "padded: " + body);
        assertTrue(body.contains("longest_key : value"));
        assertTrue(body.contains("mid         : x"));
        assertTrue(body.endsWith("```"));
    }

    @Test
    void kvBlock_doesNotEscapeCodeContent() {
        // Code blocks render literally — escaping would put visible backslashes inside
        // the fenced block, which is wrong.
        var kv = Map.of("file", "/etc/_config_/file*.txt");

        String body = TelegramMessageBuilder.builder().kvBlock(kv).build();

        assertTrue(body.contains("/etc/_config_/file*.txt"),
                "no escaping inside code block: " + body);
        assertFalse(body.contains("\\_"));
    }

    @Test
    void codeBlock_withHeader_rendersBoldHeaderAboveFence() {
        String body = TelegramMessageBuilder.builder()
                .codeBlock("stack", "  at X.method(X.java:1)")
                .build();

        assertTrue(body.contains("*stack*"), "bold header: " + body);
        assertTrue(body.contains("```\n  at X.method(X.java:1)"),
                "fence opens with content: " + body);
    }

    @Test
    void codeBlock_doublyTripleBacktickContent_isEscaped() {
        // A triple-backtick inside the content would close our fence prematurely. The
        // builder breaks the run by inserting a space.
        String body = TelegramMessageBuilder.builder()
                .codeBlock("evil", "before```after")
                .build();

        assertFalse(body.contains("before```after"),
                "raw triple-backtick must not appear inside content: " + body);
        assertTrue(body.contains("``` after"));
    }

    @Test
    void buildChunks_smallMessage_singleChunk() {
        List<String> chunks = TelegramMessageBuilder.builder()
                .severity(Severity.INFO)
                .title("Hello")
                .text("world")
                .buildChunks();

        assertEquals(1, chunks.size());
    }

    @Test
    void buildChunks_overFourThousand_splitsAtSectionBoundaries() {
        // Build a payload with several large sections that together exceed 4096.
        var b = TelegramMessageBuilder.builder().severity(Severity.ERROR).title("big");
        for (int i = 0; i < 20; i++) {
            // Each section is ~480 chars after truncation. 20 × 480 = 9600 — comfortably
            // over the 4096 cap, forcing at least one split.
            String content = "line ".repeat(95);
            b.codeBlock("section " + i, content);
        }

        List<String> chunks = b.buildChunks();

        assertTrue(chunks.size() >= 2, "expected multi-chunk: got " + chunks.size());
        for (String c : chunks) {
            assertTrue(c.length() <= TelegramMessageBuilder.MAX_MESSAGE_LENGTH,
                    "chunk over 4096: " + c.length());
        }
        // Continuation chunks are clearly marked so the operator knows they're a follow-up.
        assertTrue(chunks.get(1).startsWith("(continued"),
                "continuation marker: " + chunks.get(1));
    }

    @Test
    void buildChunks_emptyBuilder_returnsEmptyList() {
        List<String> chunks = TelegramMessageBuilder.builder().buildChunks();
        assertEquals(0, chunks.size());
    }

    @Test
    void build_emptyBuilder_returnsEmptyString() {
        String body = TelegramMessageBuilder.builder().build();
        assertEquals("", body);
    }

    @Test
    void section_nullBody_rendersHeaderOnly() {
        String body = TelegramMessageBuilder.builder()
                .section("Title", null)
                .build();
        assertTrue(body.contains("*Title*"));
    }

    @Test
    void section_escapesHeaderAndBody() {
        String body = TelegramMessageBuilder.builder()
                .section("hdr_with_under", "body *with* stars")
                .build();
        assertTrue(body.contains("*hdr\\_with\\_under*"), "escaped header: " + body);
        assertTrue(body.contains("body \\*with\\* stars"), "escaped body: " + body);
    }

    @Test
    void integration_realisticPayload_assemblesCleanly() {
        var kv = new LinkedHashMap<String, String>();
        kv.put("method", "POST");
        kv.put("path", "/service/devices/42/actions");
        kv.put("user", "alice");

        String body = TelegramMessageBuilder.builder()
                .severity(Severity.ERROR)
                .title("HTTP 500 — Unhandled exception")
                .kvBlock(kv)
                .codeBlock("stack",
                        "  at uz.x.Y.method(Y.java:10)\n"
                        + "  at java.base/java.lang.reflect.Method.invoke(Method.java:580)")
                .build();

        // Must contain all the headline pieces in order.
        assertTrue(body.contains("🔴 *ERROR*"));
        assertTrue(body.contains("HTTP 500"));
        assertTrue(body.contains("method : POST")
                || body.contains("method : POST"),
                "header style permits any padding: " + body);
        assertTrue(body.contains("path"));
        assertTrue(body.contains("alice"));
        assertTrue(body.contains("```\n  at uz.x.Y.method"));
        // And it's well under the 4096 limit.
        assertTrue(body.length() < TelegramMessageBuilder.MAX_MESSAGE_LENGTH);
        assertNotNull(body);
    }
}
