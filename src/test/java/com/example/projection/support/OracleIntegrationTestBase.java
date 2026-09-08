package com.example.projection.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.Properties;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.utility.MountableFile;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
public abstract class OracleIntegrationTestBase {

    protected static final String APP_USER = "projection_app";
    protected static final String APP_PASSWORD = "TestPwd123";

    protected static final OracleContainer ORACLE = new OracleContainer("gvenzl/oracle-free:23.26.0-slim-faststart")
            .withUsername(APP_USER)
            .withPassword(APP_PASSWORD)
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("oracle-init/01_memoptimize_pool.sql"),
                    "/container-entrypoint-initdb.d/01_memoptimize_pool.sql")
            .withStartupTimeout(Duration.ofMinutes(4));

    static {
        ORACLE.start();
        grantExtraPrivileges();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", ORACLE::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_USER);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("projection.bootstrap-on-startup", () -> "false");
    }

    private static void grantExtraPrivileges() {
        String jdbcUrl = ORACLE.getJdbcUrl();
        try (Connection system = DriverManager.getConnection(jdbcUrl, "system", APP_PASSWORD);
                Statement stmt = system.createStatement()) {
            stmt.execute("GRANT CREATE SYNONYM TO " + APP_USER);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to grant CREATE SYNONYM for integration tests", e);
        }

        Properties sysdba = new Properties();
        sysdba.setProperty("user", "sys");
        sysdba.setProperty("password", APP_PASSWORD);
        sysdba.setProperty("internal_logon", "sysdba");
        try (Connection sys = DriverManager.getConnection(jdbcUrl, sysdba);
                Statement stmt = sys.createStatement()) {
            stmt.execute("GRANT EXECUTE ON SYS.DBMS_MEMOPTIMIZE TO " + APP_USER);
            stmt.execute("GRANT SELECT ON SYS.V_$PARAMETER TO " + APP_USER);
            stmt.execute("GRANT SELECT ON SYS.V_$SESSION TO " + APP_USER);
            stmt.execute("GRANT SELECT ON SYS.V_$SQL TO " + APP_USER);
            stmt.execute("GRANT SELECT ON SYS.V_$SQL_PLAN TO " + APP_USER);
            stmt.execute("GRANT SELECT ON SYS.V_$SQL_PLAN_STATISTICS_ALL TO " + APP_USER);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to grant SYS-owned object privileges for integration tests", e);
        }
    }
}
