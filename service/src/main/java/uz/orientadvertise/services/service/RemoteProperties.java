package uz.orientadvertise.services.service;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Remote view/control configuration, bound from {@code app.remote.*}.
 *
 * <p>Ships <b>dark</b>: {@link #enabled} defaults to {@code false}, which makes
 * {@code POST /api/devices/{id}/remote} return 503 and keeps {@code desiredRemoteSession} off
 * the heartbeat entirely. Enable per environment once a relay is deployed.
 *
 * <p>{@code relay.signingSecret} is the shared HMAC secret between this service and the relay.
 * It has <b>no in-source default</b> — it comes from {@code REMOTE_RELAY_SIGNING_SECRET} — and
 * {@code RemoteSessionTicketService} fails fast at startup if it is missing or under 32 bytes
 * while the feature is on. It is never logged and never appears in a DTO.
 */
@ConfigurationProperties(prefix = "app.remote")
public class RemoteProperties {

    /** Feature flag. Off ⇒ start returns 503 and the heartbeat never advertises a session. */
    private boolean enabled = false;

    /** Hard ceiling on a session's life. The DEVICE enforces this on its own clock. */
    private Duration sessionTtl = Duration.ofMinutes(30);

    /**
     * Encoder hints handed to the device in the START push. {@code maxWidth} is further
     * clamped down to the device's own reported {@code remote_max_width} when it has reported
     * one — capability is reported, not assumed.
     */
    private int maxWidth = 1280;
    private int maxFps = 15;
    private int bitRate = 2_000_000;

    private final Relay relay = new Relay();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Duration getSessionTtl() { return sessionTtl; }
    public void setSessionTtl(Duration sessionTtl) { this.sessionTtl = sessionTtl; }

    public int getMaxWidth() { return maxWidth; }
    public void setMaxWidth(int maxWidth) { this.maxWidth = maxWidth; }

    public int getMaxFps() { return maxFps; }
    public void setMaxFps(int maxFps) { this.maxFps = maxFps; }

    public int getBitRate() { return bitRate; }
    public void setBitRate(int bitRate) { this.bitRate = bitRate; }

    public Relay getRelay() { return relay; }

    public static class Relay {

        /** WebSocket URL the DEVICE dials, e.g. {@code wss://relay.example.uz/agent}. */
        private String agentUrl;

        /** WebSocket URL the operator BROWSER dials, e.g. {@code wss://relay.example.uz/viewer}. */
        private String viewerUrl;

        /** Shared HMAC secret. Env-only, ≥32 bytes, never logged, never returned. */
        private String signingSecret;

        public String getAgentUrl() { return agentUrl; }
        public void setAgentUrl(String agentUrl) { this.agentUrl = agentUrl; }

        public String getViewerUrl() { return viewerUrl; }
        public void setViewerUrl(String viewerUrl) { this.viewerUrl = viewerUrl; }

        public String getSigningSecret() { return signingSecret; }
        public void setSigningSecret(String signingSecret) { this.signingSecret = signingSecret; }

        /**
         * Never let the secret reach a log line, an exception message or an actuator dump
         * through the properties object's own {@code toString()}.
         */
        @Override
        public String toString() {
            return "Relay{agentUrl=%s, viewerUrl=%s, signingSecret=%s}".formatted(
                    agentUrl, viewerUrl, signingSecret == null || signingSecret.isBlank()
                            ? "<unset>" : "<redacted>");
        }
    }
}
