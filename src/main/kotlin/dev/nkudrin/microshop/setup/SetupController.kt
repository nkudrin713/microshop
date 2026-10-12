package dev.nkudrin.microshop.setup

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/setup")
class SetupController(private val service: SetupService) {
    @GetMapping("/status")
    fun status(): SetupStatus = service.status()

    @PostMapping("/owner")
    @ResponseStatus(HttpStatus.CREATED)
    fun createOwner(@RequestBody request: CreateOwnerRequest): OwnerResponse = service.createOwner(request)
}
