package uz.orientadvertise.services.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import uz.orientadvertise.services.domain.model.Event;
import uz.orientadvertise.services.domain.repository.EventRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventQueryServiceTest {

    private EventRepository eventRepository;
    private OperatorScopeResolver operatorScopeResolver;
    private EventQueryService service;

    @BeforeEach
    void setUp() {
        eventRepository = mock(EventRepository.class);
        operatorScopeResolver = mock(OperatorScopeResolver.class);
        when(operatorScopeResolver.resolve())
                .thenReturn(new OperatorScopeResolver.ScopedProjects(null, null, null, false));
        service = new EventQueryService(eventRepository, operatorScopeResolver);
        when(eventRepository.findFiltered(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));
    }

    @Test
    void noDeviceOrFacility_throws400() {
        var ex = assertThrows(IllegalArgumentException.class, () ->
                service.findFiltered(null, null, null, null, null, PageRequest.of(0, 20)));
        assertTrue(ex.getMessage().contains("deviceId or facilityId"));
    }

    @Test
    void deviceIdOnly_isAccepted() {
        service.findFiltered(1L, null, null, null, null, PageRequest.of(0, 20));
        verify(eventRepository).findFiltered(eq(1L), eq(null), any(), any(), any(), any(), any());
    }

    @Test
    void facilityIdOnly_isAccepted() {
        service.findFiltered(null, 5L, null, null, null, PageRequest.of(0, 20));
        verify(eventRepository).findFiltered(eq(null), eq(5L), any(), any(), any(), any(), any());
    }

    @Test
    void rangeOverNinetyDays_throws400() {
        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofDays(91));
        var ex = assertThrows(IllegalArgumentException.class, () ->
                service.findFiltered(1L, null, null, from, to, PageRequest.of(0, 20)));
        assertTrue(ex.getMessage().contains("90 days"));
    }

    @Test
    void rangeExactlyNinetyDays_isAccepted() {
        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofDays(90));
        service.findFiltered(1L, null, null, from, to, PageRequest.of(0, 20));
        verify(eventRepository).findFiltered(eq(1L), any(), any(), eq(from), eq(to), any(), any());
    }

    @Test
    void missingBounds_defaultToLast90Days() {
        service.findFiltered(1L, null, null, null, null, PageRequest.of(0, 20));

        ArgumentCaptor<Instant> fromCap = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> toCap = ArgumentCaptor.forClass(Instant.class);
        verify(eventRepository).findFiltered(any(), any(), any(), fromCap.capture(), toCap.capture(), any(), any());

        Instant from = fromCap.getValue();
        Instant to = toCap.getValue();
        long span = Duration.between(from, to).toDays();
        assertEquals(90, span, "default range = 90 days");
    }

    @Test
    void onlyFromProvided_toDefaultsToNow() {
        Instant from = Instant.now().minus(Duration.ofDays(7));
        service.findFiltered(1L, null, null, from, null, PageRequest.of(0, 20));

        ArgumentCaptor<Instant> toCap = ArgumentCaptor.forClass(Instant.class);
        verify(eventRepository).findFiltered(any(), any(), any(), eq(from), toCap.capture(), any(), any());
        // to should be near now
        assertTrue(Duration.between(toCap.getValue(), Instant.now()).abs().toSeconds() < 5);
    }

    @Test
    void fromAfterTo_throws400() {
        Instant to = Instant.now().minus(Duration.ofDays(1));
        Instant from = Instant.now();
        assertThrows(IllegalArgumentException.class, () ->
                service.findFiltered(1L, null, null, from, to, PageRequest.of(0, 20)));
    }

    @Test
    void pageSizeOver100_throws400() {
        var ex = assertThrows(IllegalArgumentException.class, () ->
                service.findFiltered(1L, null, null, null, null, PageRequest.of(0, 101)));
        assertTrue(ex.getMessage().contains("100"));
    }

    @Test
    void pageSizeAt100_isAccepted() {
        service.findFiltered(1L, null, null, null, null, PageRequest.of(0, 100));
        verify(eventRepository).findFiltered(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void priorityFilter_passedThroughToRepo() {
        service.findFiltered(1L, null, Event.Priority.CRITICAL, null, null, PageRequest.of(0, 20));
        verify(eventRepository).findFiltered(any(), any(),
                eq(Event.Priority.CRITICAL), any(), any(), any(), any());
    }

    @Test
    void resultsAreReturned() {
        var event = mock(Event.class);
        Page<Event> stub = new PageImpl<>(List.of(event));
        when(eventRepository.findFiltered(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(stub);

        Page<Event> result = service.findFiltered(1L, null, null, null, null,
                (Pageable) PageRequest.of(0, 20));

        assertEquals(1, result.getTotalElements());
    }
}
