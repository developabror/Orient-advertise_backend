package uz.orientadvertise.services.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HealthStatusTest {

    @Test
    void record_holdsValues() {
        var status = new HealthStatus("db", new HealthStatus.Status.Up(), "2024-01-01T00:00:00Z");
        assertEquals("db", status.component());
        assertInstanceOf(HealthStatus.Status.Up.class, status.status());
        assertEquals("2024-01-01T00:00:00Z", status.timestamp());
    }

    @Test
    void record_equalsWorks() {
        var a = new HealthStatus("app", new HealthStatus.Status.Up(), "ts1");
        var b = new HealthStatus("app", new HealthStatus.Status.Up(), "ts1");
        var c = new HealthStatus("app", new HealthStatus.Status.Down("fail"), "ts1");
        assertEquals(a, b);
        assertNotEquals(a, c);
    }

    @Test
    void isUp_returnsTrueForUpStatus() {
        var status = new HealthStatus("app", new HealthStatus.Status.Up(), "ts");
        assertTrue(status.isUp());
    }

    @Test
    void isUp_returnsFalseForDownStatus() {
        var status = new HealthStatus("app", new HealthStatus.Status.Down("err"), "ts");
        assertFalse(status.isUp());
    }

    @Test
    void statusName_usesPatternMatchingForUp() {
        var status = new HealthStatus("app", new HealthStatus.Status.Up(), "ts");
        assertEquals("UP", status.statusName());
    }

    @Test
    void statusName_includesReasonForDown() {
        var status = new HealthStatus("app", new HealthStatus.Status.Down("timeout"), "ts");
        assertEquals("DOWN: timeout", status.statusName());
    }

    @Test
    void statusName_handlesUnknown() {
        var status = new HealthStatus("app", new HealthStatus.Status.Unknown(), "ts");
        assertEquals("UNKNOWN", status.statusName());
    }

    private static void assertInstanceOf(Class<?> expected, Object actual) {
        assertTrue(expected.isInstance(actual),
                "Expected %s but got %s".formatted(expected.getSimpleName(), actual.getClass().getSimpleName()));
    }
}
