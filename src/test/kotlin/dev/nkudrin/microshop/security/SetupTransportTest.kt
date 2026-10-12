package dev.nkudrin.microshop.security

import dev.nkudrin.microshop.PostgresTestConfiguration
import dev.nkudrin.microshop.TestInstallationSecrets
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["server.tomcat.remoteip.internal-proxies=127\\.0\\.0\\.1"],
)
@Import(PostgresTestConfiguration::class)
class SetupTransportTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var json: JsonMapper

    @ParameterizedTest
    @CsvSource(
        "GET,/api/setup/status",
        "POST,/api/setup/owner",
        "GET,/api/csrf",
        "POST,/api/admin/auth/login",
        "GET,/api/admin/auth/me",
        "POST,/api/admin/auth/logout",
        "POST,/api/admin/auth/logout-all",
        "PUT,/api/admin/settings",
    )
    fun `production denies HTTP before CSRF credentials or session creation`(method: String, path: String) {
        HttpClient.newHttpClient().use { client ->
            val request =
                HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
                    .header("Accept", "text/html")
                    .header("Content-Type", "application/json")
                    .header("Forwarded", "proto=https;for=203.0.113.17")
                    .method(method, HttpRequest.BodyPublishers.ofString("{"))
                    .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            assertEquals(400, response.statusCode())
            assertTrue(response.headers().firstValue("content-type").orElseThrow().startsWith("application/problem+json"))
            val problem = json.readTree(response.body())
            assertEquals("HTTPS_REQUIRED", problem["code"].stringValue())
            assertEquals("HTTPS is required.", problem["detail"].stringValue())
            assertEquals(path, problem["instance"].stringValue())
            assertTrue(response.headers().firstValue("set-cookie").isEmpty)
            assertTrue(response.headers().firstValue("location").isEmpty)
        }
    }

    @Test
    fun `login limiter uses client IP supplied by the explicitly trusted Tomcat proxy`() {
        HttpClient.newHttpClient().use { client ->
            fun request(path: String, ip: String) = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-For", ip)

            fun send(request: HttpRequest.Builder) = client.send(request.build(), HttpResponse.BodyHandlers.ofString())
            val csrf = send(request("/api/csrf", "203.0.113.17").GET())
            assertEquals(200, csrf.statusCode())
            val cookie = csrf.headers().firstValue("set-cookie").orElseThrow().substringBefore(';')
            val token = json.readTree(csrf.body())["token"].stringValue()
            val credentials = """{"login":"unknown","password":"wrong-password-123456789"}"""

            fun login(ip: String) = send(
                request("/api/admin/auth/login", ip)
                    .header("Cookie", cookie)
                    .header("X-CSRF-TOKEN", token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(credentials)),
            )
            repeat(10) {
                val response = login("203.0.113.17")
                assertEquals(401, response.statusCode())
                assertEquals("INVALID_CREDENTIALS", json.readTree(response.body())["code"].stringValue())
            }
            val blocked = login("203.0.113.17")
            assertEquals(429, blocked.statusCode())
            assertEquals("LOGIN_RATE_LIMITED", json.readTree(blocked.body())["code"].stringValue())
            assertTrue(blocked.headers().firstValue("retry-after").orElseThrow().toLong() in 1..900)
            assertEquals(401, login("203.0.113.18").statusCode())
        }
    }

    @Test
    fun `native Tomcat trusts explicit proxy and setup uses Secure cookie and masked CSRF`() {
        HttpClient.newHttpClient().use { client ->
            fun request(path: String) = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))

            fun send(request: HttpRequest.Builder) = client.send(request.build(), HttpResponse.BodyHandlers.ofString())
            val plain = send(request("/api/csrf").GET())
            assertEquals(400, plain.statusCode())
            assertEquals("HTTPS_REQUIRED", json.readTree(plain.body())["code"].stringValue())
            assertTrue(plain.headers().firstValue("set-cookie").isEmpty)
            val forwardedOnly = send(request("/api/csrf").header("Forwarded", "proto=https;for=203.0.113.17").GET())
            assertEquals(400, forwardedOnly.statusCode())
            val csrf = send(request("/api/csrf").header("X-Forwarded-Proto", "https").GET())
            assertEquals(200, csrf.statusCode())
            assertEquals("no-store", csrf.headers().firstValue("cache-control").orElseThrow())
            val cookie = csrf.headers().firstValue("set-cookie").orElseThrow()
            assertTrue(cookie.startsWith("MICROSHOP_SESSION="))
            assertTrue(cookie.contains("Secure") && cookie.contains("HttpOnly") && cookie.contains("SameSite=Lax"))
            assertFalse(cookie.contains("Max-Age") || cookie.contains("Expires=") || cookie.contains("Domain="))
            val token = json.readTree(csrf.body())["token"].stringValue()
            val missingCsrf =
                send(
                    request("/api/setup/owner")
                        .header("X-Forwarded-Proto", "https")
                        .POST(HttpRequest.BodyPublishers.ofString("{}")),
                )
            assertEquals(403, missingCsrf.statusCode())
            assertEquals("CSRF_TOKEN_INVALID", json.readTree(missingCsrf.body())["code"].stringValue())
            val body =
                json.writeValueAsString(
                    mapOf(
                        "setupToken" to TestInstallationSecrets.setupToken,
                        "login" to "owner",
                        "password" to "123456789012345",
                        "displayName" to "Owner",
                    ),
                )

            fun setup() = send(
                request("/api/setup/owner")
                    .header("X-Forwarded-Proto", "https")
                    .header("Cookie", cookie.substringBefore(';'))
                    .header("X-CSRF-TOKEN", token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)),
            )
            assertEquals(201, setup().statusCode())
            assertEquals(409, setup().statusCode())
            val denied = send(request("/api/admin/settings").header("X-Forwarded-Proto", "https").GET())
            assertEquals(401, denied.statusCode())
            assertTrue(denied.headers().firstValue("location").isEmpty)
            assertTrue(denied.headers().firstValue("www-authenticate").isEmpty)
        }
    }
}
