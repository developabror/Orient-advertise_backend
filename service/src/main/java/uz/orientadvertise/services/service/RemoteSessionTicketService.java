package uz.orientadvertise.services.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import uz.orientadvertise.services.common.exception.IllegalConfigurationException;

/**
 * Mints and verifies the signed relay tickets.
 *
 * <pre>
 *   ticket  = base64url(payloadJson) + "." + base64url(HMAC_SHA256(secret, payloadJson))
 *   payload = {"sid":"rs_…","role":"agent"|"viewer","did":12,"exp":&lt;epochSeconds&gt;}
 * </pre>
 *
 * <p><b>Stateless on purpose.</b> The relay validates the signature and {@code exp} offline, so
 * it keeps working while this service is redeploying — no callback, no shared session store.
 *
 * <p><b>Two tickets per session, one per role.</b> A leaked <i>viewer</i> ticket must not be
 * usable to impersonate the <i>agent</i>, so {@link #verify(String, Role)} pins the expected
 * role and rejects a role mismatch exactly as hard as a bad signature.
 *
 * <p>The secret is read from {@code app.remote.relay.signing-secret}
 * ({@code REMOTE_RELAY_SIGNING_SECRET}). It is never logged, never returned in a DTO and never
 * included in an exception message; a missing or short secret fails the application at startup
 * when remote control is enabled, rather than silently minting forgeable tickets.
 */
@Service
@EnableConfigurationProperties(RemoteProperties.class)
public class RemoteSessionTicketService {

    /** HMAC-SHA256 needs at least 256 bits of key material to be worth anything. */
    public static final int MIN_SECRET_BYTES = 32;

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64_DEC = Base64.getUrlDecoder();

    /** The relay's two connect roles. The wire value is the lowercase {@link #wire()}. */
    public enum Role {
        AGENT, VIEWER;

        public String wire() { return name().toLowerCase(java.util.Locale.ROOT); }

        static Role fromWire(String value) {
            for (var role : values()) {
                if (role.wire().equals(value)) return role;
            }
            return null;
        }
    }

    /** Verified ticket claims. Carries no secret material. */
    public record TicketClaims(String sessionKey, Role role, Long deviceId, Instant expiresAt) {}

    /** Ticket is absent, malformed, tampered with, expired, or issued for the wrong role. */
    public static class InvalidTicketException extends RuntimeException {
        public InvalidTicketException(String message) { super(message); }
    }

    private final RemoteProperties properties;
    private final byte[] secret;

    public RemoteSessionTicketService(RemoteProperties properties) {
        this.properties = properties;
        String configured = properties.getRelay().getSigningSecret();
        byte[] bytes = configured == null ? new byte[0] : configured.getBytes(StandardCharsets.UTF_8);
        if (properties.isEnabled() && bytes.length < MIN_SECRET_BYTES) {
            // Fail fast rather than boot a remote-control-enabled node that mints forgeable
            // tickets. The message names the property, never the value.
            throw new IllegalConfigurationException(
                    "app.remote.relay.signing-secret must be set and at least " + MIN_SECRET_BYTES
                            + " bytes long when app.remote.enabled=true "
                            + "(set REMOTE_RELAY_SIGNING_SECRET)");
        }
        this.secret = bytes;
    }

    /**
     * Mint a ticket for one {@code (session, role)} pair.
     *
     * @param expiresAt the session's own {@code expires_at}; the ticket's {@code exp} is never
     *                  allowed to outlive it.
     */
    public String mint(String sessionKey, Role role, Long deviceId, Instant expiresAt) {
        if (sessionKey == null || sessionKey.isBlank()) {
            throw new IllegalArgumentException("sessionKey is required to mint a ticket");
        }
        if (role == null || deviceId == null || expiresAt == null) {
            throw new IllegalArgumentException("role, deviceId and expiresAt are required to mint a ticket");
        }
        requireUsableSecret();
        var payload = """
                {"sid":"%s","role":"%s","did":%d,"exp":%d}"""
                .formatted(sessionKey, role.wire(), deviceId, expiresAt.getEpochSecond());
        var payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        return B64.encodeToString(payloadBytes) + "." + B64.encodeToString(sign(payloadBytes));
    }

