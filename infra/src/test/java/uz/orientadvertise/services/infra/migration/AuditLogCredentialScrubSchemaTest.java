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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V47 (AUTH-06): scrub credentials that older AuditFilter versions wrote into audit_log. Runs the
 * real upgrade shape — rows written at V46 receive V47 — and checks both what is cleared and what
 * must survive (ordinary rows, the registration request trail, and responses that never held secrets).
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class AuditLogCredentialScrubSchemaTest {

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void migrateToV46() {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target("46").cleanDisabled(false).load().migrate();
    }

    private void insert(long id, String method, String path, int status, String req, String res) throws SQLException {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO audit_log (id, principal, http_method, path, response_status, request_body, "
                    + "response_body, timestamp) VALUES (" + id + ", 'p', '" + method + "', '" + path + "', " + status
                    + ", '" + req + "', '" + res + "', CURRENT_TIMESTAMP)");
        }
    }

    private String[] bodies(long id) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             var rs = conn.createStatement().executeQuery(
                     "SELECT request_body, response_body FROM audit_log WHERE id = " + id)) {
            assertTrue(rs.next());
            return new String[] {rs.getString(1), rs.getString(2)};
        }
    }

    private void migrateToLatest() {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .cleanDisabled(false).load().migrate();
    }

    @Test
    void v47_clearsCredentialBodies_andKeepsTheRest() throws Exception {
        insert(1, "POST", "/api/me/password", 204, "{\"newPassword\":\"s3cret\"}", "x");
        insert(2, "POST", "/api/admin/api-keys", 201, "{\"name\":\"k\"}", "{\"rawKey\":\"live-key\"}");
        insert(3, "POST", "/api/auth/reset-password", 204, "{\"newPassword\":\"s3cret\"}", "x");
        insert(4, "POST", "/api/devices/register", 200, "{\"serialNumber\":\"SN-1\"}", "{\"deviceToken\":\"dtk_live\"}");
        insert(5, "POST", "/api/users", 400, "{\"username\":\"u\"}", "{\"rejectedValue\":\"abc\"}");
        insert(6, "POST", "/api/users", 201, "{\"username\":\"u\"}", "{\"id\":6}");
        insert(7, "PUT", "/api/devices/1", 200, "{\"name\":\"TV\"}", "{\"id\":1}");
        insert(8, "POST", "/api/auth/refresh", 200, "x", "{\"accessToken\":\"***REDACTED***\"}");
        insert(9, "POST", "/api/auth/login", 400, "{\"username\":\"bob\",\"password\":\"***REDACTED***\"}",
                "{\"rejectedValue\":\"abc\"}");
        // The old regex stopped at an escaped quote: the tail of the password stayed in the row, and
        // the backslash went with the masked part — nothing marks the row as damaged.
        insert(10, "POST", "/api/auth/login", 401,
                "{\"username\":\"eve\",\"password\":\"***REDACTED***\"adour!\"}", "{}");
        // ...and never matched a numeric password.
        insert(11, "POST", "/api/users", 201, "{\"username\":\"n\",\"password\":12345678}", "{\"id\":11}");
        // Even a cleanly masked historical login can't be told apart from row 10, so it is cleared too.
        insert(12, "POST", "/api/auth/login", 200, "{\"username\":\"alice\",\"password\":\"***REDACTED***\"}", "{}");
        // Only POSTs are credential rows.
        insert(13, "PUT", "/api/me/password", 405, "{\"note\":\"kept\"}", "{}");

        migrateToLatest();

        for (long id : new long[] {1, 2, 3}) {
            assertNull(bodies(id)[0], "request body of credential row " + id);
            assertNull(bodies(id)[1], "response body of credential row " + id);
        }
        assertEquals("{\"serialNumber\":\"SN-1\"}", bodies(4)[0], "the registration trail survives");
        assertNull(bodies(4)[1], "the live device token is gone");
        assertNull(bodies(5)[1], "a rejected password echo is gone");
        assertNull(bodies(5)[0], "historical user-create requests are cleared");
        assertEquals("{\"id\":6}", bodies(6)[1], "a successful create keeps its (masked) response");
        assertEquals("{\"name\":\"TV\"}", bodies(7)[0], "ordinary rows are untouched");
        assertEquals("{\"id\":1}", bodies(7)[1]);
        assertNull(bodies(8)[1], "refresh responses carried access tokens");
        assertNull(bodies(9)[1], "a login 400 echoed the password");
        assertNull(bodies(10)[0], "a half-masked password is cleared");
        assertNull(bodies(11)[0], "a numeric password is cleared");
        assertNull(bodies(12)[0]);
        assertEquals("{}", bodies(12)[1], "a successful login's response is kept");
        assertEquals("{\"note\":\"kept\"}", bodies(13)[0], "the POST guard holds");
    }
}
