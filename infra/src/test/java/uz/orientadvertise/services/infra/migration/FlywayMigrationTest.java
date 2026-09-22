package uz.orientadvertise.services.infra.migration;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import uz.orientadvertise.services.infra.TestApplication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class FlywayMigrationTest {

    /** Total applied migrations: V1..V48 inclusive (V36 is the Java migration). */
    private static final int EXPECTED_MIGRATIONS = 51;

    @Autowired
    private DataSource dataSource;

    private Flyway flyway;

    @BeforeEach
    void setUp() {
        flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
    }

    @Test
    void allMigrations_applySuccessfully() {
        MigrationInfoService info = flyway.info();
        MigrationInfo[] applied = info.applied();

        assertNotNull(applied);
        // Bump on EVERY new migration. The count includes the Java migrations V36, V50 and V51
        // (no V*.sql on disk for them), and the loop pins the versions as a contiguous 1..N run so an
        // out-of-order or skipped version fails loudly here rather than on a prod deploy.
        assertEquals(EXPECTED_MIGRATIONS, applied.length);
        for (int i = 0; i < EXPECTED_MIGRATIONS; i++) {
            assertEquals(String.valueOf(i + 1), applied[i].getVersion().toString());
        }
    }

    @Test
    void allMigrations_haveNoFailures() {
        MigrationInfoService info = flyway.info();

        for (MigrationInfo migration : info.applied()) {
            assertTrue(migration.getState().isApplied(),
                    "Migration %s should be applied but is %s".formatted(
                            migration.getVersion(), migration.getState()));
        }
    }

    @Test
    void noPendingMigrations_afterFullMigration() {
        MigrationInfo[] pending = flyway.info().pending();
        assertEquals(0, pending.length, "There should be no pending migrations");
    }
}
