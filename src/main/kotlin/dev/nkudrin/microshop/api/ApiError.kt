package dev.nkudrin.microshop.api

import org.springframework.http.HttpStatus

enum class ApiError(val status: HttpStatus, val detail: String) {
    MALFORMED_JSON(HttpStatus.BAD_REQUEST, "The request body is missing or malformed."),
    TYPE_MISMATCH(HttpStatus.BAD_REQUEST, "A request parameter has an invalid type."),
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Request validation failed."),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "The request is invalid."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "The requested resource was not found."),
    CONFLICT(HttpStatus.CONFLICT, "The operation conflicts with the current state."),
    SETUP_TOKEN_INVALID(HttpStatus.FORBIDDEN, "The setup token is invalid."),
    SETUP_ALREADY_INITIALIZED(HttpStatus.CONFLICT, "The installation is already initialized."),
    CSRF_TOKEN_INVALID(HttpStatus.FORBIDDEN, "The CSRF token is missing or invalid."),
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "Authentication is required."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid login or password."),
    LOGIN_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many failed login attempts. Try again later."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "Access is denied."),
    HTTPS_REQUIRED(HttpStatus.BAD_REQUEST, "HTTPS is required."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "The HTTP method is not supported for this resource."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "The request content type is not supported."),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "The requested response format is not supported."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "An internal error occurred."),
}
