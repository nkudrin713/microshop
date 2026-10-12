package dev.nkudrin.microshop

import dev.nkudrin.microshop.audit.AuditActorType
import dev.nkudrin.microshop.audit.AuditSnapshot
import dev.nkudrin.microshop.audit.AuditWriter
import jakarta.persistence.EntityManagerFactory
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.hibernate.tool.schema.spi.SchemaManagementException
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.jpa.autoconfigure.JpaProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.orm.jpa.support.OpenEntityManagerInViewFilter
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor
import org.springframework.test.annotation.DirtiesContext
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import java.io.IOException
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.SQLException
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        val admin = adminValues()

        startApplication().use { context ->
            assertJpaConfiguration(context)
            assertEquals(
                "3",
                context
                    .getBean(Flyway::class.java)
                    .info()
                    .current()
                    .version.version,
            )
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM admin_user", Int::class.java))
            insertAdmin(jdbc, admin)
            TransactionTemplate(context.getBean(PlatformTransactionManager::class.java)).executeWithoutResult {
                context.getBean(AuditWriter::class.java).record(
                    actorType = AuditActorType.ADMIN,
                    actorAdminId = admin[0] as UUID,
                    action = "TEST_CREATE",
                    entityType = "admin_user",
                    entityId = admin[0].toString(),
                    after = AuditSnapshot.select(mapOf("display_name" to admin[3]), "display_name"),
                )
            }
        }

        val dataBefore = jdbc.queryForList("SELECT * FROM admin_user")
        val auditBefore = jdbc.queryForList("SELECT * FROM audit_log")
        assertEquals(1, auditBefore.size)
        val historyBefore = jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank")
        assertEquals(3, historyBefore.size)
        assertTrue(historyBefore.all { it["success"] == true })

        startApplication().use { context ->
            assertJpaConfiguration(context)
            assertEquals(dataBefore, jdbc.queryForList("SELECT * FROM admin_user"))
            assertEquals(auditBefore, jdbc.queryForList("SELECT * FROM audit_log"))
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
            val types =
                jdbc
                    .queryForList(
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
        val port = ServerSocket(0).use { it.localPort }
        val failure =
            assertFailsWith<Exception> {
                startMigrationProbe(includeInvalid = true, port = port).close()
            }
        val causes = generateSequence<Throwable>(failure) { it.cause }.toList()
        assertTrue(causes.any { it is FlywayException })
        assertTrue(causes.any { it is SQLException && it.sqlState == "42703" })
        assertTrue(causes.any { it.message?.contains("V2__invalid.sql") == true })

        assertHealthUnavailable(port)

        val jdbc = database()
        assertNull(jdbc.queryForObject("SELECT to_regclass('public.migration_failure_probe')", String::class.java))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Int::class.java))
        assertEquals(1, jdbc.queryForObject("SELECT id FROM migration_baseline_probe", Int::class.java))
        val historyBefore = jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank")

        startMigrationProbe(includeInvalid = false).use { context ->
            assertEquals(
                "1",
                context
                    .getBean(Flyway::class.java)
                    .info()
                    .current()
                    .version.version,
            )
            assertEquals(historyBefore, jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank"))
            assertEquals(1, jdbc.queryForObject("SELECT id FROM migration_baseline_probe", Int::class.java))
            assertNull(jdbc.queryForObject("SELECT to_regclass('public.migration_failure_probe')", String::class.java))
            assertNull(jdbc.queryForObject("SELECT to_regclass('public.admin_user')", String::class.java))
        }
    }

    @Test
    fun `incompatible audit schema aborts startup without repairing schema or migration history`() {
        startApplication().close()
        val jdbc = database()
        jdbc.execute("ALTER TABLE audit_log ALTER COLUMN created_at TYPE text USING created_at::text")
        val schemaQuery =
            "SELECT column_name, data_type, is_nullable FROM information_schema.columns " +
                "WHERE table_schema = 'public' AND table_name = 'audit_log' ORDER BY ordinal_position"
        val schemaBefore = jdbc.queryForList(schemaQuery)
        val historyBefore = jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank")
        assertEquals("text", schemaBefore.single { it["column_name"] == "created_at" }["data_type"])
        val port = ServerSocket(0).use { it.localPort }

        val failure = assertFailsWith<Exception> { startApplication(port = port).close() }
        val causes = generateSequence<Throwable>(failure) { it.cause }.toList()
        assertTrue(
            causes.any {
                it is SchemaManagementException &&
                    it.message.orEmpty().contains("audit_log") && it.message.orEmpty().contains("created_at")
            },
        )
        assertEquals(schemaBefore, jdbc.queryForList(schemaQuery))
        assertEquals(historyBefore, jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank"))
        assertHealthUnavailable(port)
    }

    private fun assertHealthUnavailable(port: Int) {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build().use { client ->
            assertFailsWith<IOException> {
                client.send(
                    HttpRequest
                        .newBuilder(URI("http://127.0.0.1:$port/actuator/health"))
                        .timeout(Duration.ofSeconds(2))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.discarding(),
                )
            }
        }
    }

    private fun startApplication(
        vararg extraArguments: String,
        port: Int = 0,
    ): ConfigurableApplicationContext =
        SpringApplicationBuilder(MicroshopApplication::class.java)
            .run(
                "--spring.datasource.url=${postgres.jdbcUrl}",
                "--spring.datasource.username=${postgres.username}",
                "--spring.datasource.password=${postgres.password}",
                "--server.port=$port",
                "--microshop.app-secret=${TestInstallationSecrets.appSecret}",
                "--microshop.setup-token=${TestInstallationSecrets.setupToken}",
                *extraArguments,
            )

    private fun database(): JdbcTemplate =
        JdbcTemplate(
            DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password),
        )

    private fun startMigrationProbe(
        includeInvalid: Boolean,
        port: Int = 0,
    ): ConfigurableApplicationContext {
        val locations =
            "classpath:db/migration-failure/valid" +
                if (includeInvalid) ",classpath:db/migration-failure/invalid" else ""
        return SpringApplicationBuilder(MigrationProbeApplication::class.java).run(
            "--spring.datasource.url=${postgres.jdbcUrl}",
            "--spring.datasource.username=${postgres.username}",
            "--spring.datasource.password=${postgres.password}",
            "--spring.flyway.locations=$locations",
            "--server.port=$port",
        )
    }

    @TestConfiguration(proxyBeanMethods = false)
    @ImportAutoConfiguration(
        DataSourceAutoConfiguration::class,
        FlywayAutoConfiguration::class,
        TomcatServletWebServerAutoConfiguration::class,
    )
    class MigrationProbeApplication

    private fun assertJpaConfiguration(context: ConfigurableApplicationContext) {
        context.getBean(DataSource::class.java).connection.use {
            assertEquals(postgres.jdbcUrl, it.metaData.url)
        }
        assertEquals("validate", context.getBean(EntityManagerFactory::class.java).properties["hibernate.hbm2ddl.auto"])
        assertEquals(false, context.getBean(JpaProperties::class.java).openInView)
        assertTrue(context.getBeanNamesForType(OpenEntityManagerInViewInterceptor::class.java).isEmpty())
        assertTrue(context.getBeanNamesForType(OpenEntityManagerInViewFilter::class.java).isEmpty())
    }

    private fun adminValues(): MutableList<Any?> =
        mutableListOf(
            UUID.randomUUID(),
            "test-${UUID.randomUUID()}",
            "test-only-hash",
            "Test owner",
            true,
            OffsetDateTime.parse("2026-01-01T00:00:00Z"),
            OffsetDateTime.parse("2026-01-01T00:00:00Z"),
        )

    private fun insertAdmin(
        jdbc: JdbcTemplate,
        values: List<Any?>,
    ) {
        jdbc.update(
            "INSERT INTO admin_user (id, login, password_hash, display_name, enabled, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
            *values.toTypedArray(),
        )
    }

    private fun assertSqlState(
        expected: String,
        operation: () -> Unit,
    ) {
        val failure = assertFailsWith<DataAccessException> { operation() }
        val sqlException = generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<SQLException>().first()
        assertEquals(expected, sqlException.sqlState)
    }

    @Configuration(proxyBeanMethods = false)
    @Import(PostgresTestConfiguration::class)
    class ContainerHostConfiguration
}
