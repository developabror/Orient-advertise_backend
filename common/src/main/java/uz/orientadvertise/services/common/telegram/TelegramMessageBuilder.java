package uz.orientadvertise.services.common.telegram;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fluent builder that produces Telegram-Markdown payloads with consistent formatting
 * across the bot subsystem (startup / shutdown / log appender / 500 forward).
 *
 * <p>Lives in {@code common} (not {@code infra}) so the service-side
 * {@code GlobalExceptionHandler} can use it directly — strict layering keeps {@code service}
 * out of {@code infra} at compile time. Pure utility code: no Spring, no Telegram client
 * dependency — just string formatting that happens to target Telegram's quirks.
 *
 * <p><b>Severity prefix.</b> When a {@link Severity} is supplied, its emoji + label is
 * prepended to the bold title — 🟢 INFO / 🟡 WARN / 🔴 ERROR / ⚫ FATAL.
 *
 * <p><b>Field truncation.</b> Every text field passed in is hard-truncated to
 * {@value #MAX_FIELD_LENGTH} characters with a trailing "{@value #ELLIPSIS}" before any
 * other processing. This bounds the size of a single section even when the caller
 * passes in a multi-MB stack trace or a huge exception message.
 *
 * <p><b>Markdown escaping.</b> Plain-text fields (title, prose, key-value labels) are
 * escaped for Telegram's legacy "Markdown" mode — backslash-prefix on {@code _ * ` [}.
 * Code-block contents are NOT escaped because Telegram renders them literally; instead
 * the {@code ```} fence is broken if the content contains a triple-backtick (rare).
 *
 * <p><b>Message length.</b> Telegram caps each message at {@value #MAX_MESSAGE_LENGTH}
 * characters. {@link #buildChunks()} packs sections greedily into chunks of that size,
 * splitting on section boundaries; if a single section is itself larger than the
 * limit (after the per-field truncation, this shouldn't happen, but defensively...) it
 * is hard-truncated at the chunk boundary with the standard ellipsis.
 *
 * <p>Use:
 * <pre>{@code
 * List<String> chunks = TelegramMessageBuilder.builder()
 *     .severity(Severity.ERROR)
 *     .title("HTTP 500 — Unhandled exception")
 *     .kvBlock(Map.of("method", "POST", "path", "/service/x"))
 *     .codeBlock("stack", stackTraceString)
 *     .buildChunks();
 * for (String chunk : chunks) notifier.broadcastMarkdown(chunk);
 * }</pre>
 */
public final class TelegramMessageBuilder {

    public static final int MAX_FIELD_LENGTH = 500;
    public static final int MAX_MESSAGE_LENGTH = 4096;
    public static final String ELLIPSIS = "…";

    public enum Severity {
        INFO("🟢", "INFO"),
        WARN("🟡", "WARN"),
        ERROR("🔴", "ERROR"),
        FATAL("⚫", "FATAL");

        public final String emoji;
        public final String label;

        Severity(String emoji, String label) {
            this.emoji = emoji;
            this.label = label;
        }
    }

    private Severity severity;
    private String title;
    private final List<Section> sections = new ArrayList<>();

    private TelegramMessageBuilder() {}

    public static TelegramMessageBuilder builder() {
        return new TelegramMessageBuilder();
    }

    public TelegramMessageBuilder severity(Severity s) {
        this.severity = s;
        return this;
    }

    public TelegramMessageBuilder title(String t) {
        this.title = truncate(t);
        return this;
    }

    /**
     * Plain prose paragraph — escaped for Markdown safety. Use this for free-form
     * messages like "the connection failed because…" — anything where the caller
     * doesn't control the exact characters.
     */
    public TelegramMessageBuilder text(String body) {
        if (body == null || body.isEmpty()) return this;
        sections.add(new Section.Plain(escape(truncate(body))));
        return this;
    }

    /**
     * Bold section header above an escaped prose body.
     */
    public TelegramMessageBuilder section(String header, String body) {
        sections.add(new Section.Headed(
                escape(truncate(header)),
                body == null || body.isEmpty() ? null : escape(truncate(body))));
        return this;
    }

    /**
     * Aligned key-value table inside a fenced code block. Order is preserved; pass a
     * {@link LinkedHashMap} when ordering matters. Keys + values are NOT
     * markdown-escaped (code blocks render literally), only truncated.
     */
    public TelegramMessageBuilder kvBlock(Map<String, String> entries) {
        if (entries == null || entries.isEmpty()) return this;
        var truncated = new LinkedHashMap<String, String>();
        for (var e : entries.entrySet()) {
            truncated.put(truncate(e.getKey()), truncate(safeValue(e.getValue())));
        }
        sections.add(new Section.KvBlock(truncated));
        return this;
    }

    /**
     * Fenced code block with optional bold header. Content is NOT escaped (renders
     * literally). Use for stack traces or pre-formatted multi-line dumps.
     */
    public TelegramMessageBuilder codeBlock(String header, String content) {
        if (content == null || content.isEmpty()) return this;
        // Content can't safely contain a triple-backtick — break it up if it does.
        String safe = content.replace("```", "``` ");
        sections.add(new Section.CodeBlock(
                header == null ? null : escape(truncate(header)),
                truncate(safe)));
        return this;
    }

    /** Single chunk — convenience for callers who know the message will fit. */
    public String build() {
        var chunks = buildChunks();
        return chunks.isEmpty() ? "" : chunks.get(0);
    }

    /**
     * Pack sections into one or more 4096-char chunks. Greedy: each section is appended
     * to the current chunk if it fits, else a new chunk is started. Header (severity +
     * title) is prepended to the FIRST chunk only — subsequent chunks open with a
     * {@code (continued ...)} marker so the operator can tell they're a follow-up.
     */
    public List<String> buildChunks() {
        var chunks = new ArrayList<String>();
        String header = renderHeader();

        // Pre-render every section so we can accumulate sizes deterministically.
        var rendered = new ArrayList<String>();
        for (Section s : sections) rendered.add(s.render());

        var current = new StringBuilder(header == null ? "" : header);
        for (String s : rendered) {
            int separator = current.length() == 0 ? 0 : 1;     // for the joining "\n"
            int prospective = current.length() + separator + s.length();
            if (prospective > MAX_MESSAGE_LENGTH && current.length() > 0) {
                chunks.add(current.toString());
                current = new StringBuilder("(continued …)");
            }
            if (current.length() > 0) current.append("\n");
            current.append(s);
            // Defensive: a single section larger than MAX is hard-truncated.
            if (current.length() > MAX_MESSAGE_LENGTH) {
                String overflow = current.substring(0, MAX_MESSAGE_LENGTH - ELLIPSIS.length()) + ELLIPSIS;
                chunks.add(overflow);
                current = new StringBuilder();
            }
        }
        if (current.length() > 0) chunks.add(current.toString());
        return chunks;
    }

    private String renderHeader() {
        if (severity == null && (title == null || title.isEmpty())) return null;
        var sb = new StringBuilder();
        if (severity != null) {
            sb.append(severity.emoji).append(" ");
            sb.append("*").append(severity.label).append("*");
            if (title != null && !title.isEmpty()) sb.append(" — ");
        }
        if (title != null && !title.isEmpty()) {
            // Title is bold even without a severity prefix.
            sb.append("*").append(escape(title)).append("*");
        }
        return sb.toString();
    }

    // -------- helpers --------

    /** Backslash-escape Telegram "Markdown" mode special chars. Public for tests. */
    public static String escape(String s) {
        if (s == null) return "";
        var sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '_' || c == '*' || c == '`' || c == '[') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** Truncate to {@link #MAX_FIELD_LENGTH} with trailing ellipsis. Public for tests. */
    public static String truncate(String s) {
        if (s == null) return null;
        if (s.length() <= MAX_FIELD_LENGTH) return s;
        return s.substring(0, MAX_FIELD_LENGTH - ELLIPSIS.length()) + ELLIPSIS;
    }

    private static String safeValue(String v) {
        return v == null ? "" : v;
    }

    // -------- section types --------

    private sealed interface Section {
        String render();

        record Plain(String body) implements Section {
            @Override public String render() { return body; }
        }

        record Headed(String header, String body) implements Section {
            @Override public String render() {
                var sb = new StringBuilder("*").append(header).append("*");
                if (body != null) sb.append("\n").append(body);
                return sb.toString();
            }
        }

        record KvBlock(Map<String, String> entries) implements Section {
            @Override public String render() {
                int width = 0;
                for (String k : entries.keySet()) if (k.length() > width) width = k.length();
                var sb = new StringBuilder("```\n");
                for (var e : entries.entrySet()) {
                    sb.append(padRight(e.getKey(), width)).append(" : ")
                            .append(e.getValue()).append('\n');
                }
                // Trim the last newline so the closing fence sits on its own line cleanly.
                if (sb.charAt(sb.length() - 1) == '\n') sb.setLength(sb.length() - 1);
                sb.append("\n```");
                return sb.toString();
            }

            private static String padRight(String s, int width) {
                if (s.length() >= width) return s;
                return s + " ".repeat(width - s.length());
            }
        }

        record CodeBlock(String header, String content) implements Section {
            @Override public String render() {
                var sb = new StringBuilder();
                if (header != null) sb.append("*").append(header).append("*\n");
                sb.append("```\n").append(content);
                if (!content.endsWith("\n")) sb.append('\n');
                sb.append("```");
                return sb.toString();
            }
        }
    }
}
