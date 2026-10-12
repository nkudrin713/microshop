package dev.nkudrin.microshop

import org.flywaydb.core.Flyway
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
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest(classes = [Ms02AcceptanceTest.ContainerHost::class], webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(PostgresTestConfiguration::class)
class Ms02AcceptanceTest {
    @Autowired
    private lateinit var postgres: PostgreSQLContainer

    private val json = JsonMapper.builder().build()

    @Test
    fun `clean installation supports setup login settings restart and logout through HTTP`() {
        val jdbc = JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM pg_tables WHERE schemaname = 'public'", Int::class.java))
        val cookies = CookieManager(null, CookiePolicy.ACCEPT_ALL)
        HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(2)).build().use { client ->
            val browser = Browser(client)
            val saved = startApplication().use { context -> prepareInstallation(context, browser, jdbc) }

            startApplication(setupToken = "").use { context ->
                assertEquals(saved.installation, installation(jdbc))
                assertEquals(saved.settingsRow, jdbc.queryForMap("SELECT * FROM shop_settings"))
                assertEquals(saved.session, jdbc.queryForMap("SELECT * FROM spring_session"))
                assertEquals(saved.audit, audit(jdbc))
                assertHealth(context, browser)
                assertStatus(context, browser, initialized = true)

                val profile = body(send(context, browser, "GET", "/api/admin/auth/me"), 200)
                assertEquals(saved.ownerId.toString(), profile["id"].stringValue())
                assertEquals(setOf("id", "login", "displayName"), profile.propertyNames().toSet())
                assertEquals(saved.settings, body(send(context, browser, "GET", SETTINGS), 200))

                val oldCookie = cookies.cookieStore.cookies.single { it.name == "MICROSHOP_SESSION" }.value
                val logout = send(context, browser, "POST", "/api/admin/auth/logout")
                assertEquals(204, logout.statusCode())
                assertEquals("", logout.body())
                assertTrue(logout.headers().allValues("set-cookie").any { it.contains("Max-Age=0") })
                assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM spring_session", Int::class.java))
                assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM spring_session_attributes", Int::class.java))
                assertEquals("AUTHENTICATION_REQUIRED", body(send(context, browser, "GET", SETTINGS), 401)["code"].stringValue())
                val replay = send(context, browser, "GET", SETTINGS, cookie = "MICROSHOP_SESSION=$oldCookie")
                assertEquals("AUTHENTICATION_REQUIRED", body(replay, 401)["code"].stringValue())
                assertEquals(saved.settingsRow, jdbc.queryForMap("SELECT * FROM shop_settings"))
                assertEquals(saved.installation, installation(jdbc))
                assertEquals(saved.audit, audit(jdbc))
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM admin_user", Int::class.java))
            }
        }
    }

    private fun prepareInstallation(context: ConfigurableApplicationContext, browser: Browser, jdbc: JdbcTemplate): SavedInstallation {
        val flyway = context.getBean(Flyway::class.java)
        assertTrue(flyway.info().applied().isNotEmpty())
        assertTrue(flyway.info().pending().isEmpty())
        assertHealth(context, browser)
        assertStatus(context, browser, initialized = false)
        assertEquals("AUTHENTICATION_REQUIRED", body(send(context, browser, "GET", SETTINGS), 401)["code"].stringValue())
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM admin_user", Int::class.java))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM shop_settings", Int::class.java))

        csrf(context, browser)
        val owner = body(
            send(
                context,
                browser,
                "POST",
                "/api/setup/owner",
                mapOf(
                    "setupToken" to TestInstallationSecrets.setupToken,
                    "login" to "owner",
                    "password" to PASSWORD,
                    "displayName" to "Owner",
                ),
            ),
            201,
        )
        assertEquals(setOf("id", "login", "displayName"), owner.propertyNames().toSet())
        val ownerId = UUID.fromString(owner["id"].stringValue())
        assertStatus(context, browser, initialized = true)
        assertEquals("AUTHENTICATION_REQUIRED", body(send(context, browser, "GET", SETTINGS), 401)["code"].stringValue())

        val anonymousId = jdbc.queryForObject("SELECT session_id FROM spring_session", String::class.java)
        val profile = body(
            send(context, browser, "POST", "/api/admin/auth/login", mapOf("login" to "owner", "password" to PASSWORD)),
            200,
        )
        assertEquals(owner, profile)
        csrf(context, browser)
        val settings = updateSettings(context, browser)
        val session = jdbc.queryForMap("SELECT * FROM spring_session")
        assertNotEquals(anonymousId, session["session_id"])
        assertEquals(ownerId.toString(), session["principal_name"])
        assertEquals(86400, session["max_inactive_interval"])

        val installation = installation(jdbc)
        assertEquals("INITIALIZED", installation["status"])
        assertEquals(ownerId, installation["owner_admin_id"])
        assertNull(installation["setup_token_hash"])
        assertTrue(installation["initialized_at"] != null)
        val passwordHash = jdbc.queryForObject("SELECT password_hash FROM admin_user WHERE id = ?", String::class.java, ownerId)!!
        assertTrue(passwordHash.startsWith("$" + "argon2id$"))
        assertNotEquals(PASSWORD, passwordHash)
        val audit = audit(jdbc)
        assertEquals(listOf("OWNER_CREATED", "SHOP_SETTINGS_UPDATED"), audit.map { it["action"] })
        audit.forEach {
            assertEquals("ADMIN", it["actor_type"])
            assertEquals(ownerId, it["actor_admin_id"])
            assertSafe(it["before_data"].toString())
            assertSafe(it["after_data"].toString())
        }
        assertEquals(ownerId.toString(), audit[0]["entity_id"])
        assertEquals("1", audit[1]["entity_id"])
        assertEquals("Магазин", json.readTree(audit[1]["after_data"].toString())["name"].stringValue())
        return SavedInstallation(ownerId, settings, installation, jdbc.queryForMap("SELECT * FROM shop_settings"), session, audit)
    }

    private fun updateSettings(context: ConfigurableApplicationContext, browser: Browser): JsonNode {
        val settings = body(send(context, browser, "GET", SETTINGS), 200) as ObjectNode
        assertEquals(
            setOf(
                "name", "description", "contacts", "salesTerms", "checkoutContactRules",
                "primaryColor", "backgroundColor", "timezone", "version",
            ),
            settings.propertyNames().toSet(),
        )
        assertEquals(0, settings["version"].intValue())
        settings.put("name", " Магазин ")
        settings.put("description", "Первая строка\nВторая строка")
        settings.put("salesTerms", "Условия продажи")
        (settings["contacts"] as ObjectNode).put("email", "owner@example.com")
        val saved = body(send(context, browser, "PUT", SETTINGS, settings), 200)
        assertEquals("Магазин", saved["name"].stringValue())
        assertEquals(1, saved["version"].intValue())
        assertEquals(saved, body(send(context, browser, "GET", SETTINGS), 200))
        return saved
    }

    private fun assertHealth(context: ConfigurableApplicationContext, browser: Browser) {
        assertEquals(json.readTree("""{"status":"UP"}"""), body(send(context, browser, "GET", "/actuator/health"), 200))
    }

    private fun assertStatus(context: ConfigurableApplicationContext, browser: Browser, initialized: Boolean) {
        assertEquals(
            json.readTree("""{"initialized":$initialized}"""),
            body(send(context, browser, "GET", "/api/setup/status"), 200),
        )
    }

    private fun csrf(context: ConfigurableApplicationContext, browser: Browser) {
        val response = send(context, browser, "GET", "/api/csrf")
        assertEquals("no-store", response.headers().firstValue("cache-control").orElseThrow())
        browser.csrf = body(response, 200)["token"].stringValue()
    }

    private fun send(
        context: ConfigurableApplicationContext,
        browser: Browser,
        method: String,
        path: String,
        input: Any? = null,
        cookie: String? = null,
    ): HttpResponse<String> {
        val port = checkNotNull((context as WebServerApplicationContext).webServer).port
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(10))
        if (method != "GET") request.header("X-CSRF-TOKEN", browser.csrf)
        if (cookie != null) request.header("Cookie", cookie)
        if (input != null) request.header("Content-Type", "application/json")
        val body = input?.let { HttpRequest.BodyPublishers.ofString(json.writeValueAsString(it)) } ?: HttpRequest.BodyPublishers.noBody()
        return browser.client.send(request.method(method, body).build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun body(response: HttpResponse<String>, status: Int): JsonNode {
        assertEquals(status, response.statusCode())
        assertSafe(response.body())
        return json.readTree(response.body())
    }

    private fun assertSafe(value: String) {
        listOf(TestInstallationSecrets.appSecret, TestInstallationSecrets.setupToken, PASSWORD, "passwordHash", "password_hash").forEach {
            assertFalse(value.contains(it), "Response or audit contains a secret")
        }
    }

    private fun installation(jdbc: JdbcTemplate): Map<String, Any?> = jdbc.queryForMap(
        "SELECT id, status, setup_token_hash, encode(app_secret_hash, 'hex') AS app_secret_hash, owner_admin_id, initialized_at " +
            "FROM installation_state",
    )

    private fun audit(jdbc: JdbcTemplate): List<Map<String, Any?>> = jdbc.queryForList("SELECT * FROM audit_log ORDER BY created_at, id")

    private fun startApplication(setupToken: String = TestInstallationSecrets.setupToken): ConfigurableApplicationContext =
        SpringApplicationBuilder(MicroshopApplication::class.java).run(
            "--spring.profiles.active=dev",
            "--spring.datasource.url=${postgres.jdbcUrl}",
            "--spring.datasource.username=${postgres.username}",
            "--spring.datasource.password=${postgres.password}",
            "--server.port=0",
            "--microshop.app-secret=${TestInstallationSecrets.appSecret}",
            "--microshop.setup-token=$setupToken",
        )

    private class Browser(val client: HttpClient, var csrf: String = "")

    private data class SavedInstallation(
        val ownerId: UUID,
        val settings: JsonNode,
        val installation: Map<String, Any?>,
        val settingsRow: Map<String, Any?>,
        val session: Map<String, Any?>,
        val audit: List<Map<String, Any?>>,
    )

    @Configuration(proxyBeanMethods = false)
    class ContainerHost

    private companion object {
        const val PASSWORD = "test-only-owner-password"
        const val SETTINGS = "/api/admin/settings"
    }
}
