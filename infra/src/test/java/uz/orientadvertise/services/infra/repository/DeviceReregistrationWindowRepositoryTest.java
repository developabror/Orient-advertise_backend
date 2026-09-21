package uz.orientadvertise.services.infra.repository;

import java.time.Instant;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.Project;
import uz.orientadvertise.services.domain.model.Region;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.ProjectRepository;
import uz.orientadvertise.services.domain.repository.RegionRepository;
import uz.orientadvertise.services.infra.TestApplication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * AUTH-02 against a real database (H2, PostgreSQL mode, full Flyway schema incl. V46): the
 * conditional {@code claimReregistrationWindow} UPDATE, and the persistence details that mocks
 * cannot see — that the token rotated after a claim is actually written, and that a concurrent
 * writer's stale snapshot does not wipe the window ({@code @DynamicUpdate} on {@link Device}).
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class DeviceReregistrationWindowRepositoryTest {

    @Autowired private DataSource dataSource;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private RegionRepository regionRepository;
    @Autowired private DeviceRepository deviceRepository;
    @Autowired private PlatformTransactionManager txManager;

    private Long deviceId;

    @BeforeEach
    void setUp() {
        var flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();

        Project project = projectRepository.save(new Project("Rereg", null));
        Region region = regionRepository.save(new Region(project, "Rereg region", "rr"));
        var device = new Device(region, null, "SN-RR", "RR");
        device.register("dtk_old");
        deviceId = deviceRepository.save(device).getId();
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(txManager);
    }

    private void openWindow(Instant until) {
        tx().executeWithoutResult(s -> {
            var d = deviceRepository.findById(deviceId).orElseThrow();
            d.allowReregistrationUntil(until);
            deviceRepository.save(d);
        });
    }

    private int claim() {
        return tx().execute(s -> deviceRepository.claimReregistrationWindow(deviceId, Instant.now()));
    }

    private Device reload() {
        return tx().execute(s -> deviceRepository.findById(deviceId).orElseThrow());
    }

    @Test
    void openWindow_isClaimedExactlyOnce() {
        openWindow(Instant.now().plusSeconds(600));

        assertEquals(1, claim(), "first registration wins the window");
        assertEquals(0, claim(), "a second registration must not (a concurrent one is serialized by the UPDATE's row lock)");
        assertNull(reload().getReregistrationAllowedUntil());
    }

    @Test
    void noWindow_orExpiredWindow_cannotBeClaimed() {
        assertEquals(0, claim(), "no window open");

        openWindow(Instant.now().minusSeconds(1));
        assertEquals(0, claim(), "an expired window is closed");
    }

    @Test
    void tokenRotatedAfterAClaim_isPersisted() {
        // Mirrors DeviceRegistrationService.register: load, claim, rotate — in one transaction.
        // A clearAutomatically claim would detach the loaded device and silently drop the token.
        openWindow(Instant.now().plusSeconds(600));

        tx().executeWithoutResult(s -> {
            var d = deviceRepository.findBySerialNumberAndDeletedAtIsNull("SN-RR").orElseThrow();
            assertEquals(1, deviceRepository.claimReregistrationWindow(d.getId(), Instant.now()));
            d.register("dtk_new");
        });

        var after = reload();
        assertEquals("dtk_new", after.getDeviceToken());
        assertNull(after.getReregistrationAllowedUntil());
    }

    @Test
    void operatorEditWithAStaleSnapshot_doesNotWipeAWindowOpenedMeanwhile() {
        // A writer (e.g. an operator rename) loads the device (no window yet); meanwhile an admin
        // opens a window and commits; then the writer flushes. Without @DynamicUpdate its full-row
        // UPDATE would write the stale NULL back over the window. (A heartbeat closes the window
        // on purpose — proof of possession — so this uses a writer that must not.)
        var until = Instant.now().plusSeconds(600);
        tx().executeWithoutResult(s -> {
            var editorView = deviceRepository.findById(deviceId).orElseThrow();
            var admin = new TransactionTemplate(txManager);
            admin.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            admin.executeWithoutResult(s2 -> {
                var d = deviceRepository.findById(deviceId).orElseThrow();
                d.allowReregistrationUntil(until);
                deviceRepository.save(d);
            });
            editorView.setName("Renamed");
        });

        var after = reload();
        assertEquals("Renamed", after.getName(), "the edit itself was written");
        assertNotNull(after.getReregistrationAllowedUntil(), "the admin's window must survive the stale edit");
    }
}
