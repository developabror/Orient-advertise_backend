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
class PlaylistSchemaTest {

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

        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO project (id, name, created_at, updated_at) VALUES (200, 'ContentProj', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO content_file (id, project_id, name, content_type, size_bytes, storage_key, created_at, updated_at) VALUES (1, 200, 'video.mp4', 'video/mp4', 1024, 'uploads/v1.mp4', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO content_file (id, project_id, name, content_type, size_bytes, storage_key, created_at, updated_at) VALUES (2, 200, 'image.png', 'image/png', 512, 'uploads/i1.png', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO playlist (id, project_id, name, created_at, updated_at) VALUES (1, 200, 'Main Playlist', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        }
    }

    @Test
    void position_uniqueWithinPlaylist() throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO playlist_item (playlist_id, content_file_id, position, created_at) VALUES (1, 1, 0, CURRENT_TIMESTAMP)");
        }

        assertThrows(SQLException.class, () -> {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO playlist_item (playlist_id, content_file_id, position, created_at) VALUES (1, 2, 0, CURRENT_TIMESTAMP)");
            }
        }, "Two items at the same position in one playlist must be blocked");
    }

    @Test
    void position_samePositionDifferentPlaylist_allowed() throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO playlist (id, project_id, name, created_at, updated_at) VALUES (2, 200, 'Second Playlist', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO playlist_item (playlist_id, content_file_id, position, created_at) VALUES (1, 1, 0, CURRENT_TIMESTAMP)");
        }

        assertDoesNotThrow(() -> {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO playlist_item (playlist_id, content_file_id, position, created_at) VALUES (2, 1, 0, CURRENT_TIMESTAMP)");
            }
        });
    }

    @Test
    void playlistNameUniquePerProject() throws Exception {
        assertThrows(SQLException.class, () -> {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO playlist (id, project_id, name, created_at, updated_at) VALUES (3, 200, 'Main Playlist', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            }
        }, "Duplicate playlist name within same project must be blocked");
    }

    @Test
    void deletingPlaylist_cascadesDeleteToItems() throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO playlist_item (playlist_id, content_file_id, position, created_at) VALUES (1, 1, 0, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO playlist_item (playlist_id, content_file_id, position, created_at) VALUES (1, 2, 1, CURRENT_TIMESTAMP)");
        }

        assertDoesNotThrow(() -> {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM playlist WHERE id = 1");
            }
        });

        try (Connection conn = dataSource.getConnection();
             var rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM playlist_item WHERE playlist_id = 1")) {
            rs.next();
            assert rs.getInt(1) == 0 : "Playlist items must be cascade-deleted when playlist is deleted";
        }
    }
}
