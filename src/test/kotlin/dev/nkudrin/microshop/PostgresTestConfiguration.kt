package dev.nkudrin.microshop

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.test.context.DynamicPropertyRegistrar
import org.testcontainers.postgresql.PostgreSQLContainer

@TestConfiguration(proxyBeanMethods = false)
class PostgresTestConfiguration {
    @Bean
    fun installationProperties(): DynamicPropertyRegistrar = DynamicPropertyRegistrar { registry ->
        registry.add("microshop.app-secret") { TestInstallationSecrets.appSecret }
        registry.add("microshop.setup-token") { TestInstallationSecrets.setupToken }
    }

    @Bean
    @ServiceConnection
    fun postgres(): PostgreSQLContainer = PostgreSQLContainer("postgres:18.6")
        .withDatabaseName("microshop_test")
        .withUsername("microshop_test")
        .withPassword("microshop_test")
}
