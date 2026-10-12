package dev.nkudrin.microshop.setup

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

enum class InstallationStatus { UNINITIALIZED, INITIALIZED }

@Entity
@Table(name = "installation_state")
class InstallationState(
    @Id val id: Short = 1,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    var status: InstallationStatus = InstallationStatus.UNINITIALIZED,
    @Column(name = "setup_token_hash", columnDefinition = "bytea") var setupTokenHash: ByteArray?,
    @Column(name = "app_secret_hash", nullable = false, columnDefinition = "bytea") val appSecretHash: ByteArray,
    @Column(name = "owner_admin_id") var ownerAdminId: UUID? = null,
    @Column(name = "initialized_at") var initializedAt: Instant? = null,
)
