package dev.nkudrin.microshop.security

import dev.nkudrin.microshop.api.ApiError
import dev.nkudrin.microshop.api.ApiException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.session.FindByIndexNameSessionRepository
import org.springframework.session.Session
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/admin/auth")
class AdminAuthenticationController(
    private val authentication: AdminAuthenticationService,
    private val loginRateLimiter: LoginRateLimiter,
    private val contexts: SecurityContextRepository,
    private val loginSessionStrategy: SessionAuthenticationStrategy,
    private val logoutHandler: SecurityContextLogoutHandler,
    private val sessions: FindByIndexNameSessionRepository<out Session>,
) {
    @PostMapping("/login")
    fun login(@RequestBody credentials: LoginRequest, request: HttpServletRequest, response: HttpServletResponse): AdminProfile {
        val authenticated =
            try {
                loginRateLimiter.authenticate(request.remoteAddr) {
                    authentication.authenticate(
                        UsernamePasswordAuthenticationToken.unauthenticated(credentials.login, credentials.password),
                    )
                }
            } catch (_: BadCredentialsException) {
                throw ApiException(ApiError.INVALID_CREDENTIALS)
            }
        val profile = authentication.profile(authenticated.principal as UUID)
        loginSessionStrategy.onAuthentication(authenticated, request, response)
        val context = SecurityContextHolder.createEmptyContext().apply { this.authentication = authenticated }
        SecurityContextHolder.setContext(context)
        contexts.saveContext(context, request, response)
        return profile
    }

    @GetMapping("/me")
    fun me(@AuthenticationPrincipal id: UUID): AdminProfile = authentication.profile(id)

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun logout(request: HttpServletRequest, response: HttpServletResponse, authentication: Authentication?) {
        logoutHandler.logout(request, response, authentication)
    }

    @PostMapping("/logout-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun logoutAll(request: HttpServletRequest, response: HttpServletResponse, authentication: Authentication) {
        sessions.findByPrincipalName(authentication.name).keys.forEach(sessions::deleteById)
        logoutHandler.logout(request, response, authentication)
    }
}
