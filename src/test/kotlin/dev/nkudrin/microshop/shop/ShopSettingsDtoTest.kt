package dev.nkudrin.microshop.shop

import dev.nkudrin.microshop.api.ApiExceptionHandler
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@WebMvcTest(ShopSettingsDtoTest.TestController::class)
// This slice verifies the DTO contract without authentication or database persistence.
@AutoConfigureMockMvc(addFilters = false)
@Import(ShopSettingsDtoTest.TestController::class, ShopSettingsValidator::class, ApiExceptionHandler::class)
class ShopSettingsDtoTest {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var json: JsonMapper

    @Test
    fun `normalization preserves line breaks and response exposes only editable settings and version`() {
        val result = accepted(
            input() + mapOf(
                "name" to "  Магазин  ",
                "description" to "  Первая строка\nВторая строка  ",
                "salesTerms" to "  <b>Условия</b>\n**продажи**  ",
                "contacts" to mapOf(
                    "phone" to " +7 (999) 123-45-67 ",
                    "email" to " owner@example.com ",
                    "telegram" to " @owner_name ",
                    "otherContact" to " Связь через продавца ",
                ),
                "primaryColor" to " #aBcDef ",
                "backgroundColor" to "#ffffff",
                "timezone" to " Etc/UTC ",
                "version" to 7,
            ),
        )
        assertEquals(input().keys, result.propertyNames().toSet())
        assertEquals("Магазин", result["name"].stringValue())
        assertEquals("Первая строка\nВторая строка", result["description"].stringValue())
        assertEquals("<b>Условия</b>\n**продажи**", result["salesTerms"].stringValue())
        assertEquals("+79991234567", result["contacts"]["phone"].stringValue())
        assertEquals("owner@example.com", result["contacts"]["email"].stringValue())
        assertEquals("https://t.me/owner_name", result["contacts"]["telegram"].stringValue())
        assertEquals("Связь через продавца", result["contacts"]["otherContact"].stringValue())
        assertEquals("#ABCDEF", result["primaryColor"].stringValue())
        assertEquals("#FFFFFF", result["backgroundColor"].stringValue())
        assertEquals("Etc/UTC", result["timezone"].stringValue())
        assertEquals(7, result["version"].intValue())
        assertEquals(rules().keys, result["checkoutContactRules"].propertyNames().toSet())
    }

    @Test
    fun `every key is required including nullable contact and color keys`() {
        input().keys.forEach { rejected(input() - it, "MALFORMED_JSON") }
        contacts().keys.forEach { rejected(input() + ("contacts" to (contacts() - it)), "MALFORMED_JSON") }
        rules().keys.forEach { rejected(input() + ("checkoutContactRules" to (rules() - it)), "MALFORMED_JSON") }
    }

    @Test
    fun `unknown fields are rejected at every object level`() {
        listOf("typo", "id", "logoFileId", "published", "salesEnabled", "notificationSettings", "updatedAt").forEach {
            rejected(input() + (it to true), "MALFORMED_JSON")
        }
        rejected(input() + ("contacts" to (contacts() + ("unknown" to null))), "MALFORMED_JSON")
        rejected(input() + ("checkoutContactRules" to (rules() + ("unknown" to emptyList<String>()))), "MALFORMED_JSON")
    }

    @Test
    fun `JSON types are strict without scalar or collection coercion`() {
        listOf("name", "description", "salesTerms", "timezone").forEach { field ->
            listOf(null, 123, true, listOf("text"), emptyMap<String, String>()).forEach {
                rejected(input() + (field to it), "MALFORMED_JSON")
            }
        }
        listOf("primaryColor", "backgroundColor").forEach {
            rejected(input() + (it to 123456), "MALFORMED_JSON")
        }
        contacts().keys.forEach { rejected(input() + ("contacts" to (contacts() + (it to 123))), "MALFORMED_JSON") }
        listOf(null, "0", 0.5, true, BigInteger("9223372036854775808")).forEach {
            rejected(input() + ("version" to it), "MALFORMED_JSON")
        }
        listOf("contacts", "checkoutContactRules").forEach {
            rejected(input() + (it to null), "MALFORMED_JSON")
            rejected(input() + (it to emptyList<String>()), "MALFORMED_JSON")
        }
        listOf(null, "name", listOf(123)).forEach {
            rejected(input() + ("checkoutContactRules" to (rules() + ("required" to it))), "MALFORMED_JSON")
        }
        listOf(null, "phone", listOf("phone"), listOf(listOf(null))).forEach {
            rejected(input() + ("checkoutContactRules" to (rules() + ("any_of" to it))), "MALFORMED_JSON")
        }
    }

    @Test
    fun `null and blank contacts clear values while draft texts may be empty`() {
        val cleared = accepted(input() + ("contacts" to contacts().mapValues { " \t\n " }))
        contacts().keys.forEach { assertTrue(cleared["contacts"][it].isNull) }
        val defaults = accepted(input())
        assertEquals("", defaults["name"].stringValue())
        assertEquals("", defaults["description"].stringValue())
        assertEquals("", defaults["salesTerms"].stringValue())
        contacts().keys.forEach { assertTrue(defaults["contacts"][it].isNull) }
        assertTrue(defaults["primaryColor"].isNull)
        assertTrue(defaults["backgroundColor"].isNull)
    }

