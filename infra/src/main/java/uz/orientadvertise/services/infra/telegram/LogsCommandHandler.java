package uz.orientadvertise.services.infra.telegram;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uz.orientadvertise.services.common.telegram.TelegramMessageBuilder;
import uz.orientadvertise.services.common.telegram.TelegramMessageBuilder.Severity;
import uz.orientadvertise.services.infra.diagnostic.RetainedErrorBuffer;
import uz.orientadvertise.services.infra.diagnostic.RetainedErrorBuffer.RetainedError;

/**
 * Handles {@code /logs} — returns the most recent ERROR-level log events from the
 * in-memory ring buffer that {@link TelegramAppender} maintains. Lets an operator pull
 * the last few errors over Telegram without leaving the chat.
 *
 * <p><b>Source buffer.</b> Reads from {@link RetainedErrorBuffer}, which the
 * {@link TelegramAppender#append} path mirrors every WARN/ERROR event into. The buffer
 * is bounded at {@link RetainedErrorBuffer#CAPACITY} (100) entries and lives in static
 * memory only — JVM restart wipes it (no persistence layer, deliberately). This handler
 * filters that buffer down to ERROR-only via
 * {@link RetainedErrorBuffer#recentByLevel(String, int)}.
 *
 * <p><b>Argument parsing.</b> {@code /logs} alone returns the {@link #DEFAULT_LIMIT
 * default} most recent ERRORs. {@code /logs N} returns up to {@code N}, clamped to
 * {@code [1, MAX_LIMIT]}. Non-numeric or out-of-range arguments fall back to the default
 * with a one-line note in the response so the operator knows their input was ignored.
 *
 * <p><b>Empty-buffer handling.</b> The spec calls this out specifically: silence is
 * confusing — an operator can't tell whether the buffer is empty or the bot is broken.
 * When no ERRORs match, the response is a positive {@code "no recent errors"} message.
 *
 * <p><b>Format.</b> Each row: {@code yyyy-MM-dd HH:mm:ss ShortLogger: message [ExceptionClass]}.
 * Message is hard-truncated to 200 chars. Logger is the FQCN's last segment. Exception
 * class (if any) is shown in brackets at the end of the line. Markdown code-block keeps
 * the rendering monospace and avoids escaping concerns for log content (paths, stack
 * fragments, etc. that might otherwise collide with Markdown specials).
 *
 * <p><b>Bypass path.</b> Like {@code /state} and {@code /health}, the response goes via
 * {@code bot.send} directly — bypassing the outbound queue. Interactive command
 * responses can't tolerate the queue's 5-second per-send latency.
 *
 * <p><b>Authorization.</b> Inherited from {@link OrientTelegramBot#onUpdateReceived} —
 * the dispatcher is only invoked for authorized chat ids. No re-check.
 */
