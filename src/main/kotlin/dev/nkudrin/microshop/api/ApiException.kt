package dev.nkudrin.microshop.api

class ApiException(val error: ApiError, val retryAfterSeconds: Long? = null) : RuntimeException(error.detail)