    @Test
    fun `text limits count Unicode code points after trimming`() {
        mapOf("name" to 100, "description" to 5_000, "salesTerms" to 20_000).forEach { (field, limit) ->
            val text = "😀".repeat(limit)
            assertEquals(text, accepted(input() + (field to " $text "))[field].stringValue())
            rejected(input() + (field to (text + "😀")))
        }
        val other = "😀".repeat(500)
        accepted(input() + ("contacts" to (contacts() + ("otherContact" to " $other "))))
        rejected(input() + ("contacts" to (contacts() + ("otherContact" to (other + "😀")))))
    }

    @Test
    fun `phone accepts international boundaries and rejects invalid formats`() {
        listOf("+1234567", "+123456789012345").forEach {
            assertEquals(it, accepted(input() + ("contacts" to (contacts() + ("phone" to it))))["contacts"]["phone"].stringValue())
        }
        listOf("+123456", "+1234567890123456", "79991234567", "+7abc1234567", "+٧٩٩٩١٢٣٤٥٦٧", "()-").forEach {
            rejected(input() + ("contacts" to (contacts() + ("phone" to it))))
        }
    }

    @Test
    fun `email is validated as an address with a length limit`() {
        val address = "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(61)
        assertEquals(254, address.length)
        accepted(input() + ("contacts" to (contacts() + ("email" to " $address "))))
        listOf(address + "d", "owner", "owner@", "owner @example.com").forEach {
            rejected(input() + ("contacts" to (contacts() + ("email" to it))))
        }
    }

    @Test
    fun `telegram accepts t me links and rejects other protocols hosts and malformed values`() {
        listOf("https://t.me/owner", "https://t.me/+invite_code").forEach {
            assertEquals(it, accepted(input() + ("contacts" to (contacts() + ("telegram" to it))))["contacts"]["telegram"].stringValue())
        }
        listOf("@", "@owner name", "http://t.me/owner", "https://example.com/owner", "https://t.me.evil/owner", "https://t.me/").forEach {
            rejected(input() + ("contacts" to (contacts() + ("telegram" to it))))
        }
        listOf("https://t.me@evil.example/owner", "https://user@t.me/owner", "https://t.me:8443/owner", "https://t.me/owner name").forEach {
            rejected(input() + ("contacts" to (contacts() + ("telegram" to it))))
        }
    }

    @Test
    fun `colors timezone and version reject invalid values`() {
        listOf("primaryColor", "backgroundColor").forEach { field ->
            listOf("", "   ", "#fff", "#12345678", "123456", "#GGGGGG").forEach { rejected(input() + (field to it)) }
        }
        listOf("", " ", "+03:00", "UTC+03:00", "Unknown/Zone").forEach { rejected(input() + ("timezone" to it)) }
        rejected(input() + ("version" to -1))
        accepted(input() + ("version" to Long.MAX_VALUE))
    }

    @Test
    fun `checkout rules permit empty arrays and alternatives but reject duplicates unknown keys and empty groups`() {
        accepted(input() + ("checkoutContactRules" to mapOf("required" to emptyList<String>(), "any_of" to emptyList<List<String>>())))
        val alternatives = mapOf(
            "required" to listOf("name", "email"),
            "any_of" to listOf(listOf("phone", "telegram"), listOf("other_contact")),
        )
        val result = accepted(input() + ("checkoutContactRules" to alternatives))
        assertEquals(json.readTree(json.writeValueAsString(alternatives)), result["checkoutContactRules"])
        listOf(listOf("name", "name"), listOf("unknown"), listOf(" Name ")).forEach {
            rejected(input() + ("checkoutContactRules" to (rules() + ("required" to it))))
        }
        listOf(listOf(emptyList()), listOf(listOf("phone", "phone")), listOf(listOf("otherContact"))).forEach {
            rejected(input() + ("checkoutContactRules" to (rules() + ("any_of" to it))))
        }
    }

    @Test
    fun `NUL cannot reach PostgreSQL text fields and rejected values are not exposed`() {
        listOf("name", "description", "salesTerms").forEach { rejected(input() + (it to "PRIVATE\u0000VALUE")) }
        contacts().keys.forEach { rejected(input() + ("contacts" to (contacts() + (it to "PRIVATE\u0000VALUE")))) }
    }

    private fun accepted(input: Map<String, Any?>): JsonNode {
        val result = mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input)))
            .andExpect(status().isOk()).andReturn()
        return json.readTree(result.response.contentAsString)
    }

    private fun rejected(input: Map<String, Any?>, code: String = "VALIDATION_ERROR") {
        val result = mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(code)).andReturn()
        assertFalse(result.response.contentAsString.contains("PRIVATE"))
    }

    private fun input(): Map<String, Any?> = linkedMapOf(
        "name" to "", "description" to "", "contacts" to contacts(), "salesTerms" to "", "checkoutContactRules" to rules(),
        "primaryColor" to null, "backgroundColor" to null, "timezone" to "Europe/Moscow", "version" to 0,
    )

    private fun contacts(): Map<String, Any?> = linkedMapOf("phone" to null, "email" to null, "telegram" to null, "otherContact" to null)

    private fun rules(): Map<String, Any?> = linkedMapOf(
        "required" to listOf("name"),
        "any_of" to listOf(listOf("phone", "email", "telegram", "other_contact")),
    )

    @RestController
    class TestController(private val validator: ShopSettingsValidator) {
        @PutMapping(PATH)
        fun update(@RequestBody input: ShopSettingsDto): ShopSettingsDto = validator.normalize(input)
    }

    companion object {
        private const val PATH = "/test/shop-settings"
    }
}
