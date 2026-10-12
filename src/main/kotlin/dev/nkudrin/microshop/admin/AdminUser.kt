package dev.nkudrin.microshop.admin

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "admin_user")
class AdminUser(
    @Id val id: UUID,
    @Column(nullable = false, columnDefinition = "text") val login: String,
    @Column(name = "password_hash", nullable = false, columnDefinition = "text") val passwordHash: String,
    @Column(name = "display_name", nullable = false, columnDefinition = "text") val displayName: String,
    @Column(nullable = false) val enabled: Boolean,
    @Column(name = "created_at", nullable = false) val createdAt: Instant,
    @Column(name = "updated_at", nullable = false) val updatedAt: Instant,
)
