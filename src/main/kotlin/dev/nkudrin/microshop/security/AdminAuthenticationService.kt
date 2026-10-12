package dev.nkudrin.microshop.security

import dev.nkudrin.microshop.admin.AdminUserRepository
import dev.nkudrin.microshop.api.ApiError
import dev.nkudrin.microshop.api.ApiException
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.authority.AuthorityUtils
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.Locale
import java.util.UUID

@Service
class AdminAuthenticationService(private val admins: AdminUserRepository, private val passwords: PasswordEncoder) : AuthenticationManager {
    private val dummyHash = checkNotNull(passwords.encode(UUID.randomUUID().toString()))

    @Transactional(readOnly = true)
    override fun authenticate(authentication: Authentication): Authentication {
        val login = authentication.name.trim().lowercase(Locale.ROOT)
        val password = authentication.credentials as? String ?: throw BadCredentialsException("Invalid credentials")
        if (password.codePointCount(0, password.length) !in 15..128) throw BadCredentialsException("Invalid credentials")
        val owner = if (Regex("[a-z0-9]{3,64}").matches(login)) admins.findByLogin(login) else null
        val matches = passwords.matches(password, owner?.passwordHash ?: dummyHash)
        if (!matches || owner?.enabled != true) throw BadCredentialsException("Invalid credentials")
        return UsernamePasswordAuthenticationToken.authenticated(owner.id, null, AuthorityUtils.createAuthorityList("ROLE_OWNER"))
    }

    @Transactional(readOnly = true)
    fun profile(id: UUID): AdminProfile {
        val owner = admins.findById(id).orElse(null)
        if (owner?.enabled != true) throw ApiException(ApiError.AUTHENTICATION_REQUIRED)
        return AdminProfile(owner.id, owner.login, owner.displayName)
    }
}

class LoginRequest(val login: String, val password: String)

data class AdminProfile(val id: UUID, val login: String, val displayName: String)
