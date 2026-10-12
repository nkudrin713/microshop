package dev.nkudrin.microshop.setup

import jakarta.annotation.PostConstruct
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

@Component
@DependsOnDatabaseInitialization
class InstallationInitializer(
    private val service: InstallationService,
    private val environment: Environment,
) {
    @PostConstruct
    fun initialize() {
        service.initialize(environment.getProperty("microshop.app-secret"), environment.getProperty("microshop.setup-token"))
    }
}
