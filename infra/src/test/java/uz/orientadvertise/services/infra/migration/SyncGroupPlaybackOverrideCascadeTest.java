package uz.orientadvertise.services.infra.migration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import uz.orientadvertise.services.infra.TestApplication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * V42 {@code sync_group_playback_override}: verifies the table applies on H2 PG-mode and that its
 * {@code ON DELETE CASCADE} FK removes the override when its sync group is hard-deleted (the group's
 * delete path relies on the cascade, not on application code), plus the one-override-per-group UNIQUE
 * and the group FK.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class SyncGroupPlaybackOverrideCascadeTest {

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
    void override_cascadesAway_whenSyncGroupHardDeleted() throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO project (id, name, created_at, updated_at) VALUES (900, 'P900', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO sync_group (id, project_id, name, created_at, updated_at) VALUES (900, 900, 'SG900', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            // No device points at this group, so the group's own delete is not blocked by fk_device_sync_group.
            stmt.execute("INSERT INTO sync_group_playback_override "
                    + "(id, sync_group_id, assignment_id, version_number, content_version, chosen_index, anchor_epoch_ms, activate_at, created_at, updated_at) "
                    + "VALUES (900, 900, 1, 1, 'v-1', 0, 123456789, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        }

        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM sync_group WHERE id = 900");
        }

        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM sync_group_playback_override WHERE id = 900")) {
            rs.next();
            assertEquals(0, rs.getInt(1),
                    "ON DELETE CASCADE must remove the override when its sync group is hard-deleted");
        }
    }

    @Test
    void override_oneRowPerSyncGroup_blocksSecond() throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO project (id, name, created_at, updated_at) VALUES (901, 'P901', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO sync_group (id, project_id, name, created_at, updated_at) VALUES (901, 901, 'SG901', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO sync_group_playback_override "
                    + "(id, sync_group_id, assignment_id, version_number, content_version, chosen_index, anchor_epoch_ms, activate_at, created_at, updated_at) "
                    + "VALUES (910, 901, 1, 1, 'v-1', 0, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        }

        assertThrows(SQLException.class, () -> {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO sync_group_playback_override "
                        + "(id, sync_group_id, assignment_id, version_number, content_version, chosen_index, anchor_epoch_ms, activate_at, created_at, updated_at) "
                        + "VALUES (911, 901, 2, 2, 'v-2', 1, 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            }
        }, "uq_sg_pb_override must allow at most one override row per sync group");
    }

    @Test
    void override_groupFk_enforced() {
        assertThrows(SQLException.class, () -> {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                // sync_group_id 999 does not exist → fk_sg_pb_override_group must reject the insert.
                stmt.execute("INSERT INTO sync_group_playback_override "
                        + "(id, sync_group_id, assignment_id, version_number, content_version, chosen_index, anchor_epoch_ms, activate_at, created_at, updated_at) "
                        + "VALUES (920, 999, 1, 1, 'v-1', 0, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            }
        }, "Inserting an override for a non-existent sync group must be blocked by fk_sg_pb_override_group");
    }
}