public class LogsCommandHandler implements TelegramCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(LogsCommandHandler.class);

    static final String COMMAND = "/logs";
    static final int DEFAULT_LIMIT = 10;
    /**
     * Hard cap on how many entries we'll return in one message. Telegram's per-message
     * limit is 4096 chars; with ~200-char message previews + 100-char log context per
     * row, ~30 entries comfortably fits while leaving room for the header. Capping at
     * the buffer capacity ({@code 100}) is unnecessary — by the time the operator asks
     * for that many ERRORs, individual rows would have to shrink for the reply to fit.
     */
    static final int MAX_LIMIT = 30;
    static final int MESSAGE_PREVIEW_CHARS = 200;
    private static final ZoneId UTC_PLUS_5 = ZoneId.of("Asia/Karachi");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String LEVEL_ERROR = "ERROR";

    private final OrientTelegramBot bot;

    public LogsCommandHandler(OrientTelegramBot bot) {
        this.bot = bot;
    }

    @Override
    public String name() {
        return COMMAND;
    }

    @Override
    public void handle(Long chatId, String[] args) {
        // Outer no-throw guard mirrors /state and /health — the spec demands a response
        // even when the underlying read fails (the buffer is in-memory, so the only
        // failure modes are the formatter or bot.send).
        try {
            ParsedLimit parsed = parseLimit(args);
            List<RetainedError> entries = RetainedErrorBuffer.recentByLevel(LEVEL_ERROR, parsed.limit());
            String payload = renderPayload(entries, parsed);
            bot.send(String.valueOf(chatId), payload, "Markdown");
        } catch (Throwable t) {
            log.warn("/logs unexpected failure: {}", t.toString(), t);
            try {
                bot.send(String.valueOf(chatId),
                        "⚠️ /logs failed unexpectedly — check application logs");
            } catch (Throwable ignored) {
                // bot.send already swallows TelegramApiException; only an unchecked from
                // the SendMessage builder lands here. Nothing more we can do.
            }
        }
    }

    /**
     * Parse the optional {@code N} argument. Result records both the resolved limit and
     * (when applicable) a one-line warning for the response so the operator knows their
     * input was rejected — we don't fail silently on bad input.
     */
    static ParsedLimit parseLimit(String[] args) {
        if (args == null || args.length == 0) {
            return new ParsedLimit(DEFAULT_LIMIT, null);
        }
        String raw = args[0].trim();
        int parsed;
        try {
            parsed = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return new ParsedLimit(DEFAULT_LIMIT,
                    "argument '" + raw + "' is not a number — using default " + DEFAULT_LIMIT);
        }
        if (parsed < 1) {
            return new ParsedLimit(DEFAULT_LIMIT,
                    "argument " + parsed + " < 1 — using default " + DEFAULT_LIMIT);
        }
        if (parsed > MAX_LIMIT) {
            return new ParsedLimit(MAX_LIMIT,
                    "argument " + parsed + " > " + MAX_LIMIT + " — clamped to " + MAX_LIMIT);
        }
        return new ParsedLimit(parsed, null);
    }

    /** Visible for tests. */
    String renderPayload(List<RetainedError> entries, ParsedLimit parsed) {
        if (entries == null || entries.isEmpty()) {
            // Empty-buffer "no recent errors" — explicitly a non-silent response per spec.
            // INFO severity (green) so the absence of errors reads as a positive signal.
            var builder = TelegramMessageBuilder.builder()
                    .severity(Severity.INFO)
                    .title("Recent error logs")
                    .text("no recent errors");
            if (parsed != null && parsed.warning() != null) {
                builder.text("_" + parsed.warning() + "_");
            }
            return builder.build();
        }

        var body = new StringBuilder();
        for (RetainedError e : entries) {
            body.append(formatLine(e)).append('\n');
        }
        // Trim the trailing newline so the closing fence sits on its own line cleanly.
        String content = body.toString().replaceAll("\\n+$", "");

        var builder = TelegramMessageBuilder.builder()
                .severity(Severity.ERROR)
                .title("Recent error logs (" + entries.size() + ")")
                .codeBlock(null, content);
        if (parsed != null && parsed.warning() != null) {
            builder.text("_" + parsed.warning() + "_");
        }
        return builder.build();
    }

    /**
     * Format a single line: {@code yyyy-MM-dd HH:mm:ss ShortLogger: message [ExceptionClass]}.
     * Public for tests so the rendering contract is locked at the unit level.
     */
    static String formatLine(RetainedError e) {
        String ts = ZonedDateTime.ofInstant(e.timestamp(), UTC_PLUS_5).format(TS);
        String shortLogger = shortLoggerName(e.logger());
        String preview = truncateMessage(e.message());
        String exClass = exceptionClass(e.exception());
        var sb = new StringBuilder()
                .append(ts).append(' ')
                .append(shortLogger).append(": ")
                .append(preview);
        if (exClass != null) {
            sb.append(" [").append(exClass).append(']');
        }
        return sb.toString();
    }

    /** Truncate to {@link #MESSAGE_PREVIEW_CHARS} with trailing ellipsis on overflow. */
    static String truncateMessage(String msg) {
        if (msg == null || msg.isEmpty()) return "(no message)";
        if (msg.length() <= MESSAGE_PREVIEW_CHARS) return msg;
        return msg.substring(0, MESSAGE_PREVIEW_CHARS) + "…";
    }

    /** Last segment of an FQCN — skips the package noise. */
    static String shortLoggerName(String fqcn) {
        if (fqcn == null || fqcn.isEmpty()) return "?";
        int dot = fqcn.lastIndexOf('.');
        if (dot < 0 || dot == fqcn.length() - 1) return fqcn;
        return fqcn.substring(dot + 1);
    }

    /**
     * Extract just the class name from RetainedError.exception(), which is stored as
     * {@code "FQCN: message"} (matching what the appender stashes). Returns null when
     * no exception was attached.
     */
    static String exceptionClass(String storedException) {
        if (storedException == null || storedException.isBlank()) return null;
        // Stored format from TelegramAppender.exceptionClassLine: "FQCN" or "FQCN: message".
        int colon = storedException.indexOf(':');
        String fqcn = colon < 0 ? storedException : storedException.substring(0, colon);
        // Just the simple class name keeps the line readable.
        int dot = fqcn.lastIndexOf('.');
        return dot < 0 ? fqcn.trim() : fqcn.substring(dot + 1).trim();
    }

    /** Parsed N argument plus an optional warning message for the response. */
    record ParsedLimit(int limit, String warning) {}
}
