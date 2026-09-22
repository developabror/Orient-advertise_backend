package uz.orientadvertise.services.service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.orientadvertise.services.common.exception.IllegalConfigurationException;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.Event;
import uz.orientadvertise.services.domain.model.Region;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.RegionRepository;
import uz.orientadvertise.services.service.exception.DeviceAlreadyRegisteredException;

@Service
public class DeviceRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(DeviceRegistrationService.class);

    // Mirrors V13__device_registration.sql. Re-applied inline when the sentinel
    // pair has been deleted post-migration so registration never blocks the
    // TV-Box on missing seed data. Idempotent via WHERE NOT EXISTS.
    private static final String SEED_DEFAULT_PROJECT_SQL =
            "INSERT INTO project (id, name, created_at, updated_at) "
            + "SELECT -1, 'Default', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP "
            + "WHERE NOT EXISTS (SELECT 1 FROM project WHERE id = -1)";

    private static final String SEED_DEFAULT_REGION_SQL =
            "INSERT INTO region (id, project_id, name, code, created_at, updated_at) "
            + "SELECT -1, -1, 'Unassigned', 'UNASSIGNED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP "
            + "WHERE NOT EXISTS (SELECT 1 FROM region WHERE id = -1)";

    private final DeviceRepository deviceRepository;
    private final RegionRepository regionRepository;
    private final DeviceEventService deviceEventService;
    private final EntityManager entityManager;
    private final Long defaultRegionId;
    private final Duration reregistrationWindow;

    public DeviceRegistrationService(DeviceRepository deviceRepository,
                                      RegionRepository regionRepository,
                                      DeviceEventService deviceEventService,
                                      EntityManager entityManager,
                                      @Value("${app.device.default-region-id:-1}") Long defaultRegionId,
                                      @Value("${app.device.reregistration-window:PT1H}") Duration reregistrationWindow) {
        this.deviceRepository = deviceRepository;
        this.regionRepository = regionRepository;
        this.deviceEventService = deviceEventService;
        this.entityManager = entityManager;
        this.defaultRegionId = defaultRegionId;
        if (reregistrationWindow == null || reregistrationWindow.compareTo(Duration.ofMinutes(1)) < 0) {
            // A bare number binds as milliseconds ("60" = 60 ms), which would make the admin
            // button a silent no-op — fail fast instead.
            throw new IllegalConfigurationException(
                    "app.device.reregistration-window must be at least 1 minute, e.g. PT1H "
                            + "(set APP_DEVICE_REREGISTRATION_WINDOW)");
        }
        this.reregistrationWindow = reregistrationWindow;
    }

    /** Whether this serial belongs to a live, registered device — picks the rate-limit budget. */
    @Transactional(readOnly = true)
    public boolean isRegistered(String serialNumber) {
        return deviceRepository.findBySerialNumberAndDeletedAtIsNull(serialNumber)
                .map(Device::isRegistered)
                .orElse(false);
    }

    /**
     * AUTH-02: let this device's serial re-register once within the configured window — for a TV
     * box that was wiped or reinstalled and lost its token. Returns when the window closes. A
     * heartbeat from the device (proof it still holds its token) closes the window early.
     */
    @Transactional
    public Instant allowReregistration(Long deviceId) {
        var device = deviceRepository.findByIdAndDeletedAtIsNull(deviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Device", deviceId));
        var until = Instant.now().plus(reregistrationWindow);
        device.allowReregistrationUntil(until);
        deviceRepository.save(device);
        log.info("Re-registration window opened [id={}, serial={}, until={}]", deviceId, device.getSerialNumber(), until);
        return until;
    }

    /**
     * Register a device by serial number. TV-Box calls this on first boot.
     * A new serial is created; an existing but never-registered row is completed. An already
     * registered serial is refused ({@link DeviceAlreadyRegisteredException} → 409) unless an
     * ADMIN opened a re-registration window ({@link #allowReregistration}) — the endpoint is
     * public, so rotating the token on request would hand any caller the device (AUTH-02).
     * Returns device ID and a device token for subsequent API calls.
     */
    @Transactional
    public RegistrationResult register(String serialNumber, String deviceName) {
        var existing = deviceRepository.findBySerialNumberAndDeletedAtIsNull(serialNumber);

        if (existing.isPresent()) {
            var device = existing.get();
            if (device.isRegistered()) {
                // Atomic claim: of two concurrent callers only one matches the open window.
                if (deviceRepository.claimReregistrationWindow(device.getId(), Instant.now()) != 1) {
                    throw new DeviceAlreadyRegisteredException(serialNumber, device.getId());
                }
                // Re-registration allowed by an admin: refresh token, keep existing data
                var newToken = generateToken();
                device.register(newToken);
                log.info("Re-registered device [serial={}, id={}, newToken]", serialNumber, device.getId());
                emitRegistrationEvent(device.getId(), "DEVICE_REREGISTERED");
                return new RegistrationResult(device.getId(), newToken, device.getSerialNumber(), false,
                        device.getSyncGroupId());
            }
            // Existing unregistered device: complete registration
            var token = generateToken();
            device.register(token);
            if (deviceName != null) {
                device.setName(deviceName);
            }
            log.info("Registered existing device [serial={}, id={}]", serialNumber, device.getId());
            emitRegistrationEvent(device.getId(), "DEVICE_REGISTERED");
            return new RegistrationResult(device.getId(), token, device.getSerialNumber(), true,
                    device.getSyncGroupId());
        }

        // New device: create and register. A soft-deleted device with this serial does not block it
        // (V50: the serial is unique among live devices only) — the box gets a fresh device row.
        var region = resolveDefaultRegion();
        var name = deviceName != null ? deviceName : "Device-" + serialNumber;
        var device = new Device(region, null, serialNumber, name);
        var token = generateToken();
        device.register(token);
        deviceRepository.save(device);

        log.info("Registered new device [serial={}, id={}]", serialNumber, device.getId());
        emitRegistrationEvent(device.getId(), "DEVICE_REGISTERED");
        return new RegistrationResult(device.getId(), token, device.getSerialNumber(), true,
                device.getSyncGroupId());
    }

    /**
     * Resolve the default region. When the configured id is the canonical -1
     * sentinel and the row is missing, lazily re-seed the V13 project/region
     * pair so first-time registration always succeeds — the operator can move
     * the device to a real region later. For any other configured id we throw,
     * since the parent project cannot be inferred.
     */
    private Region resolveDefaultRegion() {
        var existing = regionRepository.findById(defaultRegionId);
        if (existing.isPresent()) {
            return existing.get();
        }
        if (defaultRegionId != -1L) {
            // Operator configured app.device.default-region-id to a region that doesn't exist —
            // a deployment/config fault (500), not an operator conflict (409). The sentinel (-1)
            // path below self-heals; a custom id can't be auto-provisioned (no parent project).
            throw new IllegalConfigurationException("Default region not found");
        }
        log.warn("Sentinel default region [-1] missing — re-seeding canonical project/region pair");
        entityManager.createNativeQuery(SEED_DEFAULT_PROJECT_SQL).executeUpdate();
        entityManager.createNativeQuery(SEED_DEFAULT_REGION_SQL).executeUpdate();
        entityManager.flush();
        return regionRepository.findById(-1L)
                .orElseThrow(() -> new IllegalConfigurationException("Failed to provision sentinel default region"));
    }

    private void emitRegistrationEvent(Long deviceId, String eventType) {
        deviceEventService.emitAsync(deviceId, eventType, Event.Priority.INFO, "{}");
    }

    private String generateToken() {
        return "dtk_" + UUID.randomUUID().toString().replace("-", "");
    }

    public record RegistrationResult(Long deviceId, String deviceToken, String serialNumber,
                                     boolean newRegistration, String syncGroupId) {}
}
