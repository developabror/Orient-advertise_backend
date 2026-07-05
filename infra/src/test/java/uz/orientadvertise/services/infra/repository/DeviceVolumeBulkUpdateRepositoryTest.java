package uz.orientadvertise.services.infra.repository;

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
import uz.orientadvertise.services.domain.model.DeviceGroup;
import uz.orientadvertise.services.domain.model.Project;
import uz.orientadvertise.services.domain.model.Region;
import uz.orientadvertise.services.domain.repository.DeviceGroupRepository;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.ProjectRepository;
import uz.orientadvertise.services.domain.repository.RegionRepository;
import uz.orientadvertise.services.infra.TestApplication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Exercises the real {@code DeviceRepository.bulkSetDesiredVolume} @Modifying JPQL against H2
 * (PostgreSQL mode) under the full Flyway schema — the "apply volume to all" path. Mock service
 * tests can't catch a runtime JPQL fault, in particular the {@code :projectIds IS NULL} unrestricted
 * branch (a null collection parameter combined with {@code IN :projectIds}) and the
 * soft-deleted exclusion. The @Modifying call needs an ambient transaction, supplied here via
 * {@link TransactionTemplate} (in production it's the {@code @Transactional setVolumeForAll}).
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class DeviceVolumeBulkUpdateRepositoryTest {

    @Autowired private DataSource dataSource;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private RegionRepository regionRepository;
    @Autowired private DeviceRepository deviceRepository;
    @Autowired private DeviceGroupRepository deviceGroupRepository;
    @Autowired private PlatformTransactionManager txManager;

    private Long projectAId;
    private Long devA1;
    private Long devA2;
    private Long devB1;
    private Long devDeleted;

    @BeforeEach
    void setUp() {
        var flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();

        Project a = projectRepository.save(new Project("Vol A", null));
        Project b = projectRepository.save(new Project("Vol B", null));
        projectAId = a.getId();
        Region ra = regionRepository.save(new Region(a, "Region A", "ra"));
        Region rb = regionRepository.save(new Region(b, "Region B", "rb"));

        devA1 = deviceRepository.save(new Device(ra, null, "SN-A1", "A1")).getId();
        devA2 = deviceRepository.save(new Device(ra, null, "SN-A2", "A2")).getId();
        devB1 = deviceRepository.save(new Device(rb, null, "SN-B1", "B1")).getId();
        Device deleted = deviceRepository.save(new Device(ra, null, "SN-DEL", "DEL"));
        deleted.softDelete();
        deviceRepository.save(deleted);
        devDeleted = deleted.getId();
    }

    private int bulkSet(int volume, List<Long> projectIds) {
        return new TransactionTemplate(txManager)
                .execute(s -> deviceRepository.bulkSetDesiredVolume(volume, java.time.Instant.now(), projectIds));
    }

    private Integer desiredVolumeOf(Long id) {
        return deviceRepository.findById(id).orElseThrow().getDesiredVolume();
    }

    @Test
    void unrestricted_nullProjectIds_updatesAllActiveDevices_skipsSoftDeleted() {
        int affected = bulkSet(50, null);

        assertEquals(3, affected, "all 3 active devices updated; soft-deleted excluded");
        assertEquals(50, desiredVolumeOf(devA1).intValue());
        assertEquals(50, desiredVolumeOf(devA2).intValue());
        assertEquals(50, desiredVolumeOf(devB1).intValue());
        assertNull(desiredVolumeOf(devDeleted), "soft-deleted device must not be touched");
    }

    @Test
    void scoped_toProjectA_updatesOnlyThatProjectsActiveDevices() {
        int affected = bulkSet(30, List.of(projectAId));

        assertEquals(2, affected, "only project A's 2 active devices");
        assertEquals(30, desiredVolumeOf(devA1).intValue());
        assertEquals(30, desiredVolumeOf(devA2).intValue());
        assertNull(desiredVolumeOf(devB1), "project B device unaffected by project-A-scoped update");
    }

    @Test
    void clearByGroup_nullsOverridesForActiveMembersOnly_skipsOtherGroupsAndSoftDeleted() {
        // Two active members of group G plus one soft-deleted member; devB1 stays ungrouped.
        // Seed per-device overrides on all three of project A's devices, then apply to the group.
        Long groupId = new TransactionTemplate(txManager).execute(s -> {
            DeviceGroup g = deviceGroupRepository.save(
                    new DeviceGroup(projectRepository.findById(projectAId).orElseThrow(), "G", null));
            attachToGroup(devA1, g.getId());
            attachToGroup(devA2, g.getId());
            attachToGroup(devDeleted, g.getId());
            return g.getId();
        });
        bulkSet(70, null); // every active device now has an override (devDeleted excluded)

        int cleared = new TransactionTemplate(txManager)
                .execute(s -> deviceRepository.bulkClearDesiredVolumeByGroup(groupId, java.time.Instant.now()));

        assertEquals(2, cleared, "only the 2 active group members are cleared");
        assertNull(desiredVolumeOf(devA1), "group member override cleared → inherits group volume");
        assertNull(desiredVolumeOf(devA2), "group member override cleared → inherits group volume");
        assertEquals(70, desiredVolumeOf(devB1).intValue(), "device outside the group keeps its override");
    }

    private void attachToGroup(Long deviceId, Long groupId) {
        Device d = deviceRepository.findById(deviceId).orElseThrow();
        d.setDeviceGroup(deviceGroupRepository.findById(groupId).orElseThrow());
        deviceRepository.save(d);
    }
}
