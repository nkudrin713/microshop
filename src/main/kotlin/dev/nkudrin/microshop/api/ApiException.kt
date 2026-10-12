package dev.nkudrin.microshop.api

class ApiException(
    val error: ApiError,
) : RuntimeException(error.detail)
