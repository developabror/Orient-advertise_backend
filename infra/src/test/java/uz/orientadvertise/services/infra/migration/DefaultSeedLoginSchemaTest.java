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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V45 (AUTH-01): deactivate every account still on the public V33 seed hash.
 *
 * <p>Runs the real upgrade shape — a database already at V44 receives V45 alone — so the
 * "accounts with a changed password are untouched" guarantee is proven against rows that
 * existed before the migration, not against a clean chain.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class DefaultSeedLoginSchemaTest {

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void migrateToV44() {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target("44").cleanDisabled(false).load().migrate();
    }

    private void migrateToLatest() {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .cleanDisabled(false).load().migrate();
    }

    private void exec(String sql) throws SQLException {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    private boolean isActive(String username) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             var rs = conn.createStatement().executeQuery(
                     "SELECT is_active FROM app_user WHERE username = '" + username + "'")) {
            assertTrue(rs.next(), "user must exist: " + username);
            return rs.getBoolean("is_active");
        }
    }

    @Test
    void v45_deactivatesEverySeedAccountStillOnTheDefaultHash() throws Exception {
        assertTrue(isActive("admin"), "precondition: V12/V33 leave admin active at V44");

        migrateToLatest();

        assertFalse(isActive("developabror@gmail.com"));
        assertFalse(isActive("admin"));
        assertFalse(isActive("operator"));
        assertFalse(isActive("viewer"));
        assertFalse(isActive("advertiser"));
        assertFalse(isActive("deactivated"), "was inactive before V45 and must stay inactive");
    }

    @Test
    void v45_leavesAccountsWithAChangedPasswordActive() throws Exception {
        // A seed account whose owner already changed the password, and an ordinary user.
        exec("UPDATE app_user SET password = '$2a$10$someOtherHashValueXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX' "
                + "WHERE username = 'developabror@gmail.com'");
        exec("INSERT INTO app_user (username, password, role, is_active) "
                + "VALUES ('alice', '$2a$10$aliceHashValueXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX', 'OPERATOR', TRUE)");

        migrateToLatest();

        assertTrue(isActive("developabror@gmail.com"), "changed password → not a default login");
        assertTrue(isActive("alice"), "ordinary user must be untouched");
        assertFalse(isActive("admin"), "untouched seed account in the same run is still deactivated");
    }
}
