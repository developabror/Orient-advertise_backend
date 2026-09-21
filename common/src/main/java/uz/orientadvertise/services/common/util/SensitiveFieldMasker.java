package uz.orientadvertise.services.common.util;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decides which JSON keys carry credentials, for everything that echoes request/response data
 * somewhere durable: {@code AuditFilter} (via {@code AuditBodySanitizer}) and the validation
 * {@code rejectedValue} in {@code GlobalExceptionHandler}.
 *
 * <p>Matching is by <b>name pattern</b>, case-insensitive — not an exact-name list. The exact list
 * this replaced missed {@code currentPassword}/{@code newPassword}/{@code confirmPassword} and
 * {@code rawKey}, so every password change and every minted API key sat in {@code audit_log} in
 * plaintext (AUTH-06). A key is sensitive when it:
 * <ul>
 *   <li>contains {@code password}, {@code passphrase}, {@code passwd}, {@code secret}, {@code token},
 *       {@code ticket}, {@code authorization}, {@code credential}, {@code cookie}, {@code signature},
 *       {@code hmac} or {@code bearer}; or</li>
 *   <li>ends with {@code key} ({@code rawKey}, {@code apiKey}, {@code secretKey}, {@code accessKey};
 *       masking e.g. {@code storageKey} too is accepted collateral), {@code pin}, {@code pwd},
 *       {@code otp} or {@code jwt}; or</li>
 *   <li>is exactly {@code ssn}, {@code cvv}, {@code credit_card}/{@code creditCard} or {@code pinCode}.</li>
 * </ul>
 * {@code keyPrefix} and {@code apiKeyId} stay readable — they identify a key without being one.
 */
public final class SensitiveFieldMasker {

    public static final String MASKED_VALUE = "***REDACTED***";

    private static final List<String> SENSITIVE_PARTS = List.of("password", "passphrase", "passwd", "secret",
            "token", "ticket", "authorization", "credential", "cookie", "signature", "hmac", "bearer");
    private static final List<String> SENSITIVE_SUFFIXES = List.of("key", "pin", "pwd", "otp", "jwt");
    private static final Set<String> SENSITIVE_EXACT = Set.of("ssn", "cvv", "credit_card", "creditcard", "pincode");

    private SensitiveFieldMasker() {
    }

    public static boolean isSensitiveKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String k = key.toLowerCase(Locale.ROOT);
        if (SENSITIVE_EXACT.contains(k)) {
            return true;
        }
        for (String suffix : SENSITIVE_SUFFIXES) {
            if (k.endsWith(suffix)) {
                return true;
            }
        }
        for (String part : SENSITIVE_PARTS) {
            if (k.contains(part)) {
                return true;
            }
        }
        return false;
    }
}
