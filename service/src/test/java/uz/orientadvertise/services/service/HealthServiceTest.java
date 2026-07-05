package uz.orientadvertise.services.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.orientadvertise.services.domain.model.HealthStatus;
import uz.orientadvertise.services.infra.health.DatabaseHealthIndicator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HealthServiceTest {

    private DatabaseHealthIndicator dbIndicator;
    private HealthService healthService;

    @BeforeEach
    void setUp() {
        dbIndicator = mock(DatabaseHealthIndicator.class);
        healthService = new HealthService(dbIndicator);
    }

    @Test
    void checkAll_returnsAllComponentsUp() {
        when(dbIndicator.check()).thenReturn(
                new HealthStatus("database", new HealthStatus.Status.Up(), "2024-01-01T00:00:00Z")
        );

        var result = healthService.checkAll();

        assertEquals(2, result.size());
        assertEquals("application", result.getFirst().component());
        assertTrue(result.getFirst().isUp());
        assertEquals("database", result.getLast().component());
        assertTrue(result.getLast().isUp());
    }

    @Test
    void checkAll_reflectsDatabaseDown() {
        when(dbIndicator.check()).thenReturn(
                new HealthStatus("database", new HealthStatus.Status.Down("conn refused"), "2024-01-01T00:00:00Z")
        );

        var result = healthService.checkAll();

        assertEquals(2, result.size());
        assertInstanceOf(HealthStatus.Status.Down.class, result.getLast().status());

        // JDK 21 record pattern — extract reason from Down
        if (result.getLast().status() instanceof HealthStatus.Status.Down(var reason)) {
            assertEquals("conn refused", reason);
        }
    }

    @Test
    void checkApplication_returnsUp() {
        var result = healthService.checkApplication();

        assertEquals("application", result.component());
        assertTrue(result.isUp());
    }
}
