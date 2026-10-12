package dev.nkudrin.microshop.security

import dev.nkudrin.microshop.admin.AdminUserRepository
import dev.nkudrin.microshop.api.ApiError
import dev.nkudrin.microshop.api.ApiException
import jakarta.servlet.DispatcherType
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.web.server.autoconfigure.ServerProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.http.HttpMethod
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy
import org.springframework.security.web.csrf.CsrfFilter
import org.springframework.security.web.csrf.CsrfTokenRepository
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository
import org.springframework.security.web.csrf.InvalidCsrfTokenException
import org.springframework.security.web.csrf.MissingCsrfTokenException
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.HandlerExceptionResolver
import java.time.Clock
import java.util.UUID

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class SecurityConfiguration {
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun loginRateLimiter(clock: Clock): LoginRateLimiter = LoginRateLimiter(clock)

    @Bean
    fun securityContextRepository(): SecurityContextRepository = HttpSessionSecurityContextRepository()

    @Bean
    fun csrfTokenRepository(): CsrfTokenRepository = HttpSessionCsrfTokenRepository()

    @Bean
    fun loginSessionStrategy(csrf: CsrfTokenRepository): SessionAuthenticationStrategy =
        CompositeSessionAuthenticationStrategy(listOf(ChangeSessionIdAuthenticationStrategy(), CsrfAuthenticationStrategy(csrf)))

    @Bean
    fun securityContextLogoutHandler(contexts: SecurityContextRepository): SecurityContextLogoutHandler =
        SecurityContextLogoutHandler().apply { setSecurityContextRepository(contexts) }

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        environment: Environment,
        server: ServerProperties,
        admins: AdminUserRepository,
        contexts: SecurityContextRepository,
        csrf: CsrfTokenRepository,
        logout: SecurityContextLogoutHandler,
        @Qualifier("handlerExceptionResolver") errors: HandlerExceptionResolver,
    ): SecurityFilterChain {
        val dev = environment.acceptsProfiles(Profiles.of("dev"))
        validateTransportConfiguration(dev, environment, server)
        if (!dev) {
            http.addFilterBefore(HttpsFilter(errors), CsrfFilter::class.java)
        }
        http.addFilterAfter(ActiveOwnerFilter(admins, logout, errors), CsrfFilter::class.java)
        http
            .securityContext { it.securityContextRepository(contexts) }
            .authorizeHttpRequests {
                it
                    .dispatcherTypeMatchers(DispatcherType.ERROR)
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/actuator/health", "/api/setup/status", "/api/csrf")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/setup/owner", "/api/admin/auth/login", "/api/admin/auth/logout")
                    .permitAll()
                    .requestMatchers("/api/admin/**")
                    .hasRole("OWNER")
                    .anyRequest()
                    .denyAll()
            }.csrf { it.csrfTokenRepository(csrf) }
            .exceptionHandling {
                it
                    .authenticationEntryPoint { request, response, _ ->
                        errors.resolveException(request, response, null, ApiException(ApiError.AUTHENTICATION_REQUIRED))
                    }.accessDeniedHandler { request, response, exception ->
                        val error =
                            if (exception is MissingCsrfTokenException || exception is InvalidCsrfTokenException) {
                                ApiError.CSRF_TOKEN_INVALID
                            } else {
                                ApiError.ACCESS_DENIED
                            }
                        errors.resolveException(request, response, null, ApiException(error))
                    }
            }.httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .requestCache { it.disable() }
        return http.build()
    }

    private class ActiveOwnerFilter(
        private val admins: AdminUserRepository,
        private val logout: SecurityContextLogoutHandler,
        private val errors: HandlerExceptionResolver,
    ) : OncePerRequestFilter() {
        override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
            val path = request.servletPath + request.pathInfo.orEmpty()
            val publicAuth = request.method == "POST" && path in setOf("/api/admin/auth/login", "/api/admin/auth/logout")
            if ((path == "/api/admin" || path.startsWith("/api/admin/")) && !publicAuth) {
                val authentication = SecurityContextHolder.getContext().authentication
                if (authentication?.isAuthenticated == true && authentication !is AnonymousAuthenticationToken) {
                    val id = authentication.principal as? UUID
                    val owner = id?.let { admins.findById(it).orElse(null) }
                    if (owner?.enabled != true) {
                        logout.logout(request, response, authentication)
                        errors.resolveException(request, response, null, ApiException(ApiError.AUTHENTICATION_REQUIRED))
                        return
                    }
                }
            }
            chain.doFilter(request, response)
        }
    }

    private fun validateTransportConfiguration(dev: Boolean, environment: Environment, server: ServerProperties) {
        if (dev) {
            check(server.address?.isLoopbackAddress == true) { "The dev profile requires a loopback server address." }
            check(server.forwardHeadersStrategy == ServerProperties.ForwardHeadersStrategy.NONE) {
                "The dev profile must not trust forwarded headers."
            }
        } else {
            check(environment.getProperty("server.servlet.session.cookie.secure", Boolean::class.java) == true) {
                "Production requires Secure session cookies."
            }
            check(server.forwardHeadersStrategy == ServerProperties.ForwardHeadersStrategy.NATIVE) {
                "Production requires native forwarded header handling."
            }
            check(!environment.getProperty("server.tomcat.remoteip.internal-proxies").isNullOrBlank()) {
                "Proxy trust must be explicitly restricted."
            }
        }
    }

    private class HttpsFilter(private val errors: HandlerExceptionResolver) : OncePerRequestFilter() {
        override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
            val path = request.servletPath + request.pathInfo.orEmpty()
            val protected =
                path == "/api/csrf" || path == "/api/setup" || path.startsWith("/api/setup/") ||
                    path == "/api/admin" || path.startsWith("/api/admin/")
            if (protected && !request.isSecure) {
                errors.resolveException(request, response, null, ApiException(ApiError.HTTPS_REQUIRED))
            } else {
                chain.doFilter(request, response)
            }
        }
    }
}
