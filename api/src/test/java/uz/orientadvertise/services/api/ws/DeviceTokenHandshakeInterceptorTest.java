package uz.orientadvertise.services.api.ws;

import java.util.HashMap;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.WebSocketHandler;
import uz.orientadvertise.services.api.security.DeviceTokenAuthFilter;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.repository.DeviceRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeviceTokenHandshakeInterceptorTest {

    private DeviceRepository deviceRepository;
    private DeviceTokenHandshakeInterceptor interceptor;
    private final WebSocketHandler handler = mock(WebSocketHandler.class);

    @BeforeEach
    void setUp() {
        deviceRepository = mock(DeviceRepository.class);
        interceptor = new DeviceTokenHandshakeInterceptor(deviceRepository);
    }

    @Test
    void missingToken_returns401_andFalse() {
        var res = new MockHttpServletResponse();
        boolean ok = interceptor.beforeHandshake(req("/ws/devices/7", null, null),
                new ServletServerHttpResponse(res), handler, new HashMap<>());

        assertFalse(ok);
        assertEquals(HttpStatus.UNAUTHORIZED.value(), res.getStatus());
    }

    @Test
    void unknownToken_returns401_andFalse() {
        when(deviceRepository.findByDeviceTokenAndDeletedAtIsNull("dtk_bad")).thenReturn(Optional.empty());
        var res = new MockHttpServletResponse();

        boolean ok = interceptor.beforeHandshake(req("/ws/devices/7", "dtk_bad", null),
                new ServletServerHttpResponse(res), handler, new HashMap<>());

        assertFalse(ok);
        assertEquals(HttpStatus.UNAUTHORIZED.value(), res.getStatus());
    }

    @Test
    void tokenForDifferentDevice_returns403_andFalse() {
        var device = mock(Device.class);
        when(device.getId()).thenReturn(8L);
        when(deviceRepository.findByDeviceTokenAndDeletedAtIsNull("dtk_8")).thenReturn(Optional.of(device));
        var res = new MockHttpServletResponse();

        // Token belongs to device 8 but the handshake path is device 7 → 403.
        boolean ok = interceptor.beforeHandshake(req("/ws/devices/7", "dtk_8", null),
                new ServletServerHttpResponse(res), handler, new HashMap<>());

        assertFalse(ok);
        assertEquals(HttpStatus.FORBIDDEN.value(), res.getStatus());
    }

    @Test
    void validMatchingToken_returnsTrue_andStoresDeviceId() {
        var device = mock(Device.class);
        when(device.getId()).thenReturn(7L);
        when(deviceRepository.findByDeviceTokenAndDeletedAtIsNull("dtk_7")).thenReturn(Optional.of(device));
        var attrs = new HashMap<String, Object>();

        // Token via the query param fallback (browsers can't set WS headers).
        boolean ok = interceptor.beforeHandshake(req("/ws/devices/7", null, "dtk_7"),
                new ServletServerHttpResponse(new MockHttpServletResponse()), handler, attrs);

        assertTrue(ok);
        assertEquals(7L, attrs.get(DeviceTokenHandshakeInterceptor.ATTR_DEVICE_ID));
    }

    private static ServletServerHttpRequest req(String path, String headerToken, String queryToken) {
        var mock = new MockHttpServletRequest("GET", path);
        mock.setRequestURI(path);
        if (headerToken != null) {
            mock.addHeader(DeviceTokenAuthFilter.HEADER, headerToken);
        }
        if (queryToken != null) {
            mock.setParameter("device_token", queryToken);
        }
        return new ServletServerHttpRequest(mock);
    }
}
