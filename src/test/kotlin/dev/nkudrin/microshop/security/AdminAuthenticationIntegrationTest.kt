package dev.nkudrin.microshop.security

import dev.nkudrin.microshop.PostgresTestConfiguration
import dev.nkudrin.microshop.TestInstallationSecrets
import dev.nkudrin.microshop.setup.CreateOwnerRequest
import dev.nkudrin.microshop.setup.InstallationService
import dev.nkudrin.microshop.setup.SetupService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.doReturn
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.context.SecurityContext
import org.springframework.session.FindByIndexNameSessionRepository
import org.springframework.session.Session
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Import(PostgresTestConfiguration::class)
class AdminAuthenticationIntegrationTest {
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var installation: InstallationService

    @Autowired private lateinit var setup: SetupService

    @Autowired private lateinit var sessions: FindByIndexNameSessionRepository<out Session>

    @Autowired private lateinit var json: JsonMapper

    @MockitoBean private lateinit var clock: Clock

    @LocalServerPort private var port: Int = 0

    private lateinit var client: HttpClient
    private lateinit var ownerId: UUID
    private val password = "  password😀123456789  "

    @BeforeEach
    fun prepareOwner() {
        doReturn(Instant.ofEpochSecond(time.addAndGet(901))).`when`(clock).instant()
        client = HttpClient.newHttpClient()
        resetInstallation()
        ownerId = setup.createOwner(CreateOwnerRequest(TestInstallationSecrets.setupToken, "OWNER01", password, "Owner")).id
    }

    @AfterEach
    fun closeClient() {
        client.close()
    }

    @Test
    fun `login limit ignores CSRF and malformed JSON rejects forged IP and expires after fifteen minutes`() {
        val browser = Browser()
        csrf(browser)
        repeat(9) { assertProblem(login(browser, secret = "wrong-password-123456789"), 401, "INVALID_CREDENTIALS") }
        assertProblem(send(browser, "POST", "/api/admin/auth/login", "{"), 400, "MALFORMED_JSON")
        val validToken = browser.token
        browser.token = null
        assertProblem(login(browser), 403, "CSRF_TOKEN_INVALID")
        browser.token = validToken
        assertProblem(login(browser, secret = "wrong-password-123456789"), 401, "INVALID_CREDENTIALS")
        val blocked = login(browser)
        assertProblem(blocked, 429, "LOGIN_RATE_LIMITED")
        assertEquals("900", blocked.headers().firstValue("retry-after").orElseThrow())
        assertEquals("Too many failed login attempts. Try again later.", json.readTree(blocked.body())["detail"].stringValue())
        val credentials = json.writeValueAsString(mapOf("login" to "owner01", "password" to password))
        assertProblem(
            send(browser, "POST", "/api/admin/auth/login", credentials, forwardedFor = "203.0.113.17"),
            429,
            "LOGIN_RATE_LIMITED",
        )
        assertTrue(sessions.findByPrincipalName(ownerId.toString()).isEmpty())
        val firstFailure = Instant.ofEpochSecond(time.get())
        doReturn(firstFailure.plusSeconds(899).plusMillis(500)).`when`(clock).instant()
        assertEquals("1", login(browser).headers().firstValue("retry-after").orElseThrow())
        doReturn(firstFailure.plusSeconds(900)).`when`(clock).instant()
        assertEquals(200, login(browser).statusCode())
        assertEquals(200, send(browser, "GET", "/api/admin/auth/me").statusCode())
    }

