package uz.orientadvertise.services.infra.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;

import uz.orientadvertise.services.infra.TestApplication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class DataSourceConfigTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void dataSource_isHikariDataSource() {
        assertInstanceOf(HikariDataSource.class, dataSource);
    }

    @Test
    void hikariPool_hasConfiguredProperties() {
        var hikari = (HikariDataSource) dataSource;

        assertNotNull(hikari.getPoolName());
        assertEquals("infra-test-pool", hikari.getPoolName());
        assertEquals(3, hikari.getMaximumPoolSize());
        assertEquals(1, hikari.getMinimumIdle());
        assertEquals(3000, hikari.getConnectionTimeout());
    }

    @Test
    void hikariPool_isRunning() {
        var hikari = (HikariDataSource) dataSource;
        assertTrue(hikari.isRunning());
    }
}
