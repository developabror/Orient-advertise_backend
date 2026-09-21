package uz.orientadvertise.services.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import uz.orientadvertise.services.Application;
import uz.orientadvertise.services.domain.content.DevicePushChannel;
import uz.orientadvertise.services.service.RemoteProperties;
import uz.orientadvertise.services.service.RemoteSessionExpirationJob;
import uz.orientadvertise.services.service.RemoteSessionService;
import uz.orientadvertise.services.service.RemoteSessionTicketService;
import uz.orientadvertise.services.service.RemoteSessionTicketService.Role;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boots the whole application with remote control <b>switched ON</b>.
 *
 * <p>Every other test either mocks these beans or runs with the feature dark, so this is the only
 * place that proves the enabled path actually wires: the {@code app.remote.*} binding, the
 * ticket service's startup secret check passing, the scheduled janitor being registered, and —
 * the one that a slice test structurally cannot catch — the {@link DevicePushChannel} port
 * resolving to the WebSocket handler across the {@code service} → {@code api} module boundary.
 * The service module has no compile dependency on {@code api}, so a missing implementation would
 * only surface as a context-startup failure in the real app.
 */
@SpringBootTest(classes = Application.class)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.remote.enabled=true",
        "app.remote.session-ttl=PT10M",
        "app.remote.relay.agent-url=wss://relay.test/agent",
        "app.remote.relay.viewer-url=wss://relay.test/viewer",
        // Exactly 32 bytes — the minimum the fail-fast guard accepts.
        "app.remote.relay.signing-secret=0123456789abcdef0123456789abcdef"
})
class RemoteControlWiringIntegrationTest {

    @Autowired
    private RemoteProperties properties;

    @Autowired
    private RemoteSessionTicketService ticketService;

    @Autowired
    private RemoteSessionService sessionService;

    @Autowired
    private RemoteSessionExpirationJob expirationJob;

    @Autowired
    private DevicePushChannel devicePushChannel;

    @Test
    void contextStarts_withRemoteControlEnabled() {
        assertNotNull(sessionService);
        assertNotNull(expirationJob);
        assertTrue(properties.isEnabled());
    }

    @Test
    void relayPropertiesBindFromTheAppRemotePrefix() {
        assertEquals(java.time.Duration.ofMinutes(10), properties.getSessionTtl());
        assertEquals("wss://relay.test/agent", properties.getRelay().getAgentUrl());
        assertEquals("wss://relay.test/viewer", properties.getRelay().getViewerUrl());
        assertEquals(1280, properties.getMaxWidth());
        assertEquals(15, properties.getMaxFps());
        assertEquals(2_000_000, properties.getBitRate());
    }

    @Test
    void devicePushChannel_resolvesToTheWebSocketHandler() {
        // The seam that keeps `service` from importing `api`. If this ever resolves to something
        // else — or to nothing — remote sessions silently stop being pushed live.
        assertInstanceOf(uz.orientadvertise.services.api.ws.DeviceWebSocketHandler.class, devicePushChannel);
        assertEquals(false, devicePushChannel.isConnected(-1L), "no device is connected in a test context");
    }

    @Test
    void ticketService_mintsAndVerifiesAgainstTheConfiguredSecret() {
        var expiry = java.time.Instant.now().plusSeconds(600);
        var ticket = ticketService.mint("rs_wiring", Role.AGENT, 1L, expiry);

        var claims = ticketService.verify(ticket, Role.AGENT);
        assertEquals("rs_wiring", claims.sessionKey());
        assertEquals(Role.AGENT, claims.role());
        assertEquals("wss://relay.test/agent", ticketService.relayUrlFor(Role.AGENT));
    }
}
