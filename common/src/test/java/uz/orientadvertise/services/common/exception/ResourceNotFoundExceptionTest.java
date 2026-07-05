package uz.orientadvertise.services.common.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ResourceNotFoundExceptionTest {

    @Test
    void constructor_formatsMessageCorrectly() {
        ResourceNotFoundException ex = new ResourceNotFoundException("User", 42L);
        assertEquals("User not found with id: 42", ex.getMessage());
    }

    @Test
    void constructor_handlesStringId() {
        ResourceNotFoundException ex = new ResourceNotFoundException("Order", "abc-123");
        assertNotNull(ex.getMessage());
        assertEquals("Order not found with id: abc-123", ex.getMessage());
    }
}
