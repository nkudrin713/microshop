package dev.nkudrin.microshop

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class MicroshopApplication

fun main(args: Array<String>) {
    runApplication<MicroshopApplication>(*args)
}