    /**
     * Verify signature, expiry and role. Every failure mode raises the same
     * {@link InvalidTicketException} type so a caller cannot use the exception to distinguish
     * "bad signature" from "unknown session".
     */
    public TicketClaims verify(String ticket, Role expectedRole) {
        var claims = verify(ticket);
        if (expectedRole != null && claims.role() != expectedRole) {
            throw new InvalidTicketException("Ticket role mismatch");
        }
        return claims;
    }

    /** Verify signature and expiry without pinning a role. */
    public TicketClaims verify(String ticket) {
        requireUsableSecret();
        if (ticket == null || ticket.isBlank()) {
            throw new InvalidTicketException("Ticket is missing");
        }
        int dot = ticket.indexOf('.');
        if (dot <= 0 || dot != ticket.lastIndexOf('.') || dot == ticket.length() - 1) {
            throw new InvalidTicketException("Ticket is malformed");
        }

        byte[] payloadBytes;
        byte[] presentedSignature;
        try {
            payloadBytes = B64_DEC.decode(ticket.substring(0, dot));
            presentedSignature = B64_DEC.decode(ticket.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            throw new InvalidTicketException("Ticket is malformed");
        }

        // Constant-time — a byte-by-byte early exit would leak the signature through timing.
        if (!MessageDigest.isEqual(sign(payloadBytes), presentedSignature)) {
            throw new InvalidTicketException("Ticket signature is invalid");
        }

        var claims = parse(new String(payloadBytes, StandardCharsets.UTF_8));
        if (!claims.expiresAt().isAfter(Instant.now())) {
            throw new InvalidTicketException("Ticket has expired");
        }
        return claims;
    }

    private void requireUsableSecret() {
        if (secret.length < MIN_SECRET_BYTES) {
            // Only reachable when the feature is disabled (the constructor guards the enabled
            // case). Minting or verifying with a weak key is worse than refusing.
            throw new IllegalConfigurationException(
                    "app.remote.relay.signing-secret is not configured "
                            + "(set REMOTE_RELAY_SIGNING_SECRET, at least "
                            + MIN_SECRET_BYTES + " bytes)");
        }
    }

    private byte[] sign(byte[] payload) {
        try {
            var mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return mac.doFinal(payload);
        } catch (java.security.GeneralSecurityException e) {
            // HmacSHA256 is a mandatory JDK algorithm — absence is a provisioning fault, not a
            // caller error, so this must surface as a 500, never a 4xx.
            throw new IllegalConfigurationException("HMAC-SHA256 is unavailable in this JVM", e);
        }
    }

    /**
     * Hand-parse the four known claims. Deliberately not a general JSON binder: the payload is
     * one we minted ourselves and already authenticated, and a strict parser keeps an attacker
     * from smuggling extra structure past a lenient one.
     */
    private static TicketClaims parse(String payload) {
        try {
            var sid = extractString(payload, "sid");
            var role = Role.fromWire(extractString(payload, "role"));
            var did = Long.parseLong(extractNumber(payload, "did"));
            var exp = Long.parseLong(extractNumber(payload, "exp"));
            if (sid == null || sid.isBlank() || role == null) {
                throw new InvalidTicketException("Ticket claims are incomplete");
            }
            return new TicketClaims(sid, role, did, Instant.ofEpochSecond(exp));
        } catch (InvalidTicketException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new InvalidTicketException("Ticket claims are unreadable");
        }
    }

    private static String extractString(String json, String key) {
        var marker = "\"" + key + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) return null;
        start += marker.length();
        int end = json.indexOf('"', start);
        return end < 0 ? null : json.substring(start, end);
    }

    private static String extractNumber(String json, String key) {
        var marker = "\"" + key + "\":";
        int start = json.indexOf(marker);
        if (start < 0) throw new InvalidTicketException("Ticket claims are incomplete");
        start += marker.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        if (end == start) throw new InvalidTicketException("Ticket claims are incomplete");
        return json.substring(start, end);
    }

    /** The relay URL the given role should dial. */
    public String relayUrlFor(Role role) {
        return role == Role.AGENT
                ? properties.getRelay().getAgentUrl()
                : properties.getRelay().getViewerUrl();
    }
}
