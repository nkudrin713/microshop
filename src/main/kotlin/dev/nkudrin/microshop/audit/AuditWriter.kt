package dev.nkudrin.microshop.audit

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class AuditWriter(private val repository: AuditLogRepository) {
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = [Exception::class])
    fun record(
        actorType: AuditActorType,
        actorAdminId: UUID?,
        action: String,
        entityType: String,
        entityId: String,
        before: AuditSnapshot? = null,
        after: AuditSnapshot? = null,
    ): UUID {
        require(actorType != AuditActorType.ADMIN || actorAdminId != null) { "Admin audit requires an actor" }
        val entry =
            AuditLog(
                id = UUID.randomUUID(),
                actorType = actorType,
                actorAdminId = actorAdminId,
                action = action,
                entityType = entityType,
                entityId = entityId,
                beforeData = before?.data,
                afterData = after?.data,
                createdAt = Instant.now(),
            )
        repository.saveAndFlush(entry)
        return entry.id
    }
}
