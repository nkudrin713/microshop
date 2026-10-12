package dev.nkudrin.microshop.shop

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

@Entity
@Table(name = "shop_settings")
class ShopSettings(
    @Id val id: Short = 1,
    @Column(nullable = false, columnDefinition = "text") var name: String,
    @Column(nullable = false, columnDefinition = "text") var description: String,
    @Column(columnDefinition = "text") var phone: String?,
    @Column(columnDefinition = "text") var email: String?,
    @Column(columnDefinition = "text") var telegram: String?,
    @Column(name = "other_contact", columnDefinition = "text") var otherContact: String?,
    @Column(name = "sales_terms", nullable = false, columnDefinition = "text") var salesTerms: String,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "checkout_contact_rules", nullable = false, columnDefinition = "jsonb")
    var checkoutContactRules: CheckoutContactRules,
    @Column(name = "primary_color", columnDefinition = "text") var primaryColor: String?,
    @Column(name = "background_color", columnDefinition = "text") var backgroundColor: String?,
    @Column(nullable = false, columnDefinition = "text") var timezone: String,
    @Column(nullable = false) var published: Boolean,
    @Column(name = "sales_enabled", nullable = false) var salesEnabled: Boolean,
    @Column(name = "sales_disabled_reason", columnDefinition = "text") var salesDisabledReason: String?,
    @Column(nullable = false) var version: Long,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
)

data class CheckoutContactRules(val required: List<String>, @JsonProperty("any_of") val anyOf: List<List<String>>)
