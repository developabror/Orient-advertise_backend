package uz.orientadvertise.services.service;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.orientadvertise.services.domain.event.CriticalIncidentBroadcaster;
import uz.orientadvertise.services.domain.event.DashboardEventBroadcaster;
import uz.orientadvertise.services.domain.event.DashboardEventBroadcaster.IncidentPayload;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.Event;
import uz.orientadvertise.services.domain.model.Event.Priority;
import uz.orientadvertise.services.domain.model.Incident;
import uz.orientadvertise.services.domain.repository.EventRepository;
import uz.orientadvertise.services.domain.repository.IncidentRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that IncidentService fans out the correct {@link DashboardEventBroadcaster}
 * methods on the right state transitions. The legacy {@link CriticalIncidentBroadcaster}
 * (feeding /ws/admin/incidents) and the new {@link DashboardEventBroadcaster} (feeding
 * /ws/dashboard) are independent — both fire on CRITICAL but only the dashboard one
 * fires on acknowledge/resolve.
 */
class IncidentServiceDashboardBroadcastTest {

    private IncidentRepository incidentRepository;
    private EventRepository eventRepository;
    private CriticalIncidentBroadcaster criticalBroadcaster;
    private DashboardEventBroadcaster dashboardBroadcaster;
    private OperatorScopeResolver operatorScopeResolver;
    private IncidentService service;

    @BeforeEach
    void setUp() {
        incidentRepository = mock(IncidentRepository.class);
        eventRepository = mock(EventRepository.class);
        criticalBroadcaster = mock(CriticalIncidentBroadcaster.class);
        dashboardBroadcaster = mock(DashboardEventBroadcaster.class);
        operatorScopeResolver = mock(OperatorScopeResolver.class);
        // Unrestricted scope: excludes() returns false without touching the device graph.
        when(operatorScopeResolver.resolve()).thenReturn(
                new OperatorScopeResolver.ScopedProjects(null, null, null, false));
        service = new IncidentService(incidentRepository, eventRepository,
                criticalBroadcaster, dashboardBroadcaster, operatorScopeResolver);
    }

    @Test
    void newCriticalIncident_firesIncidentCriticalOnDashboard() {
        var device = mockDevice(10L);
        var event = new Event(device, "DEVICE_OFFLINE", Priority.CRITICAL, "{}", Instant.now());
        when(eventRepository.save(any(Event.class))).thenReturn(event);
        when(incidentRepository.findOpenByDeviceAndEventType(10L, "DEVICE_OFFLINE"))
                .thenReturn(Optional.empty());
        when(incidentRepository.save(any(Incident.class))).thenAnswer(inv -> inv.getArgument(0));

        service.processEvent(event);

        verify(dashboardBroadcaster, times(1)).incidentCritical(any());
        verify(dashboardBroadcaster, never()).incidentUpdated(any());
    }

    @Test
    void acknowledge_firesIncidentUpdatedWithStatusAndActor() {
        var incident = stubExistingIncident(7L, Incident.Status.OPEN);
        when(incidentRepository.findById(7L)).thenReturn(Optional.of(incident));

        service.acknowledge(7L, "alice");

        var captor = ArgumentCaptor.forClass(IncidentPayload.class);
        verify(dashboardBroadcaster).incidentUpdated(captor.capture());
        // After acknowledge() the model has flipped to ACKNOWLEDGED — the broadcast
        // payload reflects the post-transition state, not the pre-call state.
        assertEquals("ACKNOWLEDGED", captor.getValue().status());
        assertEquals("alice", captor.getValue().actor());
        verify(dashboardBroadcaster, never()).incidentCritical(any());
    }

    @Test
    void resolve_firesIncidentUpdated() {
        var incident = stubExistingIncident(7L, Incident.Status.OPEN);
        when(incidentRepository.findById(7L)).thenReturn(Optional.of(incident));

        service.resolve(7L, "bob");

        var captor = ArgumentCaptor.forClass(IncidentPayload.class);
        verify(dashboardBroadcaster).incidentUpdated(captor.capture());
        assertEquals("RESOLVED", captor.getValue().status());
        assertEquals("bob", captor.getValue().actor());
    }

    @Test
    void autoResolve_firesIncidentUpdated_withSystemActor() {
        var incident = stubExistingIncident(7L, Incident.Status.OPEN);
        when(incidentRepository.findOpenByDeviceAndEventType(10L, "DEVICE_OFFLINE"))
                .thenReturn(Optional.of(incident));

        service.autoResolveOnRecovery(10L, "DEVICE_OFFLINE");

        var captor = ArgumentCaptor.forClass(IncidentPayload.class);
        verify(dashboardBroadcaster).incidentUpdated(captor.capture());
        assertEquals(Incident.SYSTEM_RESOLVER, captor.getValue().actor());
    }

    @Test
    void dashboardBroadcastFailure_doesNotFailTheCall() {
        // Dashboard fan-out is best-effort — a Redis pub/sub hiccup must not break
        // incident processing.
        var incident = stubExistingIncident(7L, Incident.Status.OPEN);
        when(incidentRepository.findById(7L)).thenReturn(Optional.of(incident));
        org.mockito.Mockito.doThrow(new RuntimeException("redis down"))
                .when(dashboardBroadcaster).incidentUpdated(any());

        // Must not throw.
        service.acknowledge(7L, "alice");
    }

    private static Device mockDevice(Long id) {
        var d = mock(Device.class);
        when(d.getId()).thenReturn(id);
        // ack/resolve scope guard eagerly reads getRegion().getProject().getId() as the
        // excludes() argument; stub the nav chain so it doesn't NPE under an unrestricted scope.
        var region = mock(uz.orientadvertise.services.domain.model.Region.class);
        var project = mock(uz.orientadvertise.services.domain.model.Project.class);
        when(project.getId()).thenReturn(900L);
        when(region.getProject()).thenReturn(project);
        when(d.getRegion()).thenReturn(region);
        return d;
    }

    private static Incident stubExistingIncident(Long id, Incident.Status status) {
        var device = mockDevice(10L);
        var firstEvent = new Event(device, "DEVICE_OFFLINE", Priority.CRITICAL, "{}", Instant.now());
        var incident = new Incident(device, "DEVICE_OFFLINE", Priority.CRITICAL, "desc", firstEvent);
        // Reflectively set id since it's normally autogenerated.
        try {
            var f = Incident.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(incident, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return incident;
    }
}
