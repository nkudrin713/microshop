package dev.nkudrin.microshop.security

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
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.csrf.CsrfFilter
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository
import org.springframework.security.web.csrf.InvalidCsrfTokenException
import org.springframework.security.web.csrf.MissingCsrfTokenException
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.HandlerExceptionResolver

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class SecurityConfiguration {
    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        environment: Environment,
        server: ServerProperties,
        @Qualifier("handlerExceptionResolver") errors: HandlerExceptionResolver,
    ): SecurityFilterChain {
        val dev = environment.acceptsProfiles(Profiles.of("dev"))
        validateTransportConfiguration(dev, environment, server)
        if (!dev) {
            http.addFilterBefore(HttpsFilter(errors), CsrfFilter::class.java)
        }
        http
            .authorizeHttpRequests {
                it
                    .dispatcherTypeMatchers(DispatcherType.ERROR)
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/actuator/health", "/api/setup/status", "/api/csrf")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/setup/owner")
                    .permitAll()
                    .anyRequest()
                    .denyAll()
            }.csrf { it.csrfTokenRepository(HttpSessionCsrfTokenRepository()) }
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
