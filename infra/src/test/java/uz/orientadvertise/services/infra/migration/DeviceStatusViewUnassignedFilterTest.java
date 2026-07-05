package uz.orientadvertise.services.infra.migration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import uz.orientadvertise.services.domain.repository.DeviceStatusViewRepository;
import uz.orientadvertise.services.infra.TestApplication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for the {@code unassigned=true} list filter wired through
 * {@link DeviceStatusViewRepository#findFiltered}. Seeds a region with both grouped
 * and ungrouped devices, then asserts only the ungrouped rows survive the filter.
 *
 * <p>Lives in the infra module because it's the only place wired with
 * {@code @SpringBootTest} + Flyway + the H2 view DDL — the {@code device_status_view}
 * is created by a real Flyway migration, so a {@code @DataJpaTest} or pure mock
 * approach would not exercise the actual SQL view path.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class DeviceStatusViewUnassignedFilterTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private DeviceStatusViewRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        var flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();

        // Region 200 will hold our test fixture. Project + region are needed because
        // the device_status_view joins on these. Two device groups belong to the same
        // PROJECT (200) now that groups bind to project, not region; half of the
        // devices live in each group, the other half are ungrouped.
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO project (id, name, created_at, updated_at) "
                    + "VALUES (200, 'IntegProj', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO region (id, project_id, name, code, created_at, updated_at) "
                    + "VALUES (200, 200, 'IntegRegion', 'IR', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            // project_id 200 is the PROJECT id (matches the project seeded above), not the region.
            stmt.execute("INSERT INTO device_group (id, project_id, name, created_at, updated_at) "
                    + "VALUES (200, 200, 'GroupA', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO device_group (id, project_id, name, created_at, updated_at) "
                    + "VALUES (201, 200, 'GroupB', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");

            // Two grouped devices (one per group) and three ungrouped — last_heartbeat_at
            // is set to "now" so the view computes a stable ONLINE status for all five.
            stmt.execute("INSERT INTO device (id, region_id, device_group_id, serial_number, name, "
                    + "status, created_at, updated_at, last_heartbeat_at) "
                    + "VALUES (2001, 200, 200, 'SN-G-1', 'Grouped-1', 'ONLINE', "
                    + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO device (id, region_id, device_group_id, serial_number, name, "
                    + "status, created_at, updated_at, last_heartbeat_at) "
                    + "VALUES (2002, 200, 201, 'SN-G-2', 'Grouped-2', 'ONLINE', "
                    + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO device (id, region_id, serial_number, name, status, "
                    + "created_at, updated_at, last_heartbeat_at) "
                    + "VALUES (2003, 200, 'SN-U-1', 'Ungrouped-1', 'ONLINE', "
                    + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO device (id, region_id, serial_number, name, status, "
                    + "created_at, updated_at, last_heartbeat_at) "
                    + "VALUES (2004, 200, 'SN-U-2', 'Ungrouped-2', 'ONLINE', "
                    + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO device (id, region_id, serial_number, name, status, "
                    + "created_at, updated_at, last_heartbeat_at) "
                    + "VALUES (2005, 200, 'SN-U-3', 'Ungrouped-3', 'ONLINE', "
                    + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        }
    }

    @Test
    void findFiltered_unassignedTrue_returnsOnlyUngroupedDevicesInRegion() {
        var page = repository.findFiltered(
                null,             // status
                200L,             // regionId
                null,             // projectId — unset
                null,             // facilityId
                null,             // deviceGroupId
                Boolean.TRUE,     // unassigned — under test
                null, null, null, // serial, name, facilityName
                null,             // hasActivePlaylist — unset
                null,             // projectIds — unrestricted
                PageRequest.of(0, 50));

        var serials = page.getContent().stream()
                .map(v -> v.getSerialNumber())
                .sorted()
                .toList();
        assertEquals(List.of("SN-U-1", "SN-U-2", "SN-U-3"), serials,
                "unassigned=true must return ONLY rows with device_group_id IS NULL");
        page.getContent().forEach(v ->
                assertTrue(v.getDeviceGroupId() == null,
                        "Every returned row must have a null deviceGroupId; got "
                                + v.getDeviceGroupId() + " for " + v.getSerialNumber()));
    }

    @Test
    void findFiltered_unassignedNull_returnsBothGroupedAndUngrouped() {
        var page = repository.findFiltered(
                null, 200L, null, null, null,
                null,             // unassigned unset → no filter
                null, null, null,
                null,             // hasActivePlaylist — unset
                null,             // projectIds — unrestricted
                PageRequest.of(0, 50));

        assertEquals(5, page.getContent().size(),
                "unassigned=null must NOT filter — all 5 region-200 devices return");
    }

    @Test
    void findFiltered_unassignedFalse_returnsBothGroupedAndUngrouped() {
        var page = repository.findFiltered(
                null, 200L, null, null, null,
                Boolean.FALSE,    // explicit false → no filter
                null, null, null,
                null,             // hasActivePlaylist — unset
                null,             // projectIds — unrestricted
                PageRequest.of(0, 50));

        assertEquals(5, page.getContent().size(),
                "unassigned=false must behave like unset — no filter applied");
    }
}
