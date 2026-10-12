package dev.nkudrin.microshop.security

import dev.nkudrin.microshop.api.ApiError
import dev.nkudrin.microshop.api.ApiException
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LoginRateLimiterTest {
    private val clock = MutableClock()
    private val limiter = LoginRateLimiter(clock)
    private val owner = UsernamePasswordAuthenticationToken.authenticated("owner", null, emptyList())

    @Test
    fun `tenth failure is allowed and further attempts do not check credentials`() {
        repeat(10) { failure() }
        var checked = false
        val blocked = assertFailsWith<ApiException> {
            limiter.authenticate("first") {
                checked = true
                owner
            }
        }
        assertEquals(ApiError.LOGIN_RATE_LIMITED, blocked.error)
        assertEquals(900L, blocked.retryAfterSeconds)
        assertEquals(false, checked)
    }

    @Test
    fun `window starts on first failure and retry delay rounds up until exact expiry`() {
        failure()
        clock.now += Duration.ofMinutes(5)
        repeat(9) { failure() }
        clock.now += Duration.ofMinutes(10).minusMillis(500)
        val blocked = assertFailsWith<ApiException> { limiter.authenticate("first") { owner } }
        assertEquals(1L, blocked.retryAfterSeconds)
        clock.now += Duration.ofMillis(500)
        assertEquals(owner, limiter.authenticate("first") { owner })
        repeat(10) { failure() }
        assertEquals(900L, assertFailsWith<ApiException> { limiter.authenticate("first") { owner } }.retryAfterSeconds)
    }

    @Test
    fun `successful authentication neither consumes failures nor resets the active window`() {
        repeat(9) { failure() }
        repeat(20) { assertEquals(owner, limiter.authenticate("first") { owner }) }
        failure()
        assertFailsWith<ApiException> { limiter.authenticate("first") { owner } }
    }

    @Test
    fun `IP windows are independent and a new backend instance has no remembered failures`() {
        repeat(10) { failure() }
        assertEquals(owner, limiter.authenticate("second") { owner })
        assertFailsWith<BadCredentialsException> { limiter.authenticate("second") { throw BadCredentialsException("Invalid") } }
        assertFailsWith<ApiException> { limiter.authenticate("first") { owner } }
        assertEquals(owner, LoginRateLimiter(clock).authenticate("first") { owner })
    }

    @Test
    fun `internal failures do not consume the credential failure limit`() {
        repeat(10) {
            assertFailsWith<IllegalStateException> { limiter.authenticate("first") { error("Unavailable") } }
        }
        assertEquals(owner, limiter.authenticate("first") { owner })
    }

    @Test
    fun `concurrent attempts from the same IP cannot exceed ten credential checks`() {
        val start = CountDownLatch(1)
        val checked = AtomicInteger()
        Executors.newFixedThreadPool(20).use { executor ->
            val attempts = (1..20).map {
                executor.submit<Int> {
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    try {
                        limiter.authenticate("first") {
                            checked.incrementAndGet()
                            throw BadCredentialsException("Invalid")
                        }
                        error("Authentication must fail")
                    } catch (_: BadCredentialsException) {
                        401
                    } catch (exception: ApiException) {
                        assertEquals(ApiError.LOGIN_RATE_LIMITED, exception.error)
                        429
                    }
                }
            }
            start.countDown()
            val results = attempts.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(10, checked.get())
            assertEquals(10, results.count { it == 401 })
            assertEquals(10, results.count { it == 429 })
        }
    }

    private fun failure() {
        assertFailsWith<BadCredentialsException> { limiter.authenticate("first") { throw BadCredentialsException("Invalid") } }
    }

    private class MutableClock(var now: Instant = Instant.parse("2026-10-12T00:00:00Z")) : Clock() {
        override fun instant(): Instant = now

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
    }
}
