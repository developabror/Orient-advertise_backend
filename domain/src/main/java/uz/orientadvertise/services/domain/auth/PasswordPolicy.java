package uz.orientadvertise.services.domain.auth;

import java.nio.charset.StandardCharsets;

/**
 * The one password rule for people (AUTH-08). Creating an account, changing a password and
 * resetting one all enforce it; account creation used to accept 6 characters while change and
 * reset required 8. The constants also feed the request DTOs' {@code @Size}.
 *
 * <p>The ceiling is bcrypt's: it reads at most 72 bytes, and the encoder rejects a longer password
 * outright. {@code @Size} counts characters, so the byte check here is what catches 37 Cyrillic
 * letters (74 bytes) — otherwise they would pass validation and fail inside the encoder.
 *
 * <p>Login does not apply it: a login must accept whatever an existing account was created with.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_BYTES = 72;
    public static final String LENGTH_MESSAGE =
            "Password must be at least " + MIN_LENGTH + " characters and at most " + MAX_BYTES
                    + " bytes (" + MAX_BYTES + " Latin letters; non-Latin letters take 2 bytes or more).";

    private PasswordPolicy() {
    }

    public static boolean hasValidLength(String password) {
        return password != null
                && password.length() >= MIN_LENGTH
                && password.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES;
    }
}
