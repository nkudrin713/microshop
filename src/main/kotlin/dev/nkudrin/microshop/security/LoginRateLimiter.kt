package dev.nkudrin.microshop.security

import dev.nkudrin.microshop.api.ApiError
import dev.nkudrin.microshop.api.ApiException
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.core.Authentication
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class LoginRateLimiter(private val clock: Clock) {
    private val failures = ConcurrentHashMap<String, FailureWindow>()

    fun authenticate(ip: String, attempt: () -> Authentication): Authentication {
        val now = clock.instant()
        failures.entries.removeIf { !now.isBefore(it.value.expiresAt) }
        var authenticated: Authentication? = null
        var credentialsError: BadCredentialsException? = null
        // Serialize attempts for the same IP so concurrent requests cannot bypass the threshold.
        failures.compute(ip) { _, previous ->
            val currentTime = clock.instant()
            val window = previous?.takeIf { currentTime.isBefore(it.expiresAt) }
            if (window != null && window.count >= 10) {
                val remaining = Duration.between(currentTime, window.expiresAt)
                throw ApiException(ApiError.LOGIN_RATE_LIMITED, remaining.seconds + if (remaining.nano > 0) 1 else 0)
            }
            try {
                authenticated = attempt()
                window
            } catch (exception: BadCredentialsException) {
                credentialsError = exception
                val failedAt = clock.instant()
                val active = window?.takeIf { failedAt.isBefore(it.expiresAt) }
                active?.copy(count = active.count + 1) ?: FailureWindow(1, failedAt.plus(Duration.ofMinutes(15)))
            }
        }
        credentialsError?.let { throw it }
        return checkNotNull(authenticated)
    }

    private data class FailureWindow(val count: Int, val expiresAt: Instant)
}
