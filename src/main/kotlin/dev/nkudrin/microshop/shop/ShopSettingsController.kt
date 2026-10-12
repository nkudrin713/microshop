package dev.nkudrin.microshop.shop

import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/admin/settings")
class ShopSettingsController(private val service: ShopSettingsService) {
    @GetMapping
    fun read(): ShopSettingsDto = service.read()

    @PutMapping
    fun update(@RequestBody input: ShopSettingsDto, @AuthenticationPrincipal adminId: UUID): ShopSettingsDto =
        service.update(input, adminId)
}
