package uz.orientadvertise.services.service.seed;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.Region;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.service.DeviceRegistrationService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FakeDeviceSeederTest {

    private DeviceRepository deviceRepository;
    private DeviceRegistrationService registrationService;
    private SeedProperties props;
    private FakeDeviceSeeder seeder;
    private Map<String, Device> store;

    @BeforeEach
    void setUp() {
        deviceRepository = mock(DeviceRepository.class);
        registrationService = mock(DeviceRegistrationService.class);
        props = new SeedProperties();
        seeder = new FakeDeviceSeeder(props, registrationService, deviceRepository);

        store = new HashMap<>();
        when(deviceRepository.findBySerialNumberAndDeletedAtIsNull(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(store.get(inv.<String>getArgument(0))));
        // Simulate registration committing the row by inserting into the in-memory store.
        when(registrationService.register(anyString(), anyString())).thenAnswer(inv -> {
            String serial = inv.getArgument(0);
            String name = inv.getArgument(1);
            store.put(serial, new Device(mock(Region.class), null, serial, name));
            return new DeviceRegistrationService.RegistrationResult(1L, "dtk_test", serial, true, null);
        });
    }

    @Test
    void run_disabled_doesNothing() {
        props.setEnabled(false);
        props.setDeviceCount(5);

        seeder.run(null);

        verify(registrationService, never()).register(any(), any());
        verify(deviceRepository, never()).findBySerialNumberAndDeletedAtIsNull(any());
    }

    @Test
    void run_enabled_registersConfiguredCount() {
        props.setEnabled(true);
        props.setDeviceCount(3);

        seeder.run(null);

        verify(registrationService).register("FAKE-TV-1", "Fake Android TV 1");
        verify(registrationService).register("FAKE-TV-2", "Fake Android TV 2");
        verify(registrationService).register("FAKE-TV-3", "Fake Android TV 3");
        verify(registrationService, times(3)).register(anyString(), anyString());
    }

    @Test
    void run_repeated_isIdempotent() {
        props.setEnabled(true);
        props.setDeviceCount(3);

        seeder.run(null);
        seeder.run(null);

        // Three registrations on first pass; second pass sees existing serials and skips.
        verify(registrationService, times(3)).register(anyString(), anyString());
        assertEquals(3, store.size());
    }

    @Test
    void run_zeroCount_doesNothing() {
        props.setEnabled(true);
        props.setDeviceCount(0);

        seeder.run(null);

        verify(registrationService, never()).register(any(), any());
    }

    @Test
    void run_negativeCount_treatedAsZero() {
        props.setEnabled(true);
        props.setDeviceCount(-7);

        seeder.run(null);

        verify(registrationService, never()).register(any(), any());
    }
}
