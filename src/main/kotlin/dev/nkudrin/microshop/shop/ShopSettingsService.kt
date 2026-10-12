package dev.nkudrin.microshop.shop

import dev.nkudrin.microshop.api.ApiError
import dev.nkudrin.microshop.api.ApiException
import dev.nkudrin.microshop.audit.AuditActorType
import dev.nkudrin.microshop.audit.AuditSnapshot
import dev.nkudrin.microshop.audit.AuditWriter
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

@Service
class ShopSettingsService(
    private val repository: ShopSettingsRepository,
    private val validator: ShopSettingsValidator,
    private val audit: AuditWriter,
    private val json: JsonMapper,
) {
    @Transactional(readOnly = true)
    fun read(): ShopSettingsDto = repository.findById(1).orElseThrow().toDto()

    @Transactional(rollbackFor = [Exception::class])
    fun update(input: ShopSettingsDto, adminId: UUID): ShopSettingsDto {
        val normalized = validator.normalize(input)
        val settings = repository.lockSettings()
        if (normalized.version != settings.version) throw ApiException(ApiError.SETTINGS_VERSION_CONFLICT)
        val before = settings.toDto()
        if (normalized == before) return before
        apply(settings, normalized)
        val after = settings.toDto()
        audit.record(
            actorType = AuditActorType.ADMIN,
            actorAdminId = adminId,
            action = "SHOP_SETTINGS_UPDATED",
            entityType = "SHOP_SETTINGS",
            entityId = "1",
            before = snapshot(before),
            after = snapshot(after),
        )
        return after
    }

    private fun apply(settings: ShopSettings, input: ShopSettingsDto) {
        settings.name = input.name
        settings.description = input.description
        settings.phone = input.contacts.phone
        settings.email = input.contacts.email
        settings.telegram = input.contacts.telegram
        settings.otherContact = input.contacts.otherContact
        settings.salesTerms = input.salesTerms
        settings.checkoutContactRules = input.checkoutContactRules
        settings.primaryColor = input.primaryColor
        settings.backgroundColor = input.backgroundColor
        settings.timezone = input.timezone
        settings.version = Math.incrementExact(settings.version)
        settings.updatedAt = Instant.now()
    }

    private fun ShopSettings.toDto(): ShopSettingsDto = ShopSettingsDto(
        name, description, ShopContactsDto(phone, email, telegram, otherContact), salesTerms,
        checkoutContactRules, primaryColor, backgroundColor, timezone, version,
    )

    private fun snapshot(settings: ShopSettingsDto): AuditSnapshot = AuditSnapshot.select(
        mapOf(
            "name" to settings.name,
            "description" to settings.description,
            "salesTerms" to settings.salesTerms,
            "contacts.phone" to settings.contacts.phone,
            "contacts.email" to settings.contacts.email,
            "contacts.telegram" to settings.contacts.telegram,
            "contacts.otherContact" to settings.contacts.otherContact,
            "checkoutContactRules" to json.writeValueAsString(
                linkedMapOf("required" to settings.checkoutContactRules.required, "any_of" to settings.checkoutContactRules.anyOf),
            ),
            "primaryColor" to settings.primaryColor,
            "backgroundColor" to settings.backgroundColor,
            "timezone" to settings.timezone,
            "version" to settings.version,
        ),
        "name", "description", "salesTerms", "contacts.phone", "contacts.email", "contacts.telegram", "contacts.otherContact",
        "checkoutContactRules", "primaryColor", "backgroundColor", "timezone", "version",
    )
}
