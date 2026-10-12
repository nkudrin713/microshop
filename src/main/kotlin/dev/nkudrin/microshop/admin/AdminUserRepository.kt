package dev.nkudrin.microshop.admin

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface AdminUserRepository : JpaRepository<AdminUser, UUID> {
    fun findByLogin(login: String): AdminUser?
}
