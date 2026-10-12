package dev.nkudrin.microshop.setup

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query

interface InstallationStateRepository : JpaRepository<InstallationState, Short> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select state from InstallationState state where state.id = 1")
    fun lockState(): InstallationState
}
