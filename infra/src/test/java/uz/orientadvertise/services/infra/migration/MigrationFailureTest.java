package uz.orientadvertise.services.infra.migration;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import uz.orientadvertise.services.infra.TestApplication;

import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class MigrationFailureTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void brokenMigration_throwsFlywayException() {
        var flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/broken")
                .cleanDisabled(false)
                .load();
        flyway.clean();

        assertThrows(FlywayException.class, flyway::migrate,
                "A broken migration must throw FlywayException, halting startup");
    }

    @Test
    void missingMigrationLocation_throwsFlywayException() {
        assertThrows(FlywayException.class, () -> {
            Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration/nonexistent")
                    .failOnMissingLocations(true)
                    .load()
                    .migrate();
        }, "Missing migration location must throw FlywayException");
    }
}
