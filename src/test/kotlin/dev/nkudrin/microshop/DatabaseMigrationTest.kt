package dev.nkudrin.microshop

import jakarta.persistence.EntityManagerFactory
import java.sql.SQLException
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.jpa.autoconfigure.JpaProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.orm.jpa.support.OpenEntityManagerInViewFilter
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor
import org.springframework.test.annotation.DirtiesContext
import org.testcontainers.postgresql.PostgreSQLContainer

@SpringBootTest(
    classes = [DatabaseMigrationTest.ContainerHostConfiguration::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class DatabaseMigrationTest {
    @Autowired
    private lateinit var postgres: PostgreSQLContainer

    @Test
    fun `empty database is migrated and restart preserves schema history and data`() {
        val jdbc = database()
        assertNull(jdbc.queryForObject("SELECT to_regclass('public.admin_user')", String::class.java))
        assertNull(jdbc.queryForObject("SELECT to_regclass('public.flyway_schema_history')", String::class.java))

        startApplication().use { context ->
            assertJpaConfiguration(context)
            assertEquals("2", context.getBean(Flyway::class.java).info().current().version.version)
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM admin_user", Int::class.java))
            insertAdmin(jdbc, adminValues())
        }

        val dataBefore = jdbc.queryForList("SELECT * FROM admin_user")
        val historyBefore = jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank")
        assertEquals(2, historyBefore.size)
        assertTrue(historyBefore.all { it["success"] == true })

        startApplication().use { context ->
            assertJpaConfiguration(context)
            assertEquals(dataBefore, jdbc.queryForList("SELECT * FROM admin_user"))
            assertEquals(historyBefore, jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank"))
        }
    }

    @Test
    fun `admin user requires every field and unique login and primary key`() {
        startApplication().use {
            val jdbc = database()
            val values = adminValues()
            insertAdmin(jdbc, values)

            assertSqlState("23505") { insertAdmin(jdbc, adminValues().also { it[1] = values[1] }) }
            assertSqlState("23505") { insertAdmin(jdbc, adminValues().also { it[0] = values[0] }) }

            values.indices.forEach { index ->
                assertSqlState("23502") { insertAdmin(jdbc, adminValues().also { it[index] = null }) }
            }

            insertAdmin(jdbc, adminValues().also { it[4] = false })
            assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM admin_user", Int::class.java))
            val types = jdbc.queryForList(
                "SELECT column_name, data_type FROM information_schema.columns " +
                    "WHERE table_schema = 'public' AND table_name = 'admin_user'",
            ).associate { it["column_name"] as String to it["data_type"] as String }
            assertEquals(
                mapOf(
                    "id" to "uuid",
                    "login" to "text",
                    "password_hash" to "text",
                    "display_name" to "text",
                    "enabled" to "boolean",
                    "created_at" to "timestamp with time zone",
                    "updated_at" to "timestamp with time zone",
                ),
                types,
            )
        }
    }

    @Test
    fun `invalid test migration aborts application startup and rolls back its changes`() {
        val failure = assertFailsWith<Exception> {
            startApplication(
                "--spring.flyway.locations=classpath:db/migration,classpath:db/invalid-migration",
            ).close()
        }
        val causes = generateSequence<Throwable>(failure) { it.cause }.toList()
        assertTrue(causes.any { it is FlywayException })
        assertTrue(causes.any { it is SQLException && it.sqlState == "42703" })
        assertTrue(causes.any { it.message?.contains("V3__invalid.sql") == true })

        val jdbc = database()
        assertNull(jdbc.queryForObject("SELECT to_regclass('public.migration_failure_probe')", String::class.java))
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Int::class.java))

        startApplication().use { context ->
            assertEquals("2", context.getBean(Flyway::class.java).info().current().version.version)
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM admin_user", Int::class.java))
        }
    }

    private fun startApplication(vararg extraArguments: String): ConfigurableApplicationContext =
        SpringApplicationBuilder(MicroshopApplication::class.java)
            .run(
                "--spring.datasource.url=${postgres.jdbcUrl}",
                "--spring.datasource.username=${postgres.username}",
                "--spring.datasource.password=${postgres.password}",
                "--server.port=0",
                *extraArguments,
            )

    private fun database(): JdbcTemplate = JdbcTemplate(
        DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password),
    )

    private fun assertJpaConfiguration(context: ConfigurableApplicationContext) {
        context.getBean(DataSource::class.java).connection.use {
            assertEquals(postgres.jdbcUrl, it.metaData.url)
        }
        assertEquals("validate", context.getBean(EntityManagerFactory::class.java).properties["hibernate.hbm2ddl.auto"])
        assertEquals(false, context.getBean(JpaProperties::class.java).openInView)
        assertTrue(context.getBeanNamesForType(OpenEntityManagerInViewInterceptor::class.java).isEmpty())
        assertTrue(context.getBeanNamesForType(OpenEntityManagerInViewFilter::class.java).isEmpty())
    }

    private fun adminValues(): MutableList<Any?> = mutableListOf(
        UUID.randomUUID(),
        "test-${UUID.randomUUID()}",
        "test-only-hash",
        "Test owner",
        true,
        OffsetDateTime.parse("2026-01-01T00:00:00Z"),
        OffsetDateTime.parse("2026-01-01T00:00:00Z"),
    )

    private fun insertAdmin(jdbc: JdbcTemplate, values: List<Any?>) {
        jdbc.update(
            "INSERT INTO admin_user (id, login, password_hash, display_name, enabled, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
            *values.toTypedArray(),
        )
    }

    private fun assertSqlState(expected: String, operation: () -> Unit) {
        val failure = assertFailsWith<DataAccessException> { operation() }
        val sqlException = generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<SQLException>().first()
        assertEquals(expected, sqlException.sqlState)
    }

    @Configuration(proxyBeanMethods = false)
    @Import(PostgresTestConfiguration::class)
    class ContainerHostConfiguration
}
