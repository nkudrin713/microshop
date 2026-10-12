package dev.nkudrin.microshop

import java.security.SecureRandom
import java.util.Base64

object TestInstallationSecrets {
    val appSecret: String = randomSecret()
    val setupToken: String = randomSecret()

    fun randomSecret(): String =
        Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
}
