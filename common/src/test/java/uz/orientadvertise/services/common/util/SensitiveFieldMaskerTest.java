package uz.orientadvertise.services.common.util;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensitiveFieldMaskerTest {

    @ParameterizedTest
    @ValueSource(strings = {
            // the exact list this pattern replaced
            "password", "secret", "token", "accessToken", "refreshToken", "secretKey", "accessKey",
            "authorization", "credit_card", "creditCard", "ssn", "cvv", "pin",
            "ticket", "viewerTicket", "agentTicket", "deviceToken",
            // AUTH-06: what the exact list missed
            "currentPassword", "newPassword", "confirmPassword", "rawKey", "apiKey",
            // other credential spellings (review)
            "passphrase", "passwd", "pwd", "credentials", "otp", "jwt", "setCookie", "signature", "hmac",
            "bearer", "pinCode", "newPin",
            // case-insensitive
            "PASSWORD", "NewPassword", "RAWKEY", "Authorization"})
    void credentialKeys_areSensitive(String key) {
        assertTrue(SensitiveFieldMasker.isSensitiveKey(key), key);
    }

    @ParameterizedTest
    @ValueSource(strings = {"username", "email", "serialNumber", "deviceId", "keyPrefix", "apiKeyId",
            "relayUrl", "expiresAt", "status", "name", "pinned", "prefix"})
    void identifyingButNonSecretKeys_stayReadable(String key) {
        assertFalse(SensitiveFieldMasker.isSensitiveKey(key), key);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    void blankKeys_areNotSensitive(String key) {
        assertFalse(SensitiveFieldMasker.isSensitiveKey(key));
        assertFalse(SensitiveFieldMasker.isSensitiveKey(null));
    }
}
