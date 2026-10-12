package dev.nkudrin.microshop

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration

@SpringBootApplication(exclude = [UserDetailsServiceAutoConfiguration::class])
class MicroshopApplication

fun main(args: Array<String>) {
    runApplication<MicroshopApplication>(*args)
}