    @Test
    fun `login rotates session and CSRF and persists only owner identity`() {
        val browser = Browser()
        csrf(browser)
        val anonymousCookie = browser.cookie
        assertProblem(send(browser, "GET", "/api/admin/auth/me"), 401, "AUTHENTICATION_REQUIRED")
        val response = login(browser, login = "  OwNeR01  ")
        assertEquals(200, response.statusCode())
        val profile = json.readTree(response.body())
        assertEquals(3, profile.size())
        assertEquals(ownerId.toString(), profile["id"].stringValue())
        assertEquals("owner01", profile["login"].stringValue())
        assertEquals("Owner", profile["displayName"].stringValue())
        assertNotEquals(anonymousCookie, browser.cookie)

        val session = sessions.findByPrincipalName(ownerId.toString()).values.single()
        val authentication = checkNotNull(checkNotNull(session.getAttribute<SecurityContext>("SPRING_SECURITY_CONTEXT")).authentication)
        assertEquals(ownerId, authentication.principal)
        assertEquals(ownerId.toString(), authentication.name)
        assertNull(authentication.credentials)
        assertNull(authentication.details)
        assertProblem(send(Browser(cookie = anonymousCookie), "GET", "/api/admin/auth/me"), 401, "AUTHENTICATION_REQUIRED")
        assertEquals(200, send(browser, "GET", "/api/admin/auth/me").statusCode())
        assertProblem(send(browser, "POST", "/api/admin/auth/logout"), 403, "CSRF_TOKEN_INVALID")
        csrf(browser)
        assertEquals(204, send(browser, "POST", "/api/admin/auth/logout").statusCode())
    }

    @Test
    fun `unknown login wrong password and disabled owner have identical safe errors`() {
        val browser = Browser()
        csrf(browser)
        val responses =
            mutableListOf(
                login(browser, login = "unknown"),
                login(browser, secret = "wrong-password-123456789"),
                login(browser, secret = password.trim()),
            )
        jdbc.update("UPDATE admin_user SET enabled = false WHERE id = ?", ownerId)
        responses += login(browser)
        responses.forEach { response ->
            assertProblem(response, 401, "INVALID_CREDENTIALS")
            assertEquals("Invalid login or password.", json.readTree(response.body())["detail"].stringValue())
            assertFalse(response.body().contains(password))
        }
        assertEquals(1, responses.map { it.body() }.distinct().size)
        assertTrue(sessions.findByPrincipalName(ownerId.toString()).isEmpty())
    }

    @Test
    fun `login before setup cannot authenticate and credentials are checked after CSRF`() {
        resetInstallation()
        val browser = Browser()
        assertProblem(login(browser), 403, "CSRF_TOKEN_INVALID")
        csrf(browser)
        assertProblem(login(browser), 401, "INVALID_CREDENTIALS")
        assertProblem(send(browser, "POST", "/api/admin/auth/login", "{}"), 400, "MALFORMED_JSON")
        assertProblem(send(browser, "GET", "/api/admin/auth/me"), 401, "AUTHENTICATION_REQUIRED")
    }

