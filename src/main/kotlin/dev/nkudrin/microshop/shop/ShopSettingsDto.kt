package dev.nkudrin.microshop.shop

import com.fasterxml.jackson.annotation.JsonCreator
import jakarta.validation.constraints.Email
import tools.jackson.databind.JsonNode

data class ShopSettingsDto(
    val name: String,
    val description: String,
    val contacts: ShopContactsDto,
    val salesTerms: String,
    val checkoutContactRules: CheckoutContactRules,
    val primaryColor: String?,
    val backgroundColor: String?,
    val timezone: String,
    val version: Long,
) {
    companion object {
        // Keep this contract strict without changing Jackson defaults for other APIs.
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun fromJson(input: JsonNode): ShopSettingsDto {
            input.requireFields(
                "name", "description", "contacts", "salesTerms", "checkoutContactRules",
                "primaryColor", "backgroundColor", "timezone", "version",
            )
            val contacts = input["contacts"]
            contacts.requireFields("phone", "email", "telegram", "otherContact")
            val rules = input["checkoutContactRules"]
            rules.requireFields("required", "any_of")
            val groups = rules["any_of"]
            require(groups.isArray) { "Expected an array." }
            val version = input["version"]
            require(version.isIntegralNumber && version.canConvertToLong()) { "Expected an integer version." }
            return ShopSettingsDto(
                name = input["name"].requireString(),
                description = input["description"].requireString(),
                contacts = ShopContactsDto(
                    contacts["phone"].nullableString(),
                    contacts["email"].nullableString(),
                    contacts["telegram"].nullableString(),
                    contacts["otherContact"].nullableString(),
                ),
                salesTerms = input["salesTerms"].requireString(),
                checkoutContactRules = CheckoutContactRules(rules["required"].stringList(), groups.toList().map { it.stringList() }),
                primaryColor = input["primaryColor"].nullableString(),
                backgroundColor = input["backgroundColor"].nullableString(),
                timezone = input["timezone"].requireString(),
                version = version.longValue(),
            )
        }
    }
}

data class ShopContactsDto(
    val phone: String?,
    @field:Email
    val email: String?,
    val telegram: String?,
    val otherContact: String?,
)

private fun JsonNode.requireFields(vararg fields: String) {
    require(isObject && propertyNames().toSet() == fields.toSet()) { "Expected all declared fields and no unknown fields." }
}

private fun JsonNode.requireString(): String {
    require(isString) { "Expected a string." }
    return stringValue()
}

private fun JsonNode.nullableString(): String? = if (isNull) null else requireString()

private fun JsonNode.stringList(): List<String> {
    require(isArray) { "Expected an array." }
    return toList().map { it.requireString() }
}
