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

/**
 * Schema test for V39 (app_user.email). Mirrors {@link DeviceSchemaTest}: a clean Flyway
 * migrate, then assert the column exists, the UNIQUE constraint blocks duplicates, and
 * multiple NULLs are allowed (existing users keep NULL email — no backfill).
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class AppUserEmailSchemaTest {

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
    void emailColumn_exists() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            var md = conn.getMetaData();
            try (var rs = md.getColumns(null, null, "APP_USER", "EMAIL")) {
                assert rs.next() : "app_user.email column must exist after V39";
            }
        }
    }

    @Test
    void email_unique_blocksDuplicate() throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO app_user (username, password, role, is_active, email) "
                    + "VALUES ('email-u1', 'x', 'VIEWER', TRUE, 'dup@example.com')");
        }

        assertThrows(SQLException.class, () -> {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO app_user (username, password, role, is_active, email) "
                        + "VALUES ('email-u2', 'x', 'VIEWER', TRUE, 'dup@example.com')");
            }
        }, "Duplicate app_user.email must violate ux_app_user_email");
    }

    @Test
    void email_allowsMultipleNulls() {
        assertDoesNotThrow(() -> {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO app_user (username, password, role, is_active, email) "
                        + "VALUES ('email-n1', 'x', 'VIEWER', TRUE, NULL)");
                stmt.execute("INSERT INTO app_user (username, password, role, is_active, email) "
                        + "VALUES ('email-n2', 'x', 'VIEWER', TRUE, NULL)");
            }
        });
    }
}
