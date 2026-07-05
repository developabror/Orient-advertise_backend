package uz.orientadvertise.services.infra.repository;

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
import uz.orientadvertise.services.domain.model.DeviceVolumeResolver;
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
 * Reproduces the exact transaction performed by {@code DeviceGroupManagementService.setVolume}:
 * mutate the managed {@link DeviceGroup#setVolume} (dirty, NOT yet flushed) and then run the
 * {@code @Modifying(clearAutomatically = true)} bulk-clear on the member devices — all in ONE
 * transaction.
 *
 * <p>The regression this guards: without {@code flushAutomatically = true} on
 * {@code bulkClearDesiredVolumeByGroup}, the {@code clearAutomatically = true} clear evicts the
 * dirty group <i>before</i> its volume change is flushed, so the new group volume is silently
 * discarded on commit. The members' overrides ARE cleared (direct SQL), so they then inherit the
 * STALE group volume — exactly the "set the group volume, nothing changes, it's stuck" symptom,
 * and the read side ({@code GET /api/device-groups/{id}}) keeps showing the stale value.
 *
 * <p>The pure-mock service unit test cannot catch this: it verifies the two calls happen but
 * never exercises the real persistence-context flush/clear interaction.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class DeviceGroupVolumePersistenceTest {

    private static final int STALE_VOLUME = 49;
    private static final int NEW_VOLUME = 86;

    @Autowired private DataSource dataSource;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private RegionRepository regionRepository;
    @Autowired private DeviceRepository deviceRepository;
    @Autowired private DeviceGroupRepository deviceGroupRepository;
    @Autowired private PlatformTransactionManager txManager;

    private Long groupId;
    private Long memberId;

    @BeforeEach
    void setUp() {
        var flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();

        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            Project project = projectRepository.save(new Project("Vol Group", null));
            Region region = regionRepository.save(new Region(project, "Region", "rg"));
            DeviceGroup group = new DeviceGroup(project, "G", null);
            group.setVolume(STALE_VOLUME);                 // group already has an old volume
            group = deviceGroupRepository.save(group);
            groupId = group.getId();

            Device member = new Device(region, null, "SN-MEMBER", "Member");
            member.setDeviceGroup(group);
            member.setDesiredVolume(STALE_VOLUME);         // a manual per-device override to be wiped
            member.recordReportedVolume(STALE_VOLUME);     // the device's last self-reported actual level
            memberId = deviceRepository.save(member).getId();
        });
    }

    /** Mirror {@code DeviceGroupManagementService.setVolume} in a single transaction. */
    private void applyGroupVolume(int volume) {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            DeviceGroup group = deviceGroupRepository.findById(groupId).orElseThrow();
            group.setVolume(volume);
            deviceRepository.bulkClearDesiredVolumeByGroup(groupId, java.time.Instant.now());
        });
    }

    @Test
    void setVolume_persistsNewGroupVolume_andClearsMemberOverride() {
        applyGroupVolume(NEW_VOLUME);

        // Fresh read after commit.
        Integer persistedGroupVolume =
                deviceGroupRepository.findById(groupId).orElseThrow().getVolume();
        Integer memberOverride =
                deviceRepository.findById(memberId).orElseThrow().getDesiredVolume();

        assertEquals(NEW_VOLUME, persistedGroupVolume,
                "Group volume change must survive the clearAutomatically bulk-clear in the same "
                        + "transaction (else members inherit the stale group volume and appear stuck)");
        assertNull(memberOverride,
                "Member per-device override must be cleared so it inherits the new group volume");
    }

    /**
     * Read-path assertion: after an apply, {@code GET /api/device-groups/{id}} must reflect the new
     * volume. This mirrors what {@code DeviceGroupDetail} / {@code DeviceSummary} project per device —
     * {@code volume} (group), {@code volumeOverride} (per-device), {@code effectiveVolume} (resolved),
     * and {@code reportedVolume} (device's actual level). The resolved {@code effectiveVolume} is the
     * exact computation {@code DeviceManagementService.effectiveVolumes} runs inside the read tx.
     */
    @Test
    void groupDetailRead_reflectsNewVolume_exceptReportedVolumeWhichLagsUntilHeartbeat() {
        applyGroupVolume(NEW_VOLUME);

        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            DeviceGroup group = deviceGroupRepository.findById(groupId).orElseThrow();
            Device member = deviceRepository.findById(memberId).orElseThrow();

            // DeviceGroupDetail.volume
            assertEquals(NEW_VOLUME, group.getVolume(),
                    "Group detail's `volume` must show the newly applied value");
            // DeviceSummary.volumeOverride
            assertNull(member.getDesiredVolume(),
                    "Per-device `volumeOverride` is cleared by a group apply (inherit)");
            // DeviceSummary.effectiveVolume = override ?? group.volume ?? default
            assertEquals(NEW_VOLUME, DeviceVolumeResolver.resolveEffectiveVolume(member),
                    "Resolved `effectiveVolume` must reflect the new group volume immediately");
            // DeviceSummary.reportedVolume — the device's ACTUAL level; lags by design until the
            // device applies the target on its next heartbeat and reports it back.
            assertEquals(STALE_VOLUME, member.getReportedVolume(),
                    "`reportedVolume` is the device's self-reported actual level and only changes "
                            + "after the device heartbeats — it is expected to lag the target");
        });
    }
}
