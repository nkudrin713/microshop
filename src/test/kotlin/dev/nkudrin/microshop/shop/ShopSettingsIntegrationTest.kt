package dev.nkudrin.microshop.shop

import dev.nkudrin.microshop.PostgresTestConfiguration
import dev.nkudrin.microshop.TestInstallationSecrets
import dev.nkudrin.microshop.setup.CreateOwnerRequest
import dev.nkudrin.microshop.setup.InstallationService
import dev.nkudrin.microshop.setup.SetupService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Import(PostgresTestConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
class ShopSettingsIntegrationTest {
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var installation: InstallationService

    @Autowired private lateinit var setup: SetupService

    @Autowired private lateinit var json: JsonMapper

    @LocalServerPort private var port: Int = 0

    private val browsers = mutableListOf<Browser>()
    private lateinit var owner: Browser
    private lateinit var ownerId: UUID

    @BeforeEach
    fun prepareOwner() {
        jdbc.update("DELETE FROM spring_session")
        jdbc.update("DELETE FROM audit_log")
        jdbc.update("DELETE FROM installation_state")
        jdbc.update("DELETE FROM admin_user")
        jdbc.update("DELETE FROM shop_settings")
        jdbc.update("INSERT INTO shop_settings (id) VALUES (1)")
        installation.initialize(TestInstallationSecrets.appSecret, TestInstallationSecrets.setupToken)
        ownerId = setup.createOwner(CreateOwnerRequest(TestInstallationSecrets.setupToken, "owner", PASSWORD, "Owner")).id
        owner = authenticatedBrowser()
    }

    @AfterEach
    fun closeBrowsers() {
        browsers.forEach { it.client.close() }
    }

    @Test
    fun `GET exposes editable defaults without internal data or writes`() {
        val before = row()
        val settings = read()
        assertEquals(input().keys, settings.propertyNames().toSet())
        assertEquals(json.readTree(json.writeValueAsString(input())), settings)
        assertEquals(before, row())
        assertEquals(0, settingsAudits())
    }

    @Test
    fun `settings require owner authentication and CSRF including before setup`() {
        val before = row()
        val anonymous = browser()
        assertProblem(send(anonymous, "GET", PATH), 401, "AUTHENTICATION_REQUIRED")
        assertProblem(update(input(), anonymous), 401, "AUTHENTICATION_REQUIRED")
        val token = owner.token
        listOf(null, "invalid").forEach {
            owner.token = it
            assertProblem(update(input()), 403, "CSRF_TOKEN_INVALID")
        }
        owner.token = token
        jdbc.update("DELETE FROM spring_session")
        jdbc.update("DELETE FROM audit_log")
        jdbc.update("DELETE FROM installation_state")
        jdbc.update("DELETE FROM admin_user")
        installation.initialize(TestInstallationSecrets.appSecret, TestInstallationSecrets.setupToken)
        val fresh = browser()
        assertProblem(send(fresh, "GET", PATH), 401, "AUTHENTICATION_REQUIRED")
        assertProblem(update(input(), fresh), 401, "AUTHENTICATION_REQUIRED")
        assertEquals(before, row())
        assertEquals(0, settingsAudits())
    }

    @Test
    fun `PUT persists normalized settings advances version and audits safe before and after`() {
        jdbc.update("UPDATE shop_settings SET published = true, sales_disabled_reason = 'Пауза'")
        val before = row()
        val response = update(changed())
        assertEquals(200, response.statusCode(), response.body())
        val settings = json.readTree(response.body())
        assertEquals(settings, read())
        assertEquals("Магазин", settings["name"].stringValue())
        assertEquals("+79991234567", settings["contacts"]["phone"].stringValue())
        assertEquals("https://t.me/owner", settings["contacts"]["telegram"].stringValue())
        assertEquals("#ABCDEF", settings["primaryColor"].stringValue())
        assertEquals(1, settings["version"].intValue())
        val saved = row()
        assertEquals(true, saved["published"])
        assertEquals(false, saved["sales_enabled"])
        assertEquals("Пауза", saved["sales_disabled_reason"])
        assertNotEquals(before["updated_at"], saved["updated_at"])
        val audit = jdbc.queryForMap("SELECT * FROM audit_log WHERE action = 'SHOP_SETTINGS_UPDATED'")
        assertEquals("ADMIN", audit["actor_type"])
        assertEquals(ownerId, audit["actor_admin_id"])
        assertEquals("SHOP_SETTINGS", audit["entity_type"])
        assertEquals("1", audit["entity_id"])
        val old = json.readTree(audit["before_data"].toString())
        val new = json.readTree(audit["after_data"].toString())
        val fields = setOf(
            "name", "description", "salesTerms", "contacts.phone", "contacts.email", "contacts.telegram", "contacts.otherContact",
            "checkoutContactRules", "primaryColor", "backgroundColor", "timezone", "version",
        )
        assertEquals(fields, old.propertyNames().toSet())
        assertEquals(fields, new.propertyNames().toSet())
        assertEquals("", old["name"].stringValue())
        assertTrue(old["contacts.phone"].isNull)
        assertEquals(0, old["version"].intValue())
        assertEquals("Магазин", new["name"].stringValue())
        assertEquals("+79991234567", new["contacts.phone"].stringValue())
        assertEquals(1, new["version"].intValue())
        assertEquals("{\"required\":[\"name\",\"email\"],\"any_of\":[[\"phone\",\"telegram\"]]}", new["checkoutContactRules"].stringValue())
        assertEquals(1, settingsAudits())
    }

    @Test
    fun `normalized repeat keeps database row timestamp version and audit unchanged`() {
        assertEquals(200, update(changed()).statusCode())
        val before = row()
        val response = update(changed() + ("version" to 1))
        assertEquals(200, response.statusCode(), response.body())
        assertEquals(1, json.readTree(response.body())["version"].intValue())
        assertEquals(before, row())
        assertEquals(1, settingsAudits())
    }

    @Test
    fun `stale version conflicts even when submitted values match current settings`() {
        assertEquals(200, update(changed()).statusCode())
        val before = row()
        listOf(changed(), changed() + ("name" to "Другое имя")).forEach {
            val response = update(it)
            assertProblem(response, 409, "SETTINGS_VERSION_CONFLICT")
            assertEquals("The shop settings version is outdated.", json.readTree(response.body())["detail"].stringValue())
            assertEquals(before, row())
        }
        assertEquals(1, settingsAudits())
    }

    @Test
    fun `nullable fields clear persisted contacts and colors`() {
        assertEquals(200, update(changed()).statusCode())
        val response = update(input() + ("version" to 1))
        assertEquals(200, response.statusCode(), response.body())
        val saved = row()
        listOf("phone", "email", "telegram", "other_contact", "primary_color", "background_color").forEach { assertNull(saved[it]) }
        assertEquals(2L, saved["version"])
        assertEquals(2, settingsAudits())
        assertEquals(json.readTree(response.body()), read())
    }

    @Test
    fun `invalid and unknown input never partially updates settings or audit`() {
        val before = row()
        listOf(
            changed() + ("name" to "x".repeat(101)),
            changed() + ("primaryColor" to "#bad"),
        ).forEach { assertProblem(update(it), 400, "VALIDATION_ERROR") }
        listOf(
            changed() - "backgroundColor",
            changed() + ("published" to true),
            changed() + ("version" to "0"),
            changed() + ("contacts" to mapOf("phone" to null, "email" to null, "telegram" to null)),
        ).forEach { assertProblem(update(it), 400, "MALFORMED_JSON") }
        assertEquals(before, row())
        assertEquals(0, settingsAudits())
    }

    @Test
    fun `parallel updates of one version allow exactly one change`() {
        val callers = listOf(owner, authenticatedBrowser())
        val start = CountDownLatch(1)
        Executors.newFixedThreadPool(2).use { executor ->
            val futures = callers.mapIndexed { index, browser ->
                executor.submit<HttpResponse<String>> {
                    check(start.await(5, TimeUnit.SECONDS))
                    update(changed() + ("name" to "Store $index"), browser)
                }
            }
            start.countDown()
            val results = futures.map { it.get(15, TimeUnit.SECONDS) }
            assertEquals(listOf(200, 409), results.map { it.statusCode() }.sorted())
            assertProblem(results.single { it.statusCode() == 409 }, 409, "SETTINGS_VERSION_CONFLICT")
            assertEquals(json.readTree(results.single { it.statusCode() == 200 }.body()), read())
        }
        assertEquals(1L, row()["version"])
        assertEquals(1, settingsAudits())
    }

    @Test
    fun `audit failure rolls back settings timestamp and version without exposing input`(output: CapturedOutput) {
        val before = row()
        jdbc.execute("ALTER TABLE audit_log ADD CONSTRAINT test_fail_settings_audit CHECK (action <> 'SHOP_SETTINGS_UPDATED')")
        try {
            val response = update(changed() + ("name" to "BROKEN_STORE_PRIVATE"))
            assertProblem(response, 500, "INTERNAL_ERROR")
            assertFalse(response.body().contains("BROKEN_STORE_PRIVATE"))
            assertFalse(output.all.contains("BROKEN_STORE_PRIVATE"))
            assertTrue(output.all.contains("23514"))
            assertTrue(output.all.contains("test_fail_settings_audit"))
            assertEquals(before, row())
            assertEquals(0, settingsAudits())
        } finally {
            jdbc.execute("ALTER TABLE audit_log DROP CONSTRAINT test_fail_settings_audit")
        }
    }

    private fun row(): Map<String, Any?> = jdbc.queryForMap("SELECT *, xmin::text AS row_version FROM shop_settings")

    private fun settingsAudits(): Int =
        jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'SHOP_SETTINGS_UPDATED'", Int::class.java)!!

    private fun read(): JsonNode {
        val response = send(owner, "GET", PATH)
        assertEquals(200, response.statusCode(), response.body())
        return json.readTree(response.body())
    }

    private fun update(input: Map<String, Any?>, browser: Browser = owner): HttpResponse<String> =
        send(browser, "PUT", PATH, json.writeValueAsString(input))

    private fun input(): Map<String, Any?> = linkedMapOf(
        "name" to "", "description" to "", "salesTerms" to "",
        "contacts" to mapOf("phone" to null, "email" to null, "telegram" to null, "otherContact" to null),
        "checkoutContactRules" to
            mapOf("required" to listOf("name"), "any_of" to listOf(listOf("phone", "email", "telegram", "other_contact"))),
        "primaryColor" to null, "backgroundColor" to null, "timezone" to "Europe/Moscow", "version" to 0,
    )

    private fun changed(): Map<String, Any?> = input() + mapOf(
        "name" to " Магазин ",
        "description" to " Первая\nВторая ",
        "salesTerms" to " Условия продажи ",
        "contacts" to mapOf(
            "phone" to " +7 (999) 123-45-67 ",
            "email" to " owner@example.com ",
            "telegram" to " @owner ",
            "otherContact" to " Другой контакт ",
        ),
        "checkoutContactRules" to mapOf("required" to listOf("name", "email"), "any_of" to listOf(listOf("phone", "telegram"))),
        "primaryColor" to "#aBcDef",
        "backgroundColor" to "#ffffff",
        "timezone" to " Etc/UTC ",
    )

    private fun browser(): Browser = Browser(
        HttpClient.newBuilder().cookieHandler(CookieManager(null, CookiePolicy.ACCEPT_ALL)).build(),
    ).also {
        browsers.add(it)
        csrf(it)
    }

    private fun authenticatedBrowser(): Browser = browser().also {
        val response = send(it, "POST", "/api/admin/auth/login", json.writeValueAsString(mapOf("login" to "owner", "password" to PASSWORD)))
        assertEquals(200, response.statusCode(), response.body())
        csrf(it)
    }

    private fun csrf(browser: Browser) {
        val response = send(browser, "GET", "/api/csrf")
        assertEquals(200, response.statusCode(), response.body())
        browser.token = json.readTree(response.body())["token"].stringValue()
    }

    private fun send(browser: Browser, method: String, path: String, body: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
        if (method != "GET") browser.token?.let { request.header("X-CSRF-TOKEN", it) }
        if (body != null) request.header("Content-Type", "application/json")
        request.method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
        return browser.client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun assertProblem(response: HttpResponse<String>, status: Int, code: String) {
        assertEquals(status, response.statusCode(), response.body())
        assertTrue(response.headers().firstValue("content-type").orElseThrow().startsWith("application/problem+json"))
        assertEquals(code, json.readTree(response.body())["code"].stringValue())
    }

    private class Browser(val client: HttpClient, var token: String? = null)

    private companion object {
        const val PATH = "/api/admin/settings"
        const val PASSWORD = "123456789012345"
    }
}
