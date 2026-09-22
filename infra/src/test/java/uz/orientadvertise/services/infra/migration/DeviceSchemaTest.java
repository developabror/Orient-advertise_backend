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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class DeviceSchemaTest {

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        var flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();

        // Seed project and region
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO project (id, name, created_at, updated_at) VALUES (100, 'TestProj', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO region (id, project_id, name, code, created_at, updated_at) VALUES (100, 100, 'TestRegion', 'TR', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        }
    }

    @Test
    void serialNumber_globallyUnique_amongActiveDevices() throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO device (region_id, serial_number, name, status, created_at, updated_at) VALUES (100, 'SN-UNIQUE-1', 'D1', 'ONLINE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        }

        // Same serial_number with deleted_at = NULL → blocked
        assertThrows(SQLException.class, () -> {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO device (region_id, serial_number, name, status, created_at, updated_at) VALUES (100, 'SN-UNIQUE-1', 'D2', 'OFFLINE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            }
        }, "Duplicate serial_number among non-deleted devices must be blocked");
    }

    /**
     * G-6 (V50): a soft-deleted device must not block its serial. It used to — the box behind an
     * admin-deleted device got a 500 from /register on every boot, forever.
     */
    @Test
    void serialNumber_ofSoftDeletedDevice_canBeRegisteredAgain() throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO device (region_id, serial_number, name, status, created_at, updated_at, deleted_at) VALUES (100, 'SN-REUSE', 'D1', 'OFFLINE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO device (region_id, serial_number, name, status, created_at, updated_at) VALUES (100, 'SN-REUSE', 'D2', 'ONLINE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");

            try (var rs = stmt.executeQuery("SELECT COUNT(*) FROM device WHERE serial_number = 'SN-REUSE'")) {
                rs.next();
                assertEquals(2, rs.getInt(1), "the deleted row keeps its serial for history");
            }
        }
    }

    @Test
    void serialNumber_deletingTheLiveDevice_frees_andADeletedDuplicateCannotComeBackAlive() throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO device (id, region_id, serial_number, name, status, created_at, updated_at) VALUES (501, 100, 'SN-CYCLE', 'D1', 'ONLINE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("UPDATE device SET deleted_at = CURRENT_TIMESTAMP WHERE id = 501");
            stmt.execute("INSERT INTO device (id, region_id, serial_number, name, status, created_at, updated_at) VALUES (502, 100, 'SN-CYCLE', 'D2', 'ONLINE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        }

        // Un-deleting 501 would make two live devices with one serial — the rule still holds.
        assertThrows(SQLException.class, () -> {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("UPDATE device SET deleted_at = NULL WHERE id = 501");
            }
        }, "Two live devices must never share a serial");
    }

    @Test
    void softDelete_deviceIsNotPhysicallyRemoved() throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO device (id, region_id, serial_number, name, status, created_at, updated_at) VALUES (999, 100, 'SN-SOFT', 'D1', 'ONLINE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            // Soft delete = set deleted_at
            stmt.execute("UPDATE device SET deleted_at = CURRENT_TIMESTAMP WHERE id = 999");
        }

        // Device still exists in table
        try (Connection conn = dataSource.getConnection();
             var rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM device WHERE id = 999")) {
            rs.next();
            assert rs.getInt(1) == 1 : "Soft-deleted device must still exist in table";
        }
    }

    @Test
    void deviceGroup_softDeleteSupported() throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO device_group (id, project_id, name, created_at, updated_at) VALUES (1, 100, 'Group1', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("UPDATE device_group SET deleted_at = CURRENT_TIMESTAMP WHERE id = 1");
        }

        try (Connection conn = dataSource.getConnection();
             var rs = conn.createStatement().executeQuery("SELECT deleted_at FROM device_group WHERE id = 1")) {
            rs.next();
            assert rs.getTimestamp("deleted_at") != null : "Device group must support soft delete";
        }
    }

    @Test
    void deviceStatus_acceptsNewEnumValues() throws Exception {
        assertDoesNotThrow(() -> {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO device (region_id, serial_number, name, status, created_at, updated_at) VALUES (100, 'SN-ONLINE', 'D-Online', 'ONLINE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
                stmt.execute("INSERT INTO device (region_id, serial_number, name, status, created_at, updated_at) VALUES (100, 'SN-OFFLINE', 'D-Offline', 'OFFLINE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
                stmt.execute("INSERT INTO device (region_id, serial_number, name, status, created_at, updated_at) VALUES (100, 'SN-NC', 'D-NoContent', 'NO_CONTENT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
                stmt.execute("INSERT INTO device (region_id, serial_number, name, status, created_at, updated_at) VALUES (100, 'SN-UNREG', 'D-Unreg', 'UNREGISTERED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            }
        });
    }

    // ---- V38 device volume control schema ----------------------------------

    @Test
    void v38_volumeColumns_exist() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            var md = conn.getMetaData();
            assertColumnExists(md, "DEVICE", "DESIRED_VOLUME");
            assertColumnExists(md, "DEVICE", "REPORTED_VOLUME");
            assertColumnExists(md, "DEVICE", "VOLUME_REPORTED_AT");
            assertColumnExists(md, "DEVICE_GROUP", "VOLUME");
        }
    }

    @Test
    void v38_check_acceptsNullAndInRangeVolumes() throws Exception {
        assertDoesNotThrow(() -> {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                // NULL desired/reported volume = inherit / not yet reported
                stmt.execute("INSERT INTO device (region_id, serial_number, name, status, created_at, updated_at, desired_volume, reported_volume) "
                        + "VALUES (100, 'SN-VOL-NULL', 'D-Null', 'ONLINE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL, NULL)");
                // In-range boundaries: 0, 50, 100
                stmt.execute("INSERT INTO device (region_id, serial_number, name, status, created_at, updated_at, desired_volume, reported_volume) "
                        + "VALUES (100, 'SN-VOL-RANGE', 'D-Range', 'ONLINE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 50, 0)");
                stmt.execute("INSERT INTO device (region_id, serial_number, name, status, created_at, updated_at, desired_volume, reported_volume, volume_reported_at) "
                        + "VALUES (100, 'SN-VOL-MAX', 'D-Max', 'ONLINE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 100, 100, CURRENT_TIMESTAMP)");
                // device_group volume in range and NULL both accepted
                stmt.execute("INSERT INTO device_group (id, project_id, name, created_at, updated_at, volume) "
                        + "VALUES (50, 100, 'G-Vol', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 100)");
                stmt.execute("UPDATE device_group SET volume = NULL WHERE id = 50");
            }
        });
    }

    @Test
    void v38_check_rejectsDesiredVolumeAboveMax() throws Exception {
        assertThrows(SQLException.class, () -> {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO device (region_id, serial_number, name, status, created_at, updated_at, desired_volume) "
                        + "VALUES (100, 'SN-DV-HI', 'D-DvHi', 'ONLINE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 150)");
            }
        }, "desired_volume=150 must violate chk_device_desired_volume");
    }

    @Test
    void v38_check_rejectsDesiredVolumeBelowMin() throws Exception {
        assertThrows(SQLException.class, () -> {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO device (region_id, serial_number, name, status, created_at, updated_at, desired_volume) "
                        + "VALUES (100, 'SN-DV-LO', 'D-DvLo', 'ONLINE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, -1)");
            }
        }, "desired_volume=-1 must violate chk_device_desired_volume");
    }

    @Test
    void v38_check_rejectsReportedVolumeOutOfRange() throws Exception {
        assertThrows(SQLException.class, () -> {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO device (region_id, serial_number, name, status, created_at, updated_at, reported_volume) "
                        + "VALUES (100, 'SN-RV-HI', 'D-RvHi', 'ONLINE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 200)");
            }
        }, "reported_volume=200 must violate chk_device_reported_volume");
    }

    @Test
    void v38_check_rejectsDeviceGroupVolumeOutOfRange() throws Exception {
        assertThrows(SQLException.class, () -> {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO device_group (id, project_id, name, created_at, updated_at, volume) "
                        + "VALUES (60, 100, 'G-VolBad', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 101)");
            }
        }, "device_group.volume=101 must violate chk_device_group_volume");
    }

    private static void assertColumnExists(java.sql.DatabaseMetaData md, String table, String column) throws SQLException {
        try (var rs = md.getColumns(null, null, table, column)) {
            assert rs.next() : table + "." + column + " column must exist after V38";
        }
    }
}
