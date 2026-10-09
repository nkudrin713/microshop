package dev.nkudrin.microshop.audit

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface AuditLogRepository : JpaRepository<AuditLog, UUID>
