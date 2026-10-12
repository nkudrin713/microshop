package dev.nkudrin.microshop

import com.zaxxer.hikari.HikariDataSource
import jakarta.persistence.EntityManagerFactory
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.DriverManager
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.datasource.hikari.connection-timeout=1000",
        "spring.datasource.hikari.validation-timeout=500",
        "spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.minimum-idle=0",
    ],
)
@Import(PostgresTestConfiguration::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class BackendHealthTest {
    @Autowired
    private lateinit var context: ApplicationContext

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var postgres: PostgreSQLContainer

    @Autowired
    private lateinit var dataSource: HikariDataSource

    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    private val json = JsonMapper.builder().build()

    @AfterEach
    fun closeClient() {
        client.close()
    }

    @Test
    fun `health is up after migrations and JPA validation without owner and hides infrastructure endpoints`() {
        assertTrue(
            context
                .getBean(Flyway::class.java)
                .info()
                .applied()
                .isNotEmpty(),
        )
        assertTrue(
            context
                .getBean(Flyway::class.java)
                .info()
                .pending()
                .isEmpty(),
        )
        assertEquals("validate", context.getBean(EntityManagerFactory::class.java).properties["hibernate.hbm2ddl.auto"])
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM admin_user", Int::class.java))
        assertHealth(200, "UP")

        listOf(
            "/actuator",
            "/actuator/env",
            "/actuator/configprops",
            "/actuator/beans",
            "/actuator/mappings",
            "/actuator/metrics",
            "/actuator/info",
            "/actuator/loggers",
            "/actuator/heapdump",
            "/actuator/threaddump",
            "/actuator/health/db",
            "/actuator/health/diskSpace",
            "/actuator/health/liveness",
            "/actuator/health/readiness",
        ).forEach { path ->
            assertEquals(401, request(path).statusCode(), path)
        }
    }

    @Test
    fun `production rejects HTTP and spoofed proxy headers before creating a session`() {
        val port = checkNotNull((context as WebServerApplicationContext).webServer).port
        listOf("/api/setup/status", "/api/setup/owner", "/api/csrf", "/api/admin/settings", "/%61pi/csrf").forEach { path ->
            val response =
                client.send(
                    HttpRequest
                        .newBuilder(URI("http://127.0.0.1:$port$path"))
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Forwarded-For", "127.0.0.1")
                        .header("Forwarded", "proto=https;for=127.0.0.1")
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            assertEquals(400, response.statusCode(), path)
            assertEquals("HTTPS_REQUIRED", json.readTree(response.body())["code"].stringValue())
            assertTrue(response.headers().firstValue("set-cookie").isEmpty)
        }
    }

    @Test
    fun `health becomes down when PostgreSQL disconnects existing sessions and refuses new connections`() {
        assertHealth(200, "UP")
        val maintenanceUrl = postgres.jdbcUrl.replace("/microshop_test", "/postgres")
        DriverManager.getConnection(maintenanceUrl, postgres.username, postgres.password).use { admin ->
            admin.createStatement().use { statement ->
                statement.execute("ALTER DATABASE microshop_test ALLOW_CONNECTIONS false")
                try {
                    statement
                        .executeQuery(
                            "SELECT pg_terminate_backend(pid, 2000) FROM pg_stat_activity " +
                                "WHERE datname = 'microshop_test' AND pid <> pg_backend_pid()",
                        ).use { result ->
                            while (result.next()) assertTrue(result.getBoolean(1))
                        }
                    dataSource.hikariPoolMXBean.softEvictConnections()
                    assertHealth(503, "DOWN")
                } finally {
                    statement.execute("ALTER DATABASE microshop_test ALLOW_CONNECTIONS true")
                }
            }
        }
    }

    private fun assertHealth(
        status: Int,
        health: String,
    ) {
        val response = request("/actuator/health")
        assertEquals(status, response.statusCode())
        assertEquals(json.readTree("{\"status\":\"$health\"}"), json.readTree(response.body()))
    }

    private fun request(path: String): HttpResponse<String> {
        val port = checkNotNull((context as WebServerApplicationContext).webServer).port
        return client.send(
            HttpRequest
                .newBuilder(URI("http://127.0.0.1:$port$path"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
    }
}
