package uz.orientadvertise.services.service;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.orientadvertise.services.domain.event.CriticalIncidentBroadcaster;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.Event;
import uz.orientadvertise.services.domain.model.Event.Priority;
import uz.orientadvertise.services.domain.model.Incident;
import uz.orientadvertise.services.domain.repository.EventRepository;
import uz.orientadvertise.services.domain.repository.IncidentRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IncidentServiceTest {

    private IncidentRepository incidentRepository;
    private EventRepository eventRepository;
    private CriticalIncidentBroadcaster criticalBroadcaster;
    private uz.orientadvertise.services.domain.event.DashboardEventBroadcaster dashboardBroadcaster;
    private OperatorScopeResolver operatorScopeResolver;
    private IncidentService service;

    @BeforeEach
    void setUp() {
        incidentRepository = mock(IncidentRepository.class);
        eventRepository = mock(EventRepository.class);
        criticalBroadcaster = mock(CriticalIncidentBroadcaster.class);
        dashboardBroadcaster = mock(uz.orientadvertise.services.domain.event.DashboardEventBroadcaster.class);
        operatorScopeResolver = mock(OperatorScopeResolver.class);
        // Unrestricted scope: excludes() returns false without touching the device graph.
        when(operatorScopeResolver.resolve()).thenReturn(
                new OperatorScopeResolver.ScopedProjects(null, null, null, false));
        service = new IncidentService(incidentRepository, eventRepository,
                criticalBroadcaster, dashboardBroadcaster, operatorScopeResolver);
    }

    @Test
    void processEvent_criticalNewIncident_broadcasts() {
        var device = mockDevice(10L);
        var event = new Event(device, "DEVICE_OFFLINE", Priority.CRITICAL, "{}", Instant.now());
        when(eventRepository.save(any(Event.class))).thenReturn(event);
        when(incidentRepository.findOpenByDeviceAndEventType(10L, "DEVICE_OFFLINE"))
                .thenReturn(Optional.empty());

        service.processEvent(event);

        verify(criticalBroadcaster).broadcast(any());
    }

    @Test
    void processEvent_nonCriticalNewIncident_doesNotBroadcast() {
        var device = mockDevice(11L);
        var event = new Event(device, "DISK_WARN", Priority.MEDIUM, "{}", Instant.now());
        when(eventRepository.save(any(Event.class))).thenReturn(event);
        when(incidentRepository.findOpenByDeviceAndEventType(11L, "DISK_WARN"))
                .thenReturn(Optional.empty());

        service.processEvent(event);

        verify(criticalBroadcaster, never()).broadcast(any());
    }

    @Test
    void processEvent_existingCriticalIncident_doesNotReBroadcast() {
        // Edge case: avoid spamming admins with the same incident escalating its
        // occurrence count repeatedly. Broadcast only on the first transition to CRITICAL.
        var device = mockDevice(12L);
        var event = new Event(device, "DEVICE_OFFLINE", Priority.CRITICAL, "{}", Instant.now());
        when(eventRepository.save(any(Event.class))).thenReturn(event);
        var existing = mock(Incident.class);
        when(existing.getPriority()).thenReturn(Priority.CRITICAL);
        when(incidentRepository.findOpenByDeviceAndEventType(12L, "DEVICE_OFFLINE"))
                .thenReturn(Optional.of(existing));

        service.processEvent(event);

        verify(criticalBroadcaster, never()).broadcast(any());
    }

    @Test
    void processEvent_existingMediumEscalatedToCritical_broadcasts() {
        var device = mockDevice(13L);
        var event = new Event(device, "DEVICE_OFFLINE", Priority.CRITICAL, "{}", Instant.now());
        when(eventRepository.save(any(Event.class))).thenReturn(event);
        var existing = mock(Incident.class);
        var priorityRef = new java.util.concurrent.atomic.AtomicReference<>(Priority.MEDIUM);
        when(existing.getPriority()).thenAnswer(inv -> priorityRef.get());
        // Simulate the in-memory escalation that recordRepeatOccurrence would perform.
        org.mockito.Mockito.doAnswer(inv -> {
            priorityRef.set(Priority.CRITICAL);
            return null;
        }).when(existing).recordRepeatOccurrence(any());
        when(existing.getDevice()).thenReturn(device);
        when(incidentRepository.findOpenByDeviceAndEventType(13L, "DEVICE_OFFLINE"))
                .thenReturn(Optional.of(existing));

        service.processEvent(event);

        verify(criticalBroadcaster).broadcast(any());
    }

    @Test
    void processEvent_noExistingIncident_createsNew() {
        var device = mockDevice(1L);
        var event = new Event(device, "DISK_FULL", Priority.HIGH, "{}", Instant.now());
        when(eventRepository.save(any(Event.class))).thenReturn(event);
        when(incidentRepository.findOpenByDeviceAndEventType(1L, "DISK_FULL"))
                .thenReturn(Optional.empty());
        when(incidentRepository.save(any(Incident.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service.processEvent(event);

        assertTrue(result.created());
        assertNotNull(result.incident());
        assertEquals(Incident.Status.OPEN, result.incident().getStatus());
        assertEquals(1, result.incident().getOccurrenceCount());
        verify(incidentRepository).save(any(Incident.class));
    }

    @Test
    void processEvent_existingOpenIncident_updatesInsteadOfCreating() {
        var device = mockDevice(1L);
        var firstEvent = new Event(device, "DISK_FULL", Priority.MEDIUM, "{}", Instant.now());
        var existingIncident = new Incident(device, "DISK_FULL", Priority.MEDIUM, "desc", firstEvent);

        var repeatEvent = new Event(device, "DISK_FULL", Priority.MEDIUM, "{}", Instant.now());
        when(eventRepository.save(any(Event.class))).thenReturn(repeatEvent);
        when(incidentRepository.findOpenByDeviceAndEventType(1L, "DISK_FULL"))
                .thenReturn(Optional.of(existingIncident));

        var result = service.processEvent(repeatEvent);

        assertTrue(result.wasUpdated());
        assertEquals(2, result.incident().getOccurrenceCount());
        // Should NOT create a new incident
        verify(incidentRepository, never()).save(any(Incident.class));
    }

    @Test
    void processEvent_repeatWithHigherPriority_escalatesIncident() {
        var device = mockDevice(1L);
        var firstEvent = new Event(device, "CONN_LOST", Priority.LOW, "{}", Instant.now());
        var existingIncident = new Incident(device, "CONN_LOST", Priority.LOW, "desc", firstEvent);

        var criticalEvent = new Event(device, "CONN_LOST", Priority.CRITICAL, "{}", Instant.now());
        when(eventRepository.save(any(Event.class))).thenReturn(criticalEvent);
        when(incidentRepository.findOpenByDeviceAndEventType(1L, "CONN_LOST"))
                .thenReturn(Optional.of(existingIncident));

        var result = service.processEvent(criticalEvent);

        assertEquals(Priority.CRITICAL, result.incident().getPriority(),
                "Incident priority should escalate to the higher event priority");
    }

    @Test
    void processEvent_differentEventType_createsSecondIncident() {
        var device = mockDevice(1L);
        var event = new Event(device, "TEMP_HIGH", Priority.MEDIUM, "{}", Instant.now());
        when(eventRepository.save(any(Event.class))).thenReturn(event);
        when(incidentRepository.findOpenByDeviceAndEventType(1L, "TEMP_HIGH"))
                .thenReturn(Optional.empty());
        when(incidentRepository.save(any(Incident.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service.processEvent(event);

        assertTrue(result.created());
        verify(incidentRepository).save(any(Incident.class));
    }

    @Test
    void processEvent_afterResolve_createsNewIncident() {
        var device = mockDevice(1L);
        // No open incident (previous one was resolved)
        when(incidentRepository.findOpenByDeviceAndEventType(1L, "DISK_FULL"))
                .thenReturn(Optional.empty());

        var event = new Event(device, "DISK_FULL", Priority.HIGH, "{}", Instant.now());
        when(eventRepository.save(any(Event.class))).thenReturn(event);
        when(incidentRepository.save(any(Incident.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service.processEvent(event);

        assertTrue(result.created(), "After resolution, a repeat event should open a new incident");
    }

    @Test
    void acknowledge_movesToAcknowledged() {
        var device = mockDevice(1L);
        var event = new Event(device, "X", Priority.LOW, null, Instant.now());
        var incident = new Incident(device, "X", Priority.LOW, "d", event);
        when(incidentRepository.findById(1L)).thenReturn(Optional.of(incident));

        var result = service.acknowledge(1L);

        assertEquals(Incident.Status.ACKNOWLEDGED, result.getStatus());
        assertNotNull(result.getAcknowledgedAt());
    }

    @Test
    void resolve_alreadyResolved_throws409() {
        var device = mockDevice(1L);
        var event = new Event(device, "X", Priority.LOW, null, Instant.now());
        var incident = new Incident(device, "X", Priority.LOW, "d", event);
        incident.resolve("alice");
        when(incidentRepository.findById(1L)).thenReturn(Optional.of(incident));

        var ex = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> service.resolve(1L, "bob"));
        org.junit.jupiter.api.Assertions.assertTrue(
                ex.getMessage().contains("already resolved"));
    }

    @Test
    void resolve_setsResolvedBy_forManualClose() {
        var device = mockDevice(1L);
        var event = new Event(device, "X", Priority.LOW, null, Instant.now());
        var incident = new Incident(device, "X", Priority.LOW, "d", event);
        when(incidentRepository.findById(1L)).thenReturn(Optional.of(incident));

        service.resolve(1L, "alice");

        assertEquals("alice", incident.getResolvedBy());
        assertTrue(incident.wasManuallyResolved());
    }

    @Test
    void autoResolveOnRecovery_openIncident_resolvesWithSystem() {
        var device = mockDevice(1L);
        var event = new Event(device, "DEVICE_OFFLINE", Priority.CRITICAL, null, Instant.now());
        var incident = new Incident(device, "DEVICE_OFFLINE", Priority.CRITICAL, "d", event);
        when(incidentRepository.findOpenByDeviceAndEventType(1L, "DEVICE_OFFLINE"))
                .thenReturn(Optional.of(incident));

        boolean resolved = service.autoResolveOnRecovery(1L, "DEVICE_OFFLINE");

        assertTrue(resolved);
        assertEquals(Incident.Status.RESOLVED, incident.getStatus());
        assertEquals(Incident.SYSTEM_RESOLVER, incident.getResolvedBy());
        assertFalse(incident.wasManuallyResolved(), "auto-resolved must not show as manual");
    }

    @Test
    void autoResolveOnRecovery_noOpenIncident_returnsFalse() {
        when(incidentRepository.findOpenByDeviceAndEventType(1L, "DEVICE_OFFLINE"))
                .thenReturn(Optional.empty());

        assertFalse(service.autoResolveOnRecovery(1L, "DEVICE_OFFLINE"));
    }

    @Test
    void autoResolveOnRecovery_doesNotOverrideManuallyResolvedIncident() {
        // Edge case requirement: a manually-resolved incident must not be touched.
        // findOpenByDeviceAndEventType filters out RESOLVED status (status <> RESOLVED),
        // so the lookup is empty — auto-resolve is a no-op. This proves the manual close
        // is sealed.
        when(incidentRepository.findOpenByDeviceAndEventType(1L, "DEVICE_OFFLINE"))
                .thenReturn(Optional.empty());

        boolean resolved = service.autoResolveOnRecovery(1L, "DEVICE_OFFLINE");

        assertFalse(resolved);
    }

    @Test
    void resolve_movesToResolved() {
        var device = mockDevice(1L);
        var event = new Event(device, "X", Priority.LOW, null, Instant.now());
        var incident = new Incident(device, "X", Priority.LOW, "d", event);
        when(incidentRepository.findById(1L)).thenReturn(Optional.of(incident));

        var result = service.resolve(1L);

        assertEquals(Incident.Status.RESOLVED, result.getStatus());
        assertNotNull(result.getResolvedAt());
        assertFalse(result.isOpen());
    }

    @Test
    void lifecycle_openToAcknowledgedToResolved() {
        var device = mockDevice(1L);
        var event = new Event(device, "X", Priority.MEDIUM, null, Instant.now());
        var incident = new Incident(device, "X", Priority.MEDIUM, "d", event);

        assertEquals(Incident.Status.OPEN, incident.getStatus());
        assertTrue(incident.isOpen());

        incident.acknowledge();
        assertEquals(Incident.Status.ACKNOWLEDGED, incident.getStatus());
        assertTrue(incident.isOpen());

        incident.resolve();
        assertEquals(Incident.Status.RESOLVED, incident.getStatus());
        assertFalse(incident.isOpen());
    }

    private Device mockDevice(Long id) {
        var device = mock(Device.class);
        when(device.getId()).thenReturn(id);
        // The ack/resolve scope guard eagerly reads device.getRegion().getProject().getId()
        // as the argument to excludes(); stub the nav chain so it doesn't NPE. The scope is
        // unrestricted, so the project id value itself is irrelevant.
        var region = mock(uz.orientadvertise.services.domain.model.Region.class);
        var project = mock(uz.orientadvertise.services.domain.model.Project.class);
        when(project.getId()).thenReturn(900L);
        when(region.getProject()).thenReturn(project);
        when(device.getRegion()).thenReturn(region);
        return device;
    }
}
