package dev.nkudrin.microshop

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnectionAutoConfiguration
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import org.testcontainers.postgresql.PostgreSQLContainer
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@SpringBootTest(
    classes = [PostgresEnvironmentTest.JdbcConfiguration::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/must_not_be_used",
        "spring.datasource.username=must_not_be_used",
        "spring.datasource.password=must_not_be_used",
    ],
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PostgresEnvironmentTest {
    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var postgres: PostgreSQLContainer

    @Test
    fun `committed SQL is visible from another connection in a fresh database`() {
        assertFreshContainerConnection()
        createProbeTable()

        dataSource.connection.use { connection ->
            connection.autoCommit = false
            connection.createStatement().use { it.executeUpdate("INSERT INTO environment_probe VALUES (1)") }
            connection.commit()
        }

        assertEquals(1, probeRowCount())
    }

    @Test
    fun `rolled back SQL leaves no rows in a fresh database`() {
        assertFreshContainerConnection()
        createProbeTable()

        dataSource.connection.use { connection ->
            connection.autoCommit = false
            connection.createStatement().use { it.executeUpdate("INSERT INTO environment_probe VALUES (1)") }
            connection.rollback()
        }

        assertEquals(0, probeRowCount())
    }

    private fun assertFreshContainerConnection() {
        assertFalse(postgres.isShouldBeReused)
        dataSource.connection.use { connection ->
            assertEquals(postgres.jdbcUrl, connection.metaData.url)
            assertEquals("PostgreSQL", connection.metaData.databaseProductName)
            assertEquals(18, connection.metaData.databaseMajorVersion)
            assertEquals(postgres.username, connection.metaData.userName)
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT current_database(), to_regclass('public.environment_probe')").use {
                    it.next()
                    assertEquals(postgres.databaseName, it.getString(1))
                    assertNull(it.getString(2))
                }
            }
        }
    }

    private fun createProbeTable() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute("CREATE TABLE environment_probe (id integer PRIMARY KEY)") }
        }
    }

    private fun probeRowCount(): Int =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM environment_probe").use {
                    it.next()
                    it.getInt(1)
                }
            }
        }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(DataSourceAutoConfiguration::class, ServiceConnectionAutoConfiguration::class)
    @Import(PostgresTestConfiguration::class)
    class JdbcConfiguration
}
