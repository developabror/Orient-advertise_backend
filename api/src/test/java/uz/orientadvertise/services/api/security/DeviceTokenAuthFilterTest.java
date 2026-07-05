package uz.orientadvertise.services.api.security;

import java.util.Optional;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.repository.DeviceRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceTokenAuthFilterTest {

    private DeviceRepository deviceRepository;
    private DeviceTokenAuthFilter filter;
    private FilterChain chain;
    private ch.qos.logback.classic.Logger filterLogger;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        deviceRepository = mock(DeviceRepository.class);
        filter = new DeviceTokenAuthFilter(deviceRepository);
        chain = mock(FilterChain.class);
        SecurityContextHolder.clearContext();

        filterLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(DeviceTokenAuthFilter.class);
        logs = new ListAppender<>();
        logs.start();
        filterLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        filterLogger.detachAppender(logs);
    }

    private long missingTokenLogs() {
        return logs.list.stream()
                .filter(e -> e.getLevel() == Level.INFO)
                .filter(e -> e.getFormattedMessage().contains("carried no " + DeviceTokenAuthFilter.HEADER))
                .count();
    }

    @Test
    void noHeader_passesThroughWithoutQueryingRepo() throws Exception {
        var req = new MockHttpServletRequest();
        var res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
        verify(deviceRepository, never()).findByDeviceTokenAndDeletedAtIsNull(any());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void noHeader_onDeviceAgentPath_logsMissingToken_andStillPassesThrough() throws Exception {
        var req = new MockHttpServletRequest();
        req.setRequestURI("/api/devices/7/heartbeat");
        var res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        assertEquals(1, missingTokenLogs(), "agent path with no token should log exactly once");
        assertTrue(logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("/api/devices/7/heartbeat")),
                "log should name the offending URI");
        verify(chain).doFilter(req, res);
        verify(deviceRepository, never()).findByDeviceTokenAndDeletedAtIsNull(any());
    }

    @Test
    void noHeader_onRegisterPath_doesNotLog() throws Exception {
        var req = new MockHttpServletRequest();
        req.setRequestURI("/api/devices/register");

        filter.doFilter(req, new MockHttpServletResponse(), chain);

        assertEquals(0, missingTokenLogs(), "open register endpoint must not warn about a missing token");
        verify(chain).doFilter(any(), any());
    }

    @Test
    void noHeader_onAdminCrudPath_doesNotLog() throws Exception {
        var req = new MockHttpServletRequest();
        req.setRequestURI("/api/devices/7"); // JWT-guarded admin CRUD, no action segment

        filter.doFilter(req, new MockHttpServletResponse(), chain);

        assertEquals(0, missingTokenLogs(), "admin CRUD (no action segment) must not be flagged as a device agent");
        verify(chain).doFilter(any(), any());
    }

    @Test
    void noHeader_onNonDevicePath_doesNotLog() throws Exception {
        var req = new MockHttpServletRequest();
        req.setRequestURI("/api/auth/login");

        filter.doFilter(req, new MockHttpServletResponse(), chain);

        assertEquals(0, missingTokenLogs(), "non-device paths must not log");
        verify(chain).doFilter(any(), any());
    }

    @Test
    void invalidToken_returns401_withoutCallingChain() throws Exception {
        var req = new MockHttpServletRequest();
        req.addHeader(DeviceTokenAuthFilter.HEADER, "dtk_bogus");
        when(deviceRepository.findByDeviceTokenAndDeletedAtIsNull("dtk_bogus")).thenReturn(Optional.empty());
        var res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        assertEquals(401, res.getStatus());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void validToken_authenticatesAsDevicePrincipalEqualToId() throws Exception {
        var device = mock(Device.class);
        when(device.getId()).thenReturn(7L);
        when(deviceRepository.findByDeviceTokenAndDeletedAtIsNull("dtk_good")).thenReturn(Optional.of(device));

        var req = new MockHttpServletRequest();
        req.addHeader(DeviceTokenAuthFilter.HEADER, "dtk_good");

        var captured = new java.util.concurrent.atomic.AtomicReference<Object>();
        FilterChain capturingChain = (rq, rs) -> {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            assertNotNull(auth);
            captured.set(auth.getPrincipal());
            assertTrue(auth.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals(DeviceTokenAuthFilter.ROLE)));
        };

        filter.doFilter(req, new MockHttpServletResponse(), capturingChain);

        // Principal is the numeric device id (drives the @PreAuthorize path-binding).
        assertEquals(7L, captured.get());
        // Context cleared after the chain so a later request can't inherit it.
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
}
