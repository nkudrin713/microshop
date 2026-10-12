package dev.nkudrin.microshop.setup

import java.security.MessageDigest
import java.util.Base64

internal object SetupSecrets {
    private val format = Regex("[A-Za-z0-9_-]{43}")

    fun decode(value: String?): ByteArray? {
        if (value == null || !format.matches(value)) return null
        val decoded = Base64.getUrlDecoder().decode(value)
        return decoded.takeIf {
            it.size == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(it) == value
        }
    }

    fun hash(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)

    fun tokenHash(value: String): ByteArray = hash(value.toByteArray(Charsets.UTF_8))
}
