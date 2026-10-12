package dev.nkudrin.microshop.setup

import dev.nkudrin.microshop.admin.AdminUserRepository
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest

@Service
class InstallationService(
    private val states: InstallationStateRepository,
    private val admins: AdminUserRepository,
    private val entities: EntityManager,
) {
    @Transactional
    fun initialize(appSecret: String?, setupToken: String?) {
        val key =
            SetupSecrets.decode(appSecret)
                ?: throw IllegalStateException("APP_SECRET must be canonical Base64URL encoding of 32 bytes.")
        val keyHash = SetupSecrets.hash(key)
        key.fill(0)
        val existing = states.findById(1).orElse(null)
        if (existing != null) {
            check(MessageDigest.isEqual(existing.appSecretHash, keyHash)) {
                "APP_SECRET does not match the installation key."
            }
            return
        }
        check(admins.count() == 0L) { "Installation state is missing for an existing administrator." }
        check(SetupSecrets.decode(setupToken) != null) {
            "SETUP_TOKEN must be canonical Base64URL encoding of 32 bytes for a new installation."
        }
        try {
            entities.persist(InstallationState(setupTokenHash = SetupSecrets.tokenHash(setupToken!!), appSecretHash = keyHash))
            entities.flush()
        } catch (_: PersistenceException) {
            throw IllegalStateException("Cannot create installation state; existing database state was not overwritten.")
        }
    }
}
