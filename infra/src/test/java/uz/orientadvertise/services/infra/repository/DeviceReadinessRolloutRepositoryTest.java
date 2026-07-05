package uz.orientadvertise.services.infra.repository;

import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.Project;
import uz.orientadvertise.services.domain.model.Region;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.ProjectRepository;
import uz.orientadvertise.services.domain.repository.RegionRepository;
import uz.orientadvertise.services.infra.TestApplication;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Synchronized-playback readiness rollup (§1.4): {@code DeviceRepository.countReadyForVersion} must
 * count only the devices that have already downloaded AND confirmed the target version
 * (currentContentVersion == target AND no pending sync), and must exclude soft-deleted devices so a
 * retired box never blocks a group's cut-over.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class DeviceReadinessRolloutRepositoryTest {

    private static final String TARGET = "v-target";

    @Autowired private DataSource dataSource;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private RegionRepository regionRepository;
    @Autowired private DeviceRepository deviceRepository;
    @Autowired private PlatformTransactionManager txManager;

    private final List<Long> groupIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        var flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        groupIds.clear();

        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            Project project = projectRepository.save(new Project("Readiness", null));
            Region region = regionRepository.save(new Region(project, "R", "r"));

            // Ready: has the target version, no pending sync → counts.
            Device ready = new Device(region, null, "SN-READY", "Ready");
            ready.setCurrentContentVersion(TARGET);
            groupIds.add(deviceRepository.save(ready).getId());

            // Pending: on the target but a sync is still in-flight → NOT ready.
            Device pending = new Device(region, null, "SN-PENDING", "Pending");
            pending.setCurrentContentVersion(TARGET);
            pending.markSyncPending(TARGET);
            groupIds.add(deviceRepository.save(pending).getId());

            // Wrong version: still on the old content → NOT ready.
            Device wrongVersion = new Device(region, null, "SN-OLD", "Old");
            wrongVersion.setCurrentContentVersion("v-old");
            groupIds.add(deviceRepository.save(wrongVersion).getId());

            // Deleted-but-ready: has the target and no pending, but soft-deleted → excluded.
            Device deleted = new Device(region, null, "SN-DELETED", "Deleted");
            deleted.setCurrentContentVersion(TARGET);
            deleted.softDelete();
            groupIds.add(deviceRepository.save(deleted).getId());
        });
    }

    @Test
    void countReadyForVersion_countsOnlyConfirmedCurrentDevices_excludesPendingWrongAndDeleted() {
        long ready = deviceRepository.countReadyForVersion(groupIds, TARGET);
        assertEquals(1, ready, "only the current-version, no-pending, non-deleted device is ready");
    }

    @Test
    void countReadyForVersion_zeroWhenNoDeviceHasTheVersion() {
        assertEquals(0, deviceRepository.countReadyForVersion(groupIds, "v-never-shipped"));
    }
}
