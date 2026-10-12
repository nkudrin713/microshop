package dev.nkudrin.microshop.security

import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class CsrfController {
    @GetMapping("/api/csrf")
    fun csrf(token: CsrfToken): ResponseEntity<CsrfResponse> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(CsrfResponse(token.token, token.headerName))
}

data class CsrfResponse(
    val token: String,
    val headerName: String,
)
