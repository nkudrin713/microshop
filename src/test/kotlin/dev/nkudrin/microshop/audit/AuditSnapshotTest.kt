package dev.nkudrin.microshop.audit

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test

class AuditSnapshotTest {
    @Test
    fun `snapshot copies only explicitly allowed fields from input containing secrets`() {
        val source = mutableMapOf<String, Any?>(
            "status" to "ACTIVE", "password" to "control-password", "token" to "control-token", "secret" to "control-secret",
        )
        val snapshot = AuditSnapshot.select(source, "status")
        source["status"] = "CHANGED"
        assertEquals(mapOf("status" to "ACTIVE"), snapshot.data)
        assertFailsWith<UnsupportedOperationException> { (snapshot.data as MutableMap)["token"] = "control-token" }
    }

    @Test
    fun `snapshot rejects full objects and nested data instead of serializing them`() {
        val credential = TestCredentials("control-password", "control-token", "control-secret")
        listOf(credential, mapOf("password" to credential.password), listOf(credential)).forEach { unsafeValue ->
            assertFailsWith<IllegalArgumentException> {
                AuditSnapshot.select(mapOf("data" to unsafeValue), "data")
            }
        }
    }

    @Test
    fun `snapshot requires existing allowed fields and finite JSON numbers`() {
        assertFailsWith<IllegalArgumentException> { AuditSnapshot.select(mapOf("status" to "ACTIVE")) }
        assertFailsWith<IllegalArgumentException> { AuditSnapshot.select(emptyMap(), "missing") }
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { value ->
            assertFailsWith<IllegalArgumentException> { AuditSnapshot.select(mapOf("value" to value), "value") }
        }
    }

    private data class TestCredentials(val password: String, val token: String, val secret: String)
}