    @Test
    fun `me reads current profile and logout only removes current session`() {
        val first = authenticatedBrowser()
        val second = authenticatedBrowser()
        jdbc.update("UPDATE admin_user SET display_name = 'Updated' WHERE id = ?", ownerId)
        val me = send(first, "GET", "/api/admin/auth/me")
        assertEquals(200, me.statusCode())
        assertEquals("Updated", json.readTree(me.body())["displayName"].stringValue())
        val oldCookie = first.cookie
        val response = send(first, "POST", "/api/admin/auth/logout")
        assertEquals(204, response.statusCode())
        assertEquals("", response.body())
        val cookie = response.headers().firstValue("set-cookie").orElseThrow()
        assertTrue(cookie.contains("Max-Age=0"))
        assertEquals(1, sessions.findByPrincipalName(ownerId.toString()).size)
        assertProblem(send(Browser(cookie = oldCookie), "GET", "/api/admin/auth/me"), 401, "AUTHENTICATION_REQUIRED")
        assertEquals(200, send(second, "GET", "/api/admin/auth/me").statusCode())
        csrf(first)
        assertEquals(204, send(first, "POST", "/api/admin/auth/logout").statusCode())
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM spring_session", Int::class.java))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM audit_log", Int::class.java))
    }

    @Test
    fun `logout all requires authentication and revokes both browser sessions`() {
        val anonymous = Browser()
        csrf(anonymous)
        assertProblem(send(anonymous, "POST", "/api/admin/auth/logout-all"), 401, "AUTHENTICATION_REQUIRED")
        val first = authenticatedBrowser()
        val second = authenticatedBrowser()
        assertEquals(2, sessions.findByPrincipalName(ownerId.toString()).size)
        assertEquals(204, send(first, "POST", "/api/admin/auth/logout-all").statusCode())
        assertTrue(sessions.findByPrincipalName(ownerId.toString()).isEmpty())
        assertProblem(send(first, "GET", "/api/admin/auth/me"), 401, "AUTHENTICATION_REQUIRED")
        assertProblem(send(second, "GET", "/api/admin/auth/me"), 401, "AUTHENTICATION_REQUIRED")
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM audit_log", Int::class.java))
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `disabled or deleted owner loses existing session on next protected request`(disable: Boolean) {
        val browser = authenticatedBrowser()
        if (disable) {
            jdbc.update("UPDATE admin_user SET enabled = false WHERE id = ?", ownerId)
        } else {
            jdbc.update("DELETE FROM audit_log")
            jdbc.update("DELETE FROM installation_state")
            jdbc.update("DELETE FROM admin_user")
        }
        assertProblem(send(browser, "GET", "/api/admin/auth/me"), 401, "AUTHENTICATION_REQUIRED")
        assertTrue(sessions.findByPrincipalName(ownerId.toString()).isEmpty())
        if (disable) {
            jdbc.update("UPDATE admin_user SET enabled = true WHERE id = ?", ownerId)
            assertProblem(send(browser, "GET", "/api/admin/auth/me"), 401, "AUTHENTICATION_REQUIRED")
        }
    }

    @Test
    fun `expired authenticated session cannot read profile and fresh anonymous logout is idempotent`() {
        val browser = authenticatedBrowser()
        val expiry = System.currentTimeMillis() - 1000
        jdbc.update("UPDATE spring_session SET last_access_time = ?, expiry_time = ?", expiry - Duration.ofHours(24).toMillis(), expiry)
        assertProblem(send(browser, "GET", "/api/admin/auth/me"), 401, "AUTHENTICATION_REQUIRED")
        assertProblem(send(browser, "POST", "/api/admin/auth/logout"), 403, "CSRF_TOKEN_INVALID")
        csrf(browser)
        assertEquals(204, send(browser, "POST", "/api/admin/auth/logout").statusCode())
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM spring_session", Int::class.java))
    }

    @ParameterizedTest
    @EnumSource(AccessState::class)
    fun `health setup status and CSRF are public in every installation state`(state: AccessState) {
        val browser = accessBrowser(state)
        assertEquals(200, send(browser, "GET", "/actuator/health").statusCode())
        val status = send(browser, "GET", "/api/setup/status")
        assertEquals(200, status.statusCode())
        val payload = json.readTree(status.body())
        assertEquals(1, payload.size())
        assertEquals(state != AccessState.BEFORE_SETUP, payload["initialized"].asBoolean())
        val token = send(browser, "GET", "/api/csrf")
        assertEquals(200, token.statusCode())
        assertEquals("no-store", token.headers().firstValue("cache-control").orElseThrow())
        assertEquals("X-CSRF-TOKEN", json.readTree(token.body())["headerName"].stringValue())
        assertTrue(json.readTree(token.body())["token"].stringValue().isNotBlank())
        val profile = send(browser, "GET", "/api/admin/auth/me")
        if (state == AccessState.OWNER) {
            assertEquals(200, profile.statusCode())
        } else {
            assertProblem(profile, 401, "AUTHENTICATION_REQUIRED")
        }
    }

    @ParameterizedTest
    @EnumSource(AccessState::class)
    fun `login checks credentials even with an owner session and cannot succeed before setup`(state: AccessState) {
        val browser = accessBrowser(state)
        csrf(browser)
        assertProblem(login(browser, secret = "wrong-password-123456789"), 401, "INVALID_CREDENTIALS")
        val response = login(browser)
        if (state == AccessState.BEFORE_SETUP) {
            assertProblem(response, 401, "INVALID_CREDENTIALS")
            assertProblem(send(browser, "GET", "/api/admin/auth/me"), 401, "AUTHENTICATION_REQUIRED")
        } else {
            assertEquals(200, response.statusCode())
            val profile = send(browser, "GET", "/api/admin/auth/me")
            assertEquals(200, profile.statusCode())
            assertEquals(ownerId.toString(), json.readTree(profile.body())["id"].stringValue())
        }
    }

    @ParameterizedTest
    @EnumSource(AccessState::class)
    fun `admin access requires owner and both logout operations follow the access matrix`(state: AccessState) {
        val browser = accessBrowser(state)
        csrf(browser)
        val owner = state == AccessState.OWNER
        val profile = send(browser, "GET", "/api/admin/auth/me")
        if (owner) {
            assertEquals(200, profile.statusCode())
        } else {
            assertProblem(profile, 401, "AUTHENTICATION_REQUIRED")
        }
        listOf("PUT", "PATCH", "DELETE").forEach { method ->
            val response = send(browser, method, "/api/admin/auth/me")
            if (owner) {
                assertProblem(response, 405, "METHOD_NOT_ALLOWED")
            } else {
                assertProblem(response, 401, "AUTHENTICATION_REQUIRED")
            }
        }
        val all = send(browser, "POST", "/api/admin/auth/logout-all")
        if (owner) {
            assertEquals(204, all.statusCode())
        } else {
            assertProblem(all, 401, "AUTHENTICATION_REQUIRED")
        }
        csrf(browser)
        assertEquals(204, send(browser, "POST", "/api/admin/auth/logout").statusCode())
        assertProblem(send(browser, "GET", "/api/admin/auth/me"), 401, "AUTHENTICATION_REQUIRED")
    }

    @ParameterizedTest
    @EnumSource(AccessState::class)
    fun `unlisted routes and other actuator endpoints stay closed with API errors even for HTML clients`(state: AccessState) {
        val browser = accessBrowser(state)
        listOf("/actuator", "/actuator/env", "/actuator/info", "/api/unknown").forEach { path ->
            val response = send(browser, "GET", path, accept = "text/html")
            if (state == AccessState.OWNER) {
                assertProblem(response, 403, "ACCESS_DENIED")
                assertEquals("Access is denied.", json.readTree(response.body())["detail"].stringValue())
            } else {
                assertProblem(response, 401, "AUTHENTICATION_REQUIRED")
                assertEquals("Authentication is required.", json.readTree(response.body())["detail"].stringValue())
            }
        }
    }

    @ParameterizedTest
    @EnumSource(AccessState::class)
    fun `missing and invalid CSRF reject every mutation before authentication or request body parsing`(state: AccessState) {
        val browser = accessBrowser(state)
        csrf(browser)
        val valid = browser.token
        val before = state != AccessState.BEFORE_SETUP
        val requests =
            listOf(
                "POST" to "/api/setup/owner",
                "POST" to "/api/admin/auth/login",
                "POST" to "/api/admin/auth/logout",
                "POST" to "/api/admin/auth/logout-all",
                "PUT" to "/api/admin/settings",
                "PATCH" to "/api/admin/settings",
                "DELETE" to "/api/admin/settings",
            )
        listOf(null, "invalid").forEach { token ->
            browser.token = token
            requests.forEach { (method, path) ->
                val response = send(browser, method, path, body = "{", accept = "text/html")
                assertProblem(response, 403, "CSRF_TOKEN_INVALID")
                assertEquals("The CSRF token is missing or invalid.", json.readTree(response.body())["detail"].stringValue())
            }
        }
        browser.token = valid
        assertEquals(before, json.readTree(send(browser, "GET", "/api/setup/status").body())["initialized"].asBoolean())
        if (state == AccessState.OWNER) {
            assertEquals(200, send(browser, "GET", "/api/admin/auth/me").statusCode())
        }
    }

    @ParameterizedTest
    @EnumSource(AccessState::class)
    fun `setup accepts anonymous token only before initialization and never signs the owner in`(state: AccessState) {
        val browser = accessBrowser(state)
        csrf(browser)
        val body = json.writeValueAsString(CreateOwnerRequest(TestInstallationSecrets.setupToken, "owner01", password, "Owner"))
        val response = send(browser, "POST", "/api/setup/owner", body)
        if (state == AccessState.BEFORE_SETUP) {
            assertEquals(201, response.statusCode())
            assertProblem(send(browser, "GET", "/api/admin/auth/me"), 401, "AUTHENTICATION_REQUIRED")
        } else {
            assertProblem(response, 409, "SETUP_ALREADY_INITIALIZED")
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM admin_user", Int::class.java))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM audit_log", Int::class.java))
    }

    private fun accessBrowser(state: AccessState): Browser = when (state) {
        AccessState.BEFORE_SETUP -> {
            resetInstallation()
            Browser()
        }

        AccessState.ANONYMOUS -> Browser()

        AccessState.OWNER -> authenticatedBrowser()
    }

    private fun resetInstallation() {
        jdbc.update("DELETE FROM spring_session")
        jdbc.update("DELETE FROM audit_log")
        jdbc.update("DELETE FROM installation_state")
        jdbc.update("DELETE FROM admin_user")
        installation.initialize(TestInstallationSecrets.appSecret, TestInstallationSecrets.setupToken)
    }

    private fun authenticatedBrowser(): Browser = Browser().also {
        csrf(it)
        assertEquals(200, login(it).statusCode())
        csrf(it)
    }

    private fun csrf(browser: Browser) {
        val response = send(browser, "GET", "/api/csrf")
        assertEquals(200, response.statusCode())
        browser.token = json.readTree(response.body())["token"].stringValue()
    }

    private fun login(browser: Browser, login: String = "owner01", secret: String = password): HttpResponse<String> = send(
        browser,
        "POST",
        "/api/admin/auth/login",
        json.writeValueAsString(mapOf("login" to login, "password" to secret)),
    )

    private fun send(
        browser: Browser,
        method: String,
        path: String,
        body: String? = null,
        accept: String? = null,
        forwardedFor: String? = null,
    ): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(5))
        browser.cookie?.let { request.header("Cookie", it) }
        if (method in setOf("POST", "PUT", "PATCH", "DELETE")) browser.token?.let { request.header("X-CSRF-TOKEN", it) }
        accept?.let { request.header("Accept", it) }
        forwardedFor?.let { request.header("X-Forwarded-For", it) }
        if (body != null) request.header("Content-Type", "application/json")
        request.method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
        val response = client.send(request.build(), HttpResponse.BodyHandlers.ofString())
        response.headers().firstValue("set-cookie").ifPresent {
            if (it.contains("Max-Age=0")) {
                browser.cookie = null
                browser.token = null
            } else {
                browser.cookie = it.substringBefore(';')
            }
        }
        return response
    }

    private fun assertProblem(response: HttpResponse<String>, status: Int, code: String) {
        assertEquals(status, response.statusCode(), response.body())
        val contentType = response.headers().firstValue("content-type").orElseThrow()
        assertTrue(contentType.startsWith("application/problem+json"))
        val problem = json.readTree(response.body())
        assertEquals(code, problem["code"].stringValue())
        assertEquals(status, problem["status"].asInt())
        assertEquals("about:blank", problem["type"].stringValue())
        assertEquals(response.uri().path, problem["instance"].stringValue())
        assertTrue(response.headers().firstValue("location").isEmpty)
        assertTrue(response.headers().firstValue("www-authenticate").isEmpty)
    }

    enum class AccessState { BEFORE_SETUP, ANONYMOUS, OWNER }

    private data class Browser(var cookie: String? = null, var token: String? = null)

    private companion object {
        val time = AtomicLong()
    }
}
