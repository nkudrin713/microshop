package dev.nkudrin.microshop.setup

import dev.nkudrin.microshop.PostgresTestConfiguration
import dev.nkudrin.microshop.TestInstallationSecrets
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(PostgresTestConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
class SetupIntegrationTest {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var initializer: InstallationService

    @Autowired private lateinit var passwords: PasswordEncoder

    @Autowired private lateinit var json: JsonMapper

    @LocalServerPort private var port: Int = 0

    @BeforeEach
    fun resetInstallation() {
        jdbc.update("DELETE FROM audit_log")
        jdbc.update("DELETE FROM installation_state")
        jdbc.update("DELETE FROM admin_user")
        initializer.initialize(TestInstallationSecrets.appSecret, TestInstallationSecrets.setupToken)
    }

    @Test
    fun `setup creates one normalized owner with safe audit and no authentication`(output: CapturedOutput) {
        mvc
            .perform(get("/api/setup/status"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.initialized").value(false))
            .andExpect(jsonPath("$").isMap)
            .andExpect(jsonPath("$.owner").doesNotExist())
        val password = "  пароль😀123456789  "
        val result =
            create(password = password)
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.login").value("owner01"))
                .andExpect(jsonPath("$.displayName").value("Владелец"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.setupToken").doesNotExist())
                .andReturn()
        assertEquals(3, json.readTree(result.response.contentAsString).size())
        val owner = jdbc.queryForMap("SELECT * FROM admin_user")
        val hash = owner["password_hash"] as String
        assertTrue(hash.startsWith("\$argon2id\$v=19\$m=19456,t=2,p=1\$"))
        assertTrue(passwords.matches(password, hash))
        assertFalse(passwords.matches(password.trim(), hash))
        assertFalse(passwords.matches("another password", hash))
        assertTrue(hash != passwords.encode(password), "Argon2 must use a new salt")
        val state = jdbc.queryForMap("SELECT * FROM installation_state")
        assertEquals("INITIALIZED", state["status"])
        assertEquals(owner["id"], state["owner_admin_id"])
        assertNull(state["setup_token_hash"])
        assertTrue(state["initialized_at"] != null)
        assertTrue(
            (state["app_secret_hash"] as ByteArray).contentEquals(
                SetupSecrets.hash(SetupSecrets.decode(TestInstallationSecrets.appSecret)!!),
            ),
        )
        val audit = jdbc.queryForMap("SELECT * FROM audit_log")
        assertEquals("OWNER_CREATED", audit["action"])
        assertEquals("ADMIN_USER", audit["entity_type"])
        assertEquals("ADMIN", audit["actor_type"])
        assertEquals(owner["id"], audit["actor_admin_id"])
        assertNull(audit["before_data"])
        val snapshot = json.readTree(audit["after_data"].toString())
        assertEquals(4, snapshot.size())
        assertEquals("owner01", snapshot["login"].stringValue())
        assertEquals("Владелец", snapshot["displayName"].stringValue())
        assertTrue(snapshot["enabled"].asBoolean())
        assertNull((result.request.getSession(false) as? MockHttpSession)?.getAttribute("SPRING_SECURITY_CONTEXT"))
        mvc
            .perform(get("/api/admin/settings"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
        mvc
            .perform(get("/api/setup/status"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.initialized").value(true))
        create(token = "invalid")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("SETUP_ALREADY_INITIALIZED"))
        listOf(password, hash, TestInstallationSecrets.setupToken, TestInstallationSecrets.appSecret).forEach {
            assertFalse(output.all.contains(it), "Secrets must not appear in logs")
            assertFalse(result.response.contentAsString.contains(it), "Secrets must not appear in responses")
            assertFalse(audit["after_data"].toString().contains(it), "Secrets must not appear in audit")
        }
        assertEquals(1, count("admin_user"))
    }

    @Test
    fun `wrong setup tokens do not consume stored token`() {
        listOf("", "invalid", TestInstallationSecrets.randomSecret(), TestInstallationSecrets.setupToken + "=").forEach {
            create(token = it)
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("SETUP_TOKEN_INVALID"))
            assertUninitialized()
        }
        create().andExpect(status().isCreated)
    }

    @ParameterizedTest
    @MethodSource("invalidOwnerValues")
    fun `invalid owner data leaves setup available`(values: List<String>) {
        create(login = values[0], password = values[1], name = values[2])
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        assertUninitialized()
    }

    @Test
    fun `null character in display name is rejected without consuming setup token`() {
        create(name = "Owner\u0000Name")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        assertUninitialized()
        create().andExpect(status().isCreated)
    }

    @Test
    fun `Unicode lengths count code points and preserve password spaces`() {
        create(login = "A".repeat(64), password = "😀".repeat(128), name = "😀".repeat(100))
            .andExpect(status().isCreated)
        val owner = jdbc.queryForMap("SELECT * FROM admin_user")
        assertEquals("a".repeat(64), owner["login"])
        assertTrue(passwords.matches("😀".repeat(128), owner["password_hash"] as String))
    }

    @Test
    fun `malformed or missing JSON does not initialize installation`() {
        val (session, token) = csrf()
        listOf("", "{", "{}", """{"setupToken":null,"login":"owner","password":"123456789012345","displayName":"Owner"}""").forEach {
            mvc
                .perform(
                    post("/api/setup/owner")
                        .session(session)
                        .header("X-CSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(it),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("MALFORMED_JSON"))
            assertUninitialized()
        }
    }

    @Test
    fun `CSRF is checked before credentials and denies every mutation`() {
        val (session, _) = csrf()
        listOf("/api/setup/owner", "/api/admin/settings", "/api/admin/auth/login").forEach { path ->
            mvc
                .perform(post(path).session(session).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"))
            mvc
                .perform(post(path).session(session).header("X-CSRF-TOKEN", "invalid"))
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"))
        }
        assertUninitialized()
    }

    @Test
    fun `parallel setup requests create one owner and one audit`() {
        val callers = listOf(csrf(), csrf())
        val start = CountDownLatch(1)
        Executors.newFixedThreadPool(2).use { executor ->
            val futures =
                callers.map { (session, token) ->
                    executor.submit<Int> {
                        check(start.await(5, TimeUnit.SECONDS))
                        mvc
                            .perform(
                                post("/api/setup/owner")
                                    .session(session)
                                    .header("X-CSRF-TOKEN", token)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body()),
                            ).andReturn()
                            .response.status
                    }
                }
            start.countDown()
            assertEquals(listOf(201, 409), futures.map { it.get(15, TimeUnit.SECONDS) }.sorted())
        }
        assertEquals(1, count("admin_user"))
        assertEquals(1, count("audit_log"))
    }

    @Test
    fun `audit database failure rolls back owner state and token`() {
        jdbc.execute("ALTER TABLE audit_log ADD CONSTRAINT test_fail_owner_audit CHECK (action <> 'OWNER_CREATED')")
        try {
            create().andExpect(status().isInternalServerError).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            assertUninitialized()
        } finally {
            jdbc.execute("ALTER TABLE audit_log DROP CONSTRAINT test_fail_owner_audit")
        }
        create().andExpect(status().isCreated)
    }

    @Test
    fun `dev HTTP issues session cookie without weakening CSRF and health works before setup`() {
        HttpClient.newHttpClient().use { client ->
            fun fetch(path: String) =
                client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).GET().build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, fetch("/actuator/health").statusCode())
            val result = fetch("/api/csrf")
            assertEquals(200, result.statusCode())
            val cookie = result.headers().firstValue("set-cookie").orElseThrow()
            assertTrue(cookie.startsWith("MICROSHOP_SESSION="))
            assertTrue(cookie.contains("HttpOnly") && cookie.contains("SameSite=Lax") && cookie.contains("Path=/"))
            assertFalse(cookie.contains("Secure") || cookie.contains("Max-Age") || cookie.contains("Domain="))
        }
    }

    private fun csrf(): Pair<MockHttpSession, String> {
        val result = mvc.perform(get("/api/csrf")).andExpect(status().isOk).andReturn()
        assertEquals("no-store", result.response.getHeader("Cache-Control"))
        val payload = json.readTree(result.response.contentAsString)
        assertEquals("X-CSRF-TOKEN", payload["headerName"].stringValue())
        return result.request.getSession(false) as MockHttpSession to payload["token"].stringValue()
    }

    private fun create(
        token: String = TestInstallationSecrets.setupToken,
        login: String = "  OWNER01  ",
        password: String = "123456789012345",
        name: String = "  Владелец  ",
    ) = csrf().let { (session, csrf) ->
        mvc.perform(
            post("/api/setup/owner")
                .session(session)
                .header("X-CSRF-TOKEN", csrf)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(token, login, password, name)),
        )
    }

    private fun body(
        token: String = TestInstallationSecrets.setupToken,
        login: String = "owner01",
        password: String = "123456789012345",
        name: String = "Owner",
    ): String = json.writeValueAsString(mapOf("setupToken" to token, "login" to login, "password" to password, "displayName" to name))

    private fun count(table: String): Int = jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java)!!

    private fun assertUninitialized() {
        val state = jdbc.queryForMap("SELECT * FROM installation_state")
        assertEquals("UNINITIALIZED", state["status"])
        assertTrue((state["setup_token_hash"] as ByteArray).contentEquals(SetupSecrets.tokenHash(TestInstallationSecrets.setupToken)))
        assertNull(state["owner_admin_id"])
        assertNull(state["initialized_at"])
        assertEquals(0, count("admin_user"))
        assertEquals(0, count("audit_log"))
    }

    companion object {
        @JvmStatic
        fun invalidOwnerValues(): List<List<String>> =
            listOf(
                listOf("ab", "123456789012345", "Owner"),
                listOf("a".repeat(65), "123456789012345", "Owner"),
                listOf("owner_name", "123456789012345", "Owner"),
                listOf("Kowner", "123456789012345", "Owner"),
                listOf("владелец", "123456789012345", "Owner"),
                listOf("owner", "😀".repeat(14), "Owner"),
                listOf("owner", "😀".repeat(129), "Owner"),
                listOf("owner", "123456789012345", "   "),
                listOf("owner", "123456789012345", "😀".repeat(101)),
            )
    }
}
