package dev.nkudrin.microshop.shop

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query

interface ShopSettingsRepository : JpaRepository<ShopSettings, Short> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select settings from ShopSettings settings where settings.id = 1")
    fun lockSettings(): ShopSettings
}
