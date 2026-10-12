package dev.nkudrin.microshop.shop

import dev.nkudrin.microshop.api.ApiError
import dev.nkudrin.microshop.api.ApiException
import jakarta.validation.Validator
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URISyntaxException
import java.time.ZoneId
import java.util.Locale

@Component
class ShopSettingsValidator(private val validator: Validator) {
    fun normalize(input: ShopSettingsDto): ShopSettingsDto {
        ensure(input.version >= 0)
        validateRules(input.checkoutContactRules)
        val timezone = input.timezone.trim()
        ensure(timezone in ZoneId.getAvailableZoneIds())
        return input.copy(
            name = text(input.name, 100),
            description = text(input.description, 5_000),
            contacts = contacts(input.contacts),
            salesTerms = text(input.salesTerms, 20_000),
            primaryColor = color(input.primaryColor),
            backgroundColor = color(input.backgroundColor),
            timezone = timezone,
        )
    }

    private fun contacts(input: ShopContactsDto): ShopContactsDto {
        val normalized = ShopContactsDto(
            phone = input.phone?.let { phone(it) },
            email = input.email?.let { text(it, 254).ifEmpty { null } },
            telegram = input.telegram?.let { telegram(it) },
            otherContact = input.otherContact?.let { text(it, 500).ifEmpty { null } },
        )
        ensure(validator.validate(normalized).isEmpty())
        return normalized
    }

    private fun text(value: String, limit: Int): String = value.trim().also {
        ensure(it.codePointCount(0, it.length) <= limit && '\u0000' !in it)
    }

    private fun phone(value: String): String? {
        if (value.isBlank()) return null
        val normalized = value.filterNot { it.isWhitespace() || it in "()-" }
        ensure(Regex("\\+[0-9]{7,15}").matches(normalized))
        return normalized
    }

    private fun telegram(value: String): String? {
        val normalized = value.trim().ifEmpty { return null }
        if (normalized.startsWith('@')) {
            val username = normalized.drop(1)
            ensure(Regex("[A-Za-z0-9_]+").matches(username))
            return "https://t.me/$username"
        }
        val uri = try {
            URI(normalized)
        } catch (_: URISyntaxException) {
            throw ApiException(ApiError.VALIDATION_ERROR)
        }
        ensure(
            uri.scheme.equals("https", ignoreCase = true) && uri.host.equals("t.me", ignoreCase = true) &&
                uri.rawUserInfo == null && uri.port == -1 && uri.rawPath.orEmpty().length > 1,
        )
        return normalized
    }

    private fun color(value: String?): String? = value?.trim()?.also {
        ensure(Regex("#[0-9A-Fa-f]{6}").matches(it))
    }?.uppercase(Locale.ROOT)

    private fun validateRules(rules: CheckoutContactRules) {
        val allowed = setOf("name", "phone", "email", "telegram", "other_contact")
        fun valid(keys: List<String>): Boolean = keys.size == keys.toSet().size && keys.all { it in allowed }
        ensure(valid(rules.required) && rules.anyOf.all { it.isNotEmpty() && valid(it) })
    }

    private fun ensure(condition: Boolean) {
        if (!condition) throw ApiException(ApiError.VALIDATION_ERROR)
    }
}
