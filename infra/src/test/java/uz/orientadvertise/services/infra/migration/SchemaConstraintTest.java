package uz.orientadvertise.services.infra.migration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import uz.orientadvertise.services.infra.TestApplication;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class SchemaConstraintTest {

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void setUp() {
        var flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
    }

    @Test
    void deleteRegion_withActiveDevice_isBlocked() throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO project (id, name, created_at, updated_at) VALUES (1, 'P1', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO region (id, project_id, name, code, created_at, updated_at) VALUES (1, 1, 'R1', 'r1', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO device (id, region_id, serial_number, name, status, created_at, updated_at) VALUES (1, 1, 'SN-001', 'D1', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        }

        assertThrows(SQLException.class, () -> {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM region WHERE id = 1");
            }
        }, "Deleting region with active devices must be blocked by FK RESTRICT");
    }

    @Test
    void deleteRegion_withNoDevices_succeeds() throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO project (id, name, created_at, updated_at) VALUES (2, 'P2', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO region (id, project_id, name, code, created_at, updated_at) VALUES (2, 2, 'R2', 'r2', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        }

        assertDoesNotThrow(() -> {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM region WHERE id = 2");
            }
        });
    }

    @Test
    void facilityName_uniqueWithinRegion_blocksduplicates() throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO project (id, name, created_at, updated_at) VALUES (3, 'P3', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO region (id, project_id, name, code, created_at, updated_at) VALUES (3, 3, 'R3', 'r3', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO facility (id, region_id, name, created_at, updated_at) VALUES (1, 3, 'Facility A', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        }

        assertThrows(SQLException.class, () -> {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO facility (id, region_id, name, created_at, updated_at) VALUES (2, 3, 'Facility A', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            }
        }, "Duplicate facility name within same region must be blocked");
    }

    @Test
    void facilityName_sameNameDifferentRegion_allowed() throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO project (id, name, created_at, updated_at) VALUES (4, 'P4', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO region (id, project_id, name, code, created_at, updated_at) VALUES (4, 4, 'R4', 'r4', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO region (id, project_id, name, code, created_at, updated_at) VALUES (5, 4, 'R5', 'r5', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO facility (id, region_id, name, created_at, updated_at) VALUES (3, 4, 'Same Name', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        }

        assertDoesNotThrow(() -> {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO facility (id, region_id, name, created_at, updated_at) VALUES (4, 5, 'Same Name', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            }
        }, "Same facility name in different regions must be allowed");
    }

    @Test
    void deviceGroupName_uniqueWithinProject_blocksDuplicates() throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO project (id, name, created_at, updated_at) VALUES (100, 'P100', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO device_group (id, project_id, name, created_at, updated_at) VALUES (200, 100, 'Group A', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        }

        assertThrows(SQLException.class, () -> {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO device_group (id, project_id, name, created_at, updated_at) VALUES (201, 100, 'Group A', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            }
        }, "Duplicate device group name within same project must be blocked by uq_device_group_name_per_project");
    }

    @Test
    void deviceGroupName_sameNameDifferentProject_allowed() throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO project (id, name, created_at, updated_at) VALUES (101, 'P101', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO project (id, name, created_at, updated_at) VALUES (102, 'P102', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO device_group (id, project_id, name, created_at, updated_at) VALUES (202, 101, 'Same Group', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        }

        assertDoesNotThrow(() -> {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO device_group (id, project_id, name, created_at, updated_at) VALUES (203, 102, 'Same Group', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            }
        }, "Same device group name under different projects must be allowed");
    }

    @Test
    void deviceGroupName_sameNameSameProjectDifferentRegions_blocked() throws Exception {
        // KEY PIVOT: device_group is now bound to PROJECT only (it has no region_id column anymore).
        // Two groups sharing the same (project_id, name) collide regardless of any device's region —
        // i.e. groups are unique per PROJECT, not per region. We seed two regions under ONE project
        // to make the intent explicit, but the group rows reference the project (not the regions);
        // the second same-named insert under the same project must still be rejected. This proves
        // project-level (not region-level) uniqueness.
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO project (id, name, created_at, updated_at) VALUES (103, 'P103', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO region (id, project_id, name, code, created_at, updated_at) VALUES (103, 103, 'R103', 'r103', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO region (id, project_id, name, code, created_at, updated_at) VALUES (104, 103, 'R104', 'r104', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO device_group (id, project_id, name, created_at, updated_at) VALUES (204, 103, 'Lobby', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        }

        assertThrows(SQLException.class, () -> {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                // Same (project_id, name) as group 204, even though two distinct regions exist in the project.
                stmt.execute("INSERT INTO device_group (id, project_id, name, created_at, updated_at) VALUES (205, 103, 'Lobby', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            }
        }, "Same device group name within one project must be blocked even across regions (project-level uniqueness)");
    }

    @Test
    void deviceGroup_projectFk_enforced() throws Exception {
        assertThrows(SQLException.class, () -> {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                // project_id 999 does not exist -> fk_device_group_project must reject the insert.
                stmt.execute("INSERT INTO device_group (id, project_id, name, created_at, updated_at) VALUES (206, 999, 'Orphan Group', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            }
        }, "Inserting device_group with a non-existent project_id must be blocked by fk_device_group_project");
    }
}
