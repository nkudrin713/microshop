package dev.nkudrin.microshop.setup

import dev.nkudrin.microshop.admin.AdminUser
import dev.nkudrin.microshop.admin.AdminUserRepository
import dev.nkudrin.microshop.api.ApiError
import dev.nkudrin.microshop.api.ApiException
import dev.nkudrin.microshop.audit.AuditActorType
import dev.nkudrin.microshop.audit.AuditSnapshot
import dev.nkudrin.microshop.audit.AuditWriter
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.UUID

@Service
class SetupService(
    private val states: InstallationStateRepository,
    private val admins: AdminUserRepository,
    private val passwords: PasswordEncoder,
    private val audit: AuditWriter,
) {
    @Transactional(readOnly = true)
    fun status(): SetupStatus = SetupStatus(states.findById(1).orElseThrow().status == InstallationStatus.INITIALIZED)

    @Transactional(rollbackFor = [Exception::class])
    fun createOwner(request: CreateOwnerRequest): OwnerResponse {
        val state = states.lockState()
        if (state.status == InstallationStatus.INITIALIZED) throw ApiException(ApiError.SETUP_ALREADY_INITIALIZED)
        if (SetupSecrets.decode(request.setupToken) == null ||
            !MessageDigest.isEqual(state.setupTokenHash, SetupSecrets.tokenHash(request.setupToken))
        ) {
            throw ApiException(ApiError.SETUP_TOKEN_INVALID)
        }
        val originalLogin = request.login.trim()
        val login = originalLogin.lowercase(Locale.ROOT)
        val name = request.displayName.trim()
        validateOwnerData(originalLogin, request.password, name)
        val now = Instant.now()
        val hash = checkNotNull(passwords.encode(request.password))
        val owner = admins.saveAndFlush(AdminUser(UUID.randomUUID(), login, hash, name, true, now, now))
        state.status = InstallationStatus.INITIALIZED
        state.setupTokenHash = null
        state.ownerAdminId = owner.id
        state.initializedAt = now
        states.flush()
        recordOwnerCreated(owner)
        return OwnerResponse(owner.id, owner.login, owner.displayName)
    }

    private fun validateOwnerData(
        login: String,
        password: String,
        displayName: String,
    ) {
        if (!Regex("[A-Za-z0-9]{3,64}").matches(login) ||
            password.codePointCount(0, password.length) !in 15..128 ||
            displayName.codePointCount(0, displayName.length) !in 1..100 ||
            '\u0000' in displayName
        ) {
            throw ApiException(ApiError.VALIDATION_ERROR)
        }
    }

    private fun recordOwnerCreated(owner: AdminUser) {
        audit.record(
            actorType = AuditActorType.ADMIN,
            actorAdminId = owner.id,
            action = "OWNER_CREATED",
            entityType = "ADMIN_USER",
            entityId = owner.id.toString(),
            after =
                AuditSnapshot.select(
                    mapOf("id" to owner.id.toString(), "login" to owner.login, "displayName" to owner.displayName, "enabled" to true),
                    "id",
                    "login",
                    "displayName",
                    "enabled",
                ),
        )
    }
}

class CreateOwnerRequest(
    val setupToken: String,
    val login: String,
    val password: String,
    val displayName: String,
)

data class OwnerResponse(
    val id: UUID,
    val login: String,
    val displayName: String,
)

data class SetupStatus(
    val initialized: Boolean,
)
