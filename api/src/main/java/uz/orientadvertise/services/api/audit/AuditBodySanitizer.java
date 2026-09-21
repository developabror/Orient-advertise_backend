package uz.orientadvertise.services.api.audit;

import java.io.IOException;
import java.util.ArrayList;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uz.orientadvertise.services.common.util.SensitiveFieldMasker;

/**
 * Turns a captured HTTP body into what {@code audit_log} may store (AUTH-06): parse it as JSON,
 * replace the value of every sensitive key ({@link SensitiveFieldMasker#isSensitiveKey}) at any
 * depth and of any type, serialize, and only THEN truncate — so a secret straddling the size limit
 * can never be half-stored, which is what truncating first and masking with a regex allowed.
 *
 * <p>Fails closed: anything that doesn't parse as JSON (form posts, a malformed body) is replaced by
 * {@link #NON_JSON} rather than stored raw. Logged at DEBUG only — bodies come from unauthenticated
 * callers too, and WARN is forwarded to Telegram.
 */
final class AuditBodySanitizer {

    static final int MAX_CHARS = 10_000;
    static final String NON_JSON = "[omitted: non-JSON body]";
    static final String TRUNCATED_SUFFIX = "...[truncated]";

    private static final Logger log = LoggerFactory.getLogger(AuditBodySanitizer.class);
    // Exact decimals: the default double parsing would rewrite large/precise numbers in the audit trail.
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private AuditBodySanitizer() {
    }

    /** {@code content} is the raw body; Jackson detects its (UTF-8) encoding itself. */
    static String sanitize(byte[] content) {
        if (content == null || content.length == 0) {
            return null;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(content);
        } catch (IOException e) {
            log.debug("Audit body is not JSON — omitted: {}", e.getMessage());
            return NON_JSON;
        }
        if (root == null || root.isMissingNode()) {
            return null;
        }
        mask(root);
        return truncate(root.toString());
    }

    private static void mask(JsonNode node) {
        if (node instanceof ObjectNode object) {
            var names = new ArrayList<String>();
            object.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                if (SensitiveFieldMasker.isSensitiveKey(name)) {
                    object.put(name, SensitiveFieldMasker.MASKED_VALUE);
                } else {
                    mask(object.get(name));
                }
            }
        } else if (node.isArray()) {
            node.forEach(AuditBodySanitizer::mask);
        }
    }

    static String truncate(String json) {
        if (json.length() <= MAX_CHARS) {
            return json;
        }
        int end = MAX_CHARS;
        if (Character.isHighSurrogate(json.charAt(end - 1))) {
            end--;
        }
        return json.substring(0, end) + TRUNCATED_SUFFIX;
    }
}
