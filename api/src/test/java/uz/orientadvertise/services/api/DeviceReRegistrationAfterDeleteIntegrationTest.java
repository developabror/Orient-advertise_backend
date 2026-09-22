package uz.orientadvertise.services.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import uz.orientadvertise.services.Application;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.service.DeviceEventService;
import uz.orientadvertise.services.service.DeviceManagementService;
import uz.orientadvertise.services.service.DeviceRegistrationService;
import uz.orientadvertise.services.service.exception.DeviceAlreadyRegisteredException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G-6 end to end, on the migrated schema: an admin deletes a TV box's device, the box boots and
 * registers again. That used to be a unique-constraint 500 on every boot, forever (the deleted row
 * kept the globally-unique serial); since V50 the box gets a fresh device.
 */
@SpringBootTest(classes = Application.class)
@ActiveProfiles("test")
class DeviceReRegistrationAfterDeleteIntegrationTest {

    // Registration events are emitted asynchronously; they are not what this test is about.
    @MockitoBean private DeviceEventService deviceEventService;

    @Autowired private DeviceRegistrationService registrationService;
    @Autowired private DeviceManagementService managementService;
    @Autowired private DeviceRepository deviceRepository;

    @Test
    void deletedDevice_registersAgainAsANewDevice_andTheNewOneIsProtectedLikeAnyOther() {
        String serial = "SN-G6-" + System.nanoTime();
        var first = registrationService.register(serial, "Lobby TV");
        managementService.softDelete(first.deviceId());

        var second = registrationService.register(serial, "Lobby TV");

        assertNotEquals(first.deviceId(), second.deviceId());
        assertTrue(second.newRegistration());
        assertEquals(second.deviceId(),
                deviceRepository.findBySerialNumberAndDeletedAtIsNull(serial).orElseThrow().getId());
        assertTrue(deviceRepository.findById(first.deviceId()).orElseThrow().getDeletedAt() != null,
                "the deleted device stays deleted, history intact");

        // AUTH-02 still guards the new live device: a second registration is refused (409).
        assertThrows(DeviceAlreadyRegisteredException.class, () -> registrationService.register(serial, "x"));
    }
}
