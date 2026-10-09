package dev.nkudrin.microshop.audit

import java.math.BigDecimal
import java.math.BigInteger
import java.util.Collections

class AuditSnapshot private constructor(internal val data: Map<String, Any?>) {
    companion object {
        fun select(source: Map<String, Any?>, vararg allowedFields: String): AuditSnapshot {
            require(allowedFields.isNotEmpty()) { "Audit snapshot requires an explicit field allowlist" }
            val selected = allowedFields.associateWith { field ->
                require(source.containsKey(field)) { "Audit snapshot field is missing" }
                val value = source[field]
                require(isScalar(value)) { "Audit snapshot supports only JSON scalar values" }
                value
            }
            return AuditSnapshot(Collections.unmodifiableMap(selected))
        }

        private fun isScalar(value: Any?): Boolean = when (value) {
            null, is String, is Boolean, is Byte, is Short, is Int, is Long, is BigInteger, is BigDecimal -> true
            is Float -> value.isFinite()
            is Double -> value.isFinite()
            else -> false
        }
    }
}
