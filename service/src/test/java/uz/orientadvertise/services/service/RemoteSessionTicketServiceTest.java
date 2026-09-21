package uz.orientadvertise.services.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import uz.orientadvertise.services.common.exception.IllegalConfigurationException;
import uz.orientadvertise.services.service.RemoteSessionTicketService.InvalidTicketException;
import uz.orientadvertise.services.service.RemoteSessionTicketService.Role;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteSessionTicketServiceTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";   // exactly 32 bytes
    private static final String SESSION = "rs_7f3a91c4b8e24d5a";

    private static RemoteProperties props(boolean enabled, String secret) {
        var p = new RemoteProperties();
        p.setEnabled(enabled);
        p.getRelay().setSigningSecret(secret);
        p.getRelay().setAgentUrl("wss://relay.test/agent");
        p.getRelay().setViewerUrl("wss://relay.test/viewer");
        return p;
    }

    private static RemoteSessionTicketService enabledService() {
        return new RemoteSessionTicketService(props(true, SECRET));
    }

    private static Instant soon() {
        return Instant.now().plus(30, ChronoUnit.MINUTES);
    }

    // ----- positive -----

    @Test
    void mintThenVerify_roundTripsEveryClaim() {
        var service = enabledService();
        var expiry = soon();

        var ticket = service.mint(SESSION, Role.VIEWER, 12L, expiry);
        var claims = service.verify(ticket, Role.VIEWER);

        assertEquals(SESSION, claims.sessionKey());
        assertEquals(Role.VIEWER, claims.role());
        assertEquals(12L, claims.deviceId());
        // exp is epoch SECONDS on the wire, so compare at second precision.
        assertEquals(expiry.getEpochSecond(), claims.expiresAt().getEpochSecond());
    }

    @Test
    void ticket_isTwoBase64UrlSegmentsSeparatedByADot() {
        var ticket = enabledService().mint(SESSION, Role.AGENT, 12L, soon());

        var parts = ticket.split("\\.");
        assertEquals(2, parts.length);
        assertTrue(parts[0].matches("[A-Za-z0-9_-]+"), "payload must be base64url without padding");
        assertTrue(parts[1].matches("[A-Za-z0-9_-]+"), "signature must be base64url without padding");
    }

    @Test
    void agentAndViewerTickets_forTheSameSession_areDifferent() {
        var service = enabledService();
        var expiry = soon();

        assertNotEquals(service.mint(SESSION, Role.AGENT, 12L, expiry),
                service.mint(SESSION, Role.VIEWER, 12L, expiry));
    }

    @Test
    void verifyWithoutExpectedRole_acceptsEitherRole() {
        var service = enabledService();

        assertEquals(Role.AGENT, service.verify(service.mint(SESSION, Role.AGENT, 12L, soon())).role());
        assertEquals(Role.VIEWER, service.verify(service.mint(SESSION, Role.VIEWER, 12L, soon())).role());
    }

    @Test
    void relayUrlFor_returnsTheRoleSpecificUrl() {
        var service = enabledService();

        assertEquals("wss://relay.test/agent", service.relayUrlFor(Role.AGENT));
        assertEquals("wss://relay.test/viewer", service.relayUrlFor(Role.VIEWER));
    }

    // ----- negative -----

    @Test
    void agentTicket_rejectedWhenViewerExpected() {
        // THE point of two tickets: a leaked viewer ticket must never be usable as the agent,
        // and vice versa. Role mismatch fails exactly as hard as a bad signature.
        var service = enabledService();
        var agentTicket = service.mint(SESSION, Role.AGENT, 12L, soon());

        assertThrows(InvalidTicketException.class, () -> service.verify(agentTicket, Role.VIEWER));
    }

    @Test
    void viewerTicket_rejectedWhenAgentExpected() {
        var service = enabledService();
        var viewerTicket = service.mint(SESSION, Role.VIEWER, 12L, soon());

        assertThrows(InvalidTicketException.class, () -> service.verify(viewerTicket, Role.AGENT));
    }

    @Test
    void tamperedPayload_isRejected() {
        var service = enabledService();
        var ticket = service.mint(SESSION, Role.VIEWER, 12L, soon());

        // Re-encode the payload with a different device id, keep the original signature.
        var forgedPayload = """
                {"sid":"%s","role":"viewer","did":99,"exp":%d}"""
                .formatted(SESSION, soon().getEpochSecond());
        var forged = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(forgedPayload.getBytes(StandardCharsets.UTF_8))
                + ticket.substring(ticket.indexOf('.'));

        assertThrows(InvalidTicketException.class, () -> service.verify(forged, Role.VIEWER));
    }

    @Test
    void tamperedSignature_isRejected() {
        var service = enabledService();
        var ticket = service.mint(SESSION, Role.VIEWER, 12L, soon());
        int dot = ticket.indexOf('.');
        var sig = ticket.substring(dot + 1);
        var flipped = (sig.charAt(0) == 'A' ? 'B' : 'A') + sig.substring(1);

        var forged = ticket.substring(0, dot + 1) + flipped;
        assertThrows(InvalidTicketException.class, () -> service.verify(forged, Role.VIEWER));
    }

    @Test
    void ticketSignedWithADifferentSecret_isRejected() {
        var minted = new RemoteSessionTicketService(props(true, SECRET))
                .mint(SESSION, Role.VIEWER, 12L, soon());
        var otherService = new RemoteSessionTicketService(
                props(true, "ffffffffffffffffffffffffffffffff"));

        assertThrows(InvalidTicketException.class, () -> otherService.verify(minted, Role.VIEWER));
    }

    @Test
    void expiredTicket_isRejected() {
        var service = enabledService();
        var ticket = service.mint(SESSION, Role.VIEWER, 12L, Instant.now().minusSeconds(1));

        var e = assertThrows(InvalidTicketException.class, () -> service.verify(ticket, Role.VIEWER));
        assertTrue(e.getMessage().toLowerCase(java.util.Locale.ROOT).contains("expired"));
    }

    @Test
    void malformedTickets_areRejected() {
        var service = enabledService();

        assertThrows(InvalidTicketException.class, () -> service.verify(null, Role.VIEWER));
        assertThrows(InvalidTicketException.class, () -> service.verify("", Role.VIEWER));
        assertThrows(InvalidTicketException.class, () -> service.verify("no-dot", Role.VIEWER));
        assertThrows(InvalidTicketException.class, () -> service.verify(".onlysig", Role.VIEWER));
        assertThrows(InvalidTicketException.class, () -> service.verify("payload.", Role.VIEWER));
        assertThrows(InvalidTicketException.class, () -> service.verify("a.b.c", Role.VIEWER));
        assertThrows(InvalidTicketException.class, () -> service.verify("!!!.???", Role.VIEWER));
    }

    @Test
    void mint_rejectsMissingArguments() {
        var service = enabledService();

        assertThrows(IllegalArgumentException.class, () -> service.mint(null, Role.VIEWER, 12L, soon()));
        assertThrows(IllegalArgumentException.class, () -> service.mint("  ", Role.VIEWER, 12L, soon()));
        assertThrows(IllegalArgumentException.class, () -> service.mint(SESSION, null, 12L, soon()));
        assertThrows(IllegalArgumentException.class, () -> service.mint(SESSION, Role.VIEWER, null, soon()));
        assertThrows(IllegalArgumentException.class, () -> service.mint(SESSION, Role.VIEWER, 12L, null));
    }

    // ----- startup fail-fast -----

    @Test
    void enabledWithShortSecret_failsAtConstruction() {
        // 31 bytes — one short of HMAC-SHA256's 256-bit minimum.
        var e = assertThrows(IllegalConfigurationException.class,
                () -> new RemoteSessionTicketService(props(true, "0123456789abcdef0123456789abcde")));
        assertTrue(e.getMessage().contains("app.remote.relay.signing-secret"));
        assertTrue(e.getMessage().contains("REMOTE_RELAY_SIGNING_SECRET"));
    }

    @Test
    void enabledWithMissingSecret_failsAtConstruction() {
        assertThrows(IllegalConfigurationException.class,
                () -> new RemoteSessionTicketService(props(true, null)));
        assertThrows(IllegalConfigurationException.class,
                () -> new RemoteSessionTicketService(props(true, "")));
    }

    @Test
    void failFastMessage_neverContainsTheSecretValue() {
        var secret = "shhh-this-must-never-be-logged";   // 30 bytes: short enough to trip the guard
        var e = assertThrows(IllegalConfigurationException.class,
                () -> new RemoteSessionTicketService(props(true, secret)));

        assertTrue(!e.getMessage().contains(secret), "the guard must name the property, not the value");
    }

    @Test
    void disabledWithNoSecret_constructsButRefusesToMintOrVerify() {
        // Ship-dark posture: a disabled deployment boots fine with no secret configured, but it
        // must never fall back to minting forgeable tickets.
        var service = new RemoteSessionTicketService(props(false, null));

        assertThrows(IllegalConfigurationException.class,
                () -> service.mint(SESSION, Role.VIEWER, 12L, soon()));
        assertThrows(IllegalConfigurationException.class, () -> service.verify("a.b", Role.VIEWER));
    }

    @Test
    void relayProperties_toStringRedactsTheSecret() {
        var relay = props(true, SECRET).getRelay();

        var rendered = relay.toString();
        assertTrue(!rendered.contains(SECRET), "toString must never leak the secret");
        assertTrue(rendered.contains("<redacted>"));
    }
}
