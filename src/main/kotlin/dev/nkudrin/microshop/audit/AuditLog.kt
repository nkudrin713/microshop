package dev.nkudrin.microshop.audit

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

enum class AuditActorType { ADMIN, SYSTEM }

@Entity
@Table(name = "audit_log")
class AuditLog internal constructor(
    @Id
    val id: UUID,
    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, columnDefinition = "text")
    val actorType: AuditActorType,
    @Column(name = "actor_admin_id")
    val actorAdminId: UUID?,
    @Column(nullable = false, columnDefinition = "text")
    val action: String,
    @Column(name = "entity_type", nullable = false, columnDefinition = "text")
    val entityType: String,
    @Column(name = "entity_id", nullable = false, columnDefinition = "text")
    val entityId: String,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_data", columnDefinition = "jsonb")
    val beforeData: Map<String, Any?>?,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_data", columnDefinition = "jsonb")
    val afterData: Map<String, Any?>?,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
)
