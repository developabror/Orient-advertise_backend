package uz.orientadvertise.services.service.seed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.service.DeviceRegistrationService;

/**
 * Boot-time seeder that registers fake Android TV devices when {@code app.seed.enabled=true}.
 *
 * <p>Goes through {@link DeviceRegistrationService#register} so the fake devices land in the
 * exact same state a real TV-Box would after first boot — {@code status=ONLINE}, a device
 * token issued, attached to the {@code -1} ("Unassigned") region — which makes them
 * targetable by content assignment without any extra wiring.
 *
 * <p>Idempotent: serials are deterministic ({@code FAKE-TV-1}, {@code FAKE-TV-2}, …), and
 * the registration service upserts on existing serials, so re-running on every boot leaves
 * the device count stable.
 */
@Component
public class FakeDeviceSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FakeDeviceSeeder.class);

    private final SeedProperties props;
    private final DeviceRegistrationService deviceRegistrationService;
    private final DeviceRepository deviceRepository;

    public FakeDeviceSeeder(SeedProperties props,
                            DeviceRegistrationService deviceRegistrationService,
                            DeviceRepository deviceRepository) {
        this.props = props;
        this.deviceRegistrationService = deviceRegistrationService;
        this.deviceRepository = deviceRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.isEnabled()) {
            return;
        }
        int count = Math.max(0, props.getDeviceCount());
        log.info("Seeding fake Android TV devices [count={}, prefix={}]", count, props.getSerialPrefix());

        int created = 0;
        for (int i = 1; i <= count; i++) {
            var serial = props.getSerialPrefix() + i;
            var alreadyExists = deviceRepository.findBySerialNumberAndDeletedAtIsNull(serial).isPresent();
            if (alreadyExists) {
                continue;
            }
            var name = props.getNamePrefix() + i;
            deviceRegistrationService.register(serial, name);
            created++;
        }
        log.info("Fake device seeding complete [requested={}, created={}, alreadyExisted={}]",
                count, created, count - created);
    }

    @Configuration
    @EnableConfigurationProperties(SeedProperties.class)
    static class SeedConfig {
    }
}
