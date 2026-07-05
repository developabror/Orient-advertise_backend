package uz.orientadvertise.services.service;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.orientadvertise.services.domain.event.EventPublisher;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.Event;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.EventRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceEventServiceTest {

    private EventRepository eventRepository;
    private EventPublisher eventPublisher;
    private DeviceRepository deviceRepository;
    private DeviceEventService service;

    @BeforeEach
    void setUp() {
        eventRepository = mock(EventRepository.class);
        eventPublisher = mock(EventPublisher.class);
        deviceRepository = mock(DeviceRepository.class);
        service = new DeviceEventService(eventRepository, eventPublisher, deviceRepository);

        var device = mock(Device.class);
        when(device.getId()).thenReturn(1L);
        when(deviceRepository.findById(any())).thenReturn(Optional.of(device));
        when(eventPublisher.publish(any())).thenReturn(true);
    }

    @Test
    void emit_persistsEventAndPublishesPubSub() {
        boolean ok = service.emit(1L, "DEVICE_STATUS_CHANGED", Event.Priority.HIGH, "{\"x\":1}");

        assertTrue(ok);
        ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);
        verify(eventRepository).save(eventCaptor.capture());
        assertEquals("DEVICE_STATUS_CHANGED", eventCaptor.getValue().getEventType());
        assertEquals(Event.Priority.HIGH, eventCaptor.getValue().getPriority());
        verify(eventPublisher).publish(any());
    }

    @Test
    void emit_unknownDevice_returnsFalse_doesNotPersistOrPublish() {
        when(deviceRepository.findById(999L)).thenReturn(Optional.empty());

        boolean ok = service.emit(999L, "X", Event.Priority.INFO, "{}");

        assertFalse(ok);
        verify(eventRepository, never()).save(any());
        verify(eventPublisher, never()).publish(any());
    }

    @Test
    void rateLimit_allowsTen_blocksEleventh_inSameMinute() {
        // Edge case requirement: rapid online/offline cycling — cap at 10/min/device.
        for (int i = 0; i < 10; i++) {
            assertTrue(service.emit(1L, "DEVICE_STATUS_CHANGED", Event.Priority.INFO, "{}"),
                    "emission #" + (i + 1) + " should pass");
        }
        assertFalse(service.emit(1L, "DEVICE_STATUS_CHANGED", Event.Priority.INFO, "{}"),
                "11th emission within the window must be dropped");
        verify(eventRepository, times(10)).save(any());
    }

    @Test
    void rateLimit_isPerDevice_notGlobal() {
        // Saturate device 1
        for (int i = 0; i < 10; i++) {
            service.emit(1L, "X", Event.Priority.INFO, "{}");
        }
        // Device 2 has its own bucket — should still pass.
        var d2 = mock(Device.class);
        when(d2.getId()).thenReturn(2L);
        when(deviceRepository.findById(2L)).thenReturn(Optional.of(d2));

        assertTrue(service.emit(2L, "X", Event.Priority.INFO, "{}"));
    }

    @Test
    void payload_largerThan10KB_truncatedWithMarker() {
        // Build a payload of 12KB so it must be truncated.
        String big = "x".repeat(12 * 1024);

        service.emit(1L, "BIG_EVENT", Event.Priority.INFO, big);

        ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);
        verify(eventRepository).save(eventCaptor.capture());
        String stored = eventCaptor.getValue().getPayload();
        // Truncated to ≤ 10KB plus suffix
        assertTrue(stored.endsWith("...[truncated]"), "payload should carry truncation marker");
        // Body length excludes the suffix; body bytes must be ≤ 10KB
        String body = stored.substring(0, stored.length() - "...[truncated]".length());
        assertTrue(body.getBytes(StandardCharsets.UTF_8).length <= DeviceEventService.MAX_PAYLOAD_BYTES,
                "body must fit within 10KB cap");
    }

    @Test
    void payload_underCap_passesThroughUnchanged() {
        String small = "{\"key\":\"value\"}";
        service.emit(1L, "SMALL", Event.Priority.INFO, small);

        ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);
        verify(eventRepository).save(eventCaptor.capture());
        assertEquals(small, eventCaptor.getValue().getPayload());
    }

    @Test
    void truncate_handlesMultiByteUtf8_atBoundary() {
        // Force a payload whose 10240-th byte falls in the middle of a 2-byte char (é = 0xC3 0xA9).
        // Filling with é means every char takes 2 bytes; cut at 10240 is right between codepoints.
        String s = "é".repeat(7000); // 14000 bytes
        String t = DeviceEventService.truncate(s);

        // Result must be valid UTF-8 (no replacement char from a sliced multi-byte sequence).
        assertFalse(t.contains("�"), "truncation must not produce replacement chars");
        assertTrue(t.endsWith("...[truncated]"));
    }

    @Test
    void truncate_nullPayload_returnsNull() {
        assertNull(DeviceEventService.truncate(null));
    }
}
