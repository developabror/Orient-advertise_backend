package uz.orientadvertise.services.api.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The one place a client IP is taken from a request — the key for every per-IP rate limit and
 * the device's {@code last_known_ip}.
 *
 * <p>Deliberately just {@link HttpServletRequest#getRemoteAddr()}. With
 * {@code server.forward-headers-strategy: native} Tomcat's {@code RemoteIpValve} has already
 * resolved forwarding before any filter runs: it honours {@code X-Forwarded-For} only when the
 * direct peer is a trusted proxy ({@code server.tomcat.remoteip.internal-proxies}: loopback,
 * RFC 1918, link-local, 100.64/10 and IPv6 local ranges by default, which covers Caddy → Docker),
 * and walks the header right to left, so an entry a client prepends is ignored. Reading the header here again would undo that (AUTH-04):
 * the old code trusted the first entry, i.e. whatever the client wrote.
 */
public final class ClientIp {

    private ClientIp() {
    }

    public static String of(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
