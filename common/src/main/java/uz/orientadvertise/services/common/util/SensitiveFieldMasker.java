package uz.orientadvertise.services.common.util;

import java.util.Set;
import java.util.regex.Pattern;

public final class SensitiveFieldMasker {

    private static final Set<String> SENSITIVE_FIELDS = Set.of(
            "password", "secret", "token", "accessToken", "refreshToken",
            "secretKey", "accessKey", "authorization", "credit_card",
            "creditCard", "ssn", "cvv", "pin"
    );

    private static final String MASKED_VALUE = "***REDACTED***";

    private SensitiveFieldMasker() {
    }

    public static String mask(String json) {
        if (json == null || json.isBlank()) {
            return json;
        }
        var result = json;
        for (var field : SENSITIVE_FIELDS) {
            var pattern = Pattern.compile(
                    "(\"" + Pattern.quote(field) + "\"\\s*:\\s*)\"[^\"]*\"",
                    Pattern.CASE_INSENSITIVE
            );
            result = pattern.matcher(result).replaceAll("$1\"" + MASKED_VALUE + "\"");
        }
        return result;
    }
}
