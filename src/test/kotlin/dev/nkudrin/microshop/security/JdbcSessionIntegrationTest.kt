package dev.nkudrin.microshop.security

import dev.nkudrin.microshop.MicroshopApplication
import dev.nkudrin.microshop.PostgresTestConfiguration
import dev.nkudrin.microshop.TestInstallationSecrets
import dev.nkudrin.microshop.setup.CreateOwnerRequest
import dev.nkudrin.microshop.setup.SetupService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.annotation.DirtiesContext
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@SpringBootTest(
    classes = [JdbcSessionIntegrationTest.ContainerHostConfiguration::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class JdbcSessionIntegrationTest {
    @Autowired
    private lateinit var postgres: PostgreSQLContainer

    private val json = JsonMapper.builder().build()

    @Test
    fun `authenticated session survives restart and returns current owner profile`() {
        val jdbc = database()
        HttpClient.newHttpClient().use { client ->
            val browser =
                startApplication().use { context ->
                    context.getBean(SetupService::class.java).createOwner(
                        CreateOwnerRequest(TestInstallationSecrets.setupToken, "owner01", "123456789012345", "Owner"),
                    )
                    login(client, context, csrf(client, context))
                }
            val before = jdbc.queryForMap("SELECT * FROM spring_session")
            assertEquals(jdbc.queryForObject("SELECT id::text FROM admin_user", String::class.java), before["principal_name"])
            jdbc.update("UPDATE admin_user SET display_name = 'Updated'")

            startApplication().use { context ->
                assertEquals(before, jdbc.queryForMap("SELECT * FROM spring_session"))
                val response =
                    client.send(
                        request(context, "/api/admin/auth/me").header("Cookie", browser.cookie).GET().build(),
                        HttpResponse.BodyHandlers.ofString(),
                    )
                assertEquals(200, response.statusCode())
                assertEquals("Updated", json.readTree(response.body())["displayName"].stringValue())
                assertEquals(before["principal_name"], json.readTree(response.body())["id"].stringValue())
                assertTrue(response.headers().firstValue("set-cookie").isEmpty)
            }
        }
    }

    @Test
    fun `restart preserves session inactivity deadline and CSRF token`() {
        val jdbc = database()
        HttpClient.newHttpClient().use { client ->
            val browser = startApplication().use { context -> csrf(client, context) }
            val before = jdbc.queryForMap("SELECT * FROM spring_session")
            assertEquals(86400, before["max_inactive_interval"])
            assertTrue(jdbc.queryForObject("SELECT count(*) FROM spring_session_attributes", Int::class.java)!! > 0)

            startApplication().use { context ->
                assertEquals(before, jdbc.queryForMap("SELECT * FROM spring_session"))
                val response = setup(client, context, browser)
                assertEquals(403, response.statusCode())
                assertEquals("SETUP_TOKEN_INVALID", json.readTree(response.body())["code"].stringValue())
                assertTrue(response.headers().firstValue("set-cookie").isEmpty)
                val after = jdbc.queryForMap("SELECT * FROM spring_session")
                assertEquals(before["session_id"], after["session_id"])
                assertTrue((after["last_access_time"] as Long) >= (before["last_access_time"] as Long))
            }
        }
    }

    @Test
    fun `expired session is rejected after restart without waiting for cleanup`() {
        val jdbc = database()
        HttpClient.newHttpClient().use { client ->
            val browser = startApplication().use { context -> csrf(client, context) }
            val sessionId = jdbc.queryForObject("SELECT session_id FROM spring_session", String::class.java)
            val expiry = System.currentTimeMillis() - 1000
            jdbc.update(
                "UPDATE spring_session SET last_access_time = ?, expiry_time = ?",
                expiry - Duration.ofHours(24).toMillis(),
                expiry,
            )

            startApplication().use { context ->
                val response = setup(client, context, browser)
                assertEquals(403, response.statusCode())
                assertEquals("CSRF_TOKEN_INVALID", json.readTree(response.body())["code"].stringValue())
                assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM admin_user", Int::class.java))
                val replacement = csrf(client, context, browser.cookie)
                assertNotEquals(browser.cookie, replacement.cookie)
                assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM spring_session WHERE session_id = ?", Int::class.java, sessionId))
            }
        }
    }

    @Test
    fun `separate browsers have independent persisted sessions and CSRF tokens`() {
        val jdbc = database()
        startApplication().use { context ->
            HttpClient.newHttpClient().use { client ->
                val first = csrf(client, context)
                val second = csrf(client, context)
                assertNotEquals(first.cookie, second.cookie)
                assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM spring_session", Int::class.java))
                assertEquals("SETUP_TOKEN_INVALID", json.readTree(setup(client, context, first).body())["code"].stringValue())
                assertEquals("SETUP_TOKEN_INVALID", json.readTree(setup(client, context, second).body())["code"].stringValue())
                val mixed = setup(client, context, first.copy(token = second.token))
                assertEquals(403, mixed.statusCode())
                assertEquals("CSRF_TOKEN_INVALID", json.readTree(mixed.body())["code"].stringValue())
            }
        }
    }

    private fun csrf(client: HttpClient, context: ConfigurableApplicationContext, cookie: String? = null): BrowserSession {
        val request = request(context, "/api/csrf")
        if (cookie != null) request.header("Cookie", cookie)
        val response = client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(200, response.statusCode())
        assertEquals("no-store", response.headers().firstValue("cache-control").orElseThrow())
        return BrowserSession(
            response
                .headers()
                .firstValue("set-cookie")
                .orElseThrow()
                .substringBefore(';'),
            json.readTree(response.body())["token"].stringValue(),
        )
    }

    private fun setup(client: HttpClient, context: ConfigurableApplicationContext, browser: BrowserSession): HttpResponse<String> =
        client.send(
            request(context, "/api/setup/owner")
                .header("Cookie", browser.cookie)
                .header("X-CSRF-TOKEN", browser.token)
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        """{"setupToken":"invalid","login":"owner","password":"123456789012345","displayName":"Owner"}""",
                    ),
                ).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun login(client: HttpClient, context: ConfigurableApplicationContext, browser: BrowserSession): BrowserSession {
        val response =
            client.send(
                request(context, "/api/admin/auth/login")
                    .header("Cookie", browser.cookie)
                    .header("X-CSRF-TOKEN", browser.token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"login":"owner01","password":"123456789012345"}"""))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        assertEquals(200, response.statusCode())
        val cookie = response.headers().firstValue("set-cookie").orElseThrow()
        return browser.copy(cookie = cookie.substringBefore(';'))
    }

    private fun request(context: ConfigurableApplicationContext, path: String): HttpRequest.Builder {
        val port = checkNotNull((context as WebServerApplicationContext).webServer).port
        return HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(5))
    }

    private fun startApplication(): ConfigurableApplicationContext = SpringApplicationBuilder(MicroshopApplication::class.java).run(
        "--spring.profiles.active=dev",
        "--spring.datasource.url=${postgres.jdbcUrl}",
        "--spring.datasource.username=${postgres.username}",
        "--spring.datasource.password=${postgres.password}",
        "--server.port=0",
        "--microshop.app-secret=${TestInstallationSecrets.appSecret}",
        "--microshop.setup-token=${TestInstallationSecrets.setupToken}",
    )

    private fun database(): JdbcTemplate = JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))

    private data class BrowserSession(val cookie: String, val token: String)

    @Configuration(proxyBeanMethods = false)
    @Import(PostgresTestConfiguration::class)
    class ContainerHostConfiguration
}
