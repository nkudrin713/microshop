package dev.nkudrin.microshop.setup

import dev.nkudrin.microshop.MicroshopApplication
import dev.nkudrin.microshop.PostgresTestConfiguration
import dev.nkudrin.microshop.TestInstallationSecrets
import dev.nkudrin.microshop.api.ApiError
import dev.nkudrin.microshop.api.ApiException
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.annotation.DirtiesContext
import org.testcontainers.postgresql.PostgreSQLContainer
import java.io.IOException
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.SQLException
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest(classes = [InstallationStartupTest.ContainerHost::class], webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class InstallationStartupTest {
    @Autowired
    private lateinit var postgres: PostgreSQLContainer

    @Test
    fun `restart preserves uninitialized token and initialized owner and key hash`() {
        val jdbc = database()
        val originalToken = TestInstallationSecrets.setupToken
        var ownerId: UUID? = null
        start().use { }
        val keyHash = jdbc.queryForObject("SELECT app_secret_hash FROM installation_state", ByteArray::class.java)!!
        listOf("", "invalid", TestInstallationSecrets.randomSecret()).forEach { replacement ->
            start("--microshop.setup-token=$replacement").use {
                assertTrue(
                    jdbc
                        .queryForObject("SELECT setup_token_hash FROM installation_state", ByteArray::class.java)!!
                        .contentEquals(SetupSecrets.tokenHash(originalToken)),
                )
            }
        }
        start("--microshop.setup-token=ignored").use { context ->
            ownerId =
                context
                    .getBean(SetupService::class.java)
                    .createOwner(CreateOwnerRequest(originalToken, "OWNER", "123456789012345", "Owner"))
                    .id
        }
        val initializedAt = jdbc.queryForMap("SELECT initialized_at FROM installation_state")["initialized_at"]
        start("--microshop.setup-token=").use { context ->
            assertTrue(context.getBean(SetupService::class.java).status().initialized)
            val state = jdbc.queryForMap("SELECT * FROM installation_state")
            assertEquals(ownerId, state["owner_admin_id"])
            assertEquals(initializedAt, state["initialized_at"])
            assertNull(state["setup_token_hash"])
            assertTrue(keyHash.contentEquals(state["app_secret_hash"] as ByteArray))
            val failure =
                assertFailsWith<ApiException> {
                    context
                        .getBean(
                            SetupService::class.java,
                        ).createOwner(CreateOwnerRequest("ignored", "owner", "123456789012345", "Owner"))
                }
            assertEquals(ApiError.SETUP_ALREADY_INITIALIZED, failure.error)
        }
    }

    @Test
    fun `invalid or changed APP_SECRET aborts startup without replacing state`() {
        val jdbc = database()
        listOf("", "invalid", TestInstallationSecrets.appSecret + "=", " " + TestInstallationSecrets.appSecret).forEach {
            assertStartupRejected("--microshop.app-secret=$it", expectedMessage = "APP_SECRET must be canonical")
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM installation_state", Int::class.java))
        }
        start().use { }
        val original = jdbc.queryForObject("SELECT app_secret_hash FROM installation_state", ByteArray::class.java)!!
        assertStartupRejected(
            "--microshop.app-secret=${TestInstallationSecrets.randomSecret()}",
            expectedMessage = "APP_SECRET does not match",
        )
        assertTrue(original.contentEquals(jdbc.queryForObject("SELECT app_secret_hash FROM installation_state", ByteArray::class.java)!!))
    }

    @Test
    fun `new installation requires token and refuses existing admins without state`() {
        val jdbc = database()
        listOf("", "invalid", TestInstallationSecrets.setupToken + "=", TestInstallationSecrets.setupToken + " ").forEach {
            assertStartupRejected("--microshop.setup-token=$it", expectedMessage = "SETUP_TOKEN must be canonical")
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM installation_state", Int::class.java))
        }
        jdbc.update("INSERT INTO admin_user VALUES (?, 'existing', 'test-only-hash', 'Owner', true, now(), now())", UUID.randomUUID())
        assertStartupRejected(expectedMessage = "Installation state is missing for an existing administrator")
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM admin_user", Int::class.java))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM installation_state", Int::class.java))
    }

    @Test
    fun `database enforces singleton hashes state consistency and owner foreign key`() {
        start().use { context ->
            val jdbc = database()
            assertSqlState("23514") {
                jdbc.update(
                    "INSERT INTO installation_state " +
                        "SELECT 2, status, setup_token_hash, app_secret_hash, owner_admin_id, initialized_at FROM installation_state",
                )
            }
            assertSqlState("23505") {
                jdbc.update("INSERT INTO installation_state SELECT * FROM installation_state")
            }
            listOf(
                "status = 'UNKNOWN'",
                "status = 'INITIALIZED'",
                "setup_token_hash = null",
                "setup_token_hash = decode('00', 'hex')",
                "app_secret_hash = decode('00', 'hex')",
                "initialized_at = now()",
            ).forEach { change -> assertSqlState("23514") { jdbc.update("UPDATE installation_state SET $change") } }
            assertSqlState("23502") { jdbc.update("UPDATE installation_state SET app_secret_hash = null") }
            assertSqlState("23503") {
                jdbc.update(
                    "UPDATE installation_state SET status='INITIALIZED', setup_token_hash=null, owner_admin_id=?, initialized_at=now()",
                    UUID.randomUUID(),
                )
            }
            val owner =
                context
                    .getBean(
                        SetupService::class.java,
                    ).createOwner(CreateOwnerRequest(TestInstallationSecrets.setupToken, "owner", "123456789012345", "Owner"))
            assertSqlState("23001") { jdbc.update("DELETE FROM admin_user WHERE id=?", owner.id) }
            assertSqlState("23514") { jdbc.update("UPDATE installation_state SET setup_token_hash=decode(repeat('00', 32), 'hex')") }
        }
    }

    @Test
    fun `concurrent initialization never overwrites the winning singleton`() {
        start().use { context ->
            val jdbc = database()
            jdbc.update("DELETE FROM installation_state")
            val keys = listOf(TestInstallationSecrets.randomSecret(), TestInstallationSecrets.randomSecret())
            val tokens = listOf(TestInstallationSecrets.randomSecret(), TestInstallationSecrets.randomSecret())
            val start = CountDownLatch(1)
            val service = context.getBean(InstallationService::class.java)
            Executors.newFixedThreadPool(2).use { executor ->
                val results =
                    keys.indices.map { index ->
                        executor.submit<Boolean> {
                            check(start.await(5, TimeUnit.SECONDS))
                            try {
                                service.initialize(keys[index], tokens[index])
                                true
                            } catch (_: DataAccessException) {
                                false
                            } catch (_: IllegalStateException) {
                                false
                            }
                        }
                    }
                start.countDown()
                val completed = results.map { it.get(15, TimeUnit.SECONDS) }
                assertEquals(1, completed.count { it })
                val winner = completed.indexOf(true)
                val state = jdbc.queryForMap("SELECT * FROM installation_state")
                assertTrue((state["app_secret_hash"] as ByteArray).contentEquals(SetupSecrets.hash(SetupSecrets.decode(keys[winner])!!)))
                assertTrue((state["setup_token_hash"] as ByteArray).contentEquals(SetupSecrets.tokenHash(tokens[winner])))
            }
        }
    }

    @Test
    fun `unsafe production or dev transport overrides abort startup`() {
        assertStartupRejected("--spring.profiles.active=dev", "--server.address=0.0.0.0", expectedMessage = "requires a loopback")
        assertStartupRejected(
            "--spring.profiles.active=dev",
            "--server.forward-headers-strategy=native",
            expectedMessage = "must not trust forwarded",
        )
        assertStartupRejected("--server.servlet.session.cookie.secure=false", expectedMessage = "Production requires Secure")
        assertStartupRejected("--server.tomcat.remoteip.internal-proxies=", expectedMessage = "Proxy trust must be explicitly restricted")
    }

    private fun assertStartupRejected(vararg args: String, expectedMessage: String) {
        val port = ServerSocket(0).use { it.localPort }
        val failure = assertFailsWith<Exception> { start(*args, port = port).close() }
        assertTrue(
            generateSequence<Throwable>(failure) {
                it.cause
            }.any { it.message.orEmpty().contains(expectedMessage) },
            "Expected safe startup diagnostic",
        )
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build().use { client ->
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

    private fun start(vararg args: String, port: Int = 0): ConfigurableApplicationContext =
        SpringApplicationBuilder(MicroshopApplication::class.java).run(
            *(
                listOf(
                    "--spring.datasource.url=${postgres.jdbcUrl}",
                    "--spring.datasource.username=${postgres.username}",
                    "--spring.datasource.password=${postgres.password}",
                    "--microshop.app-secret=${TestInstallationSecrets.appSecret}",
                    "--microshop.setup-token=${TestInstallationSecrets.setupToken}",
                    "--server.port=$port",
                ).filter { default -> args.none { it.substringBefore('=') == default.substringBefore('=') } } + args
                ).toTypedArray(),
        )

    private fun database(): JdbcTemplate = JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))

    private fun assertSqlState(expected: String, operation: () -> Unit) {
        val failure = assertFailsWith<DataAccessException> { operation() }
        assertEquals(expected, generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<SQLException>().first().sqlState)
    }

    @Configuration(proxyBeanMethods = false)
    @Import(PostgresTestConfiguration::class)
    class ContainerHost
}
