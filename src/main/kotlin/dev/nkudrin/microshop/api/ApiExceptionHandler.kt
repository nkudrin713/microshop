package dev.nkudrin.microshop.api

import org.slf4j.LoggerFactory
import org.springframework.beans.TypeMismatchException
import org.springframework.context.MessageSourceResolvable
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.validation.FieldError
import org.springframework.validation.method.ParameterErrors
import org.springframework.web.HttpMediaTypeNotAcceptableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.ServletWebRequest
import org.springframework.web.context.request.WebRequest
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.servlet.NoHandlerFoundException
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import org.springframework.web.servlet.resource.NoResourceFoundException
import java.net.URI

@RestControllerAdvice
class ApiExceptionHandler : ResponseEntityExceptionHandler() {
    private val log = LoggerFactory.getLogger(ApiExceptionHandler::class.java)

    @ExceptionHandler(ApiException::class)
    fun handleApiException(ex: ApiException, request: WebRequest): ResponseEntity<Any> {
        val headers = HttpHeaders()
        ex.retryAfterSeconds?.let { headers.set(HttpHeaders.RETRY_AFTER, it.toString()) }
        return response(ex.error, ex.error.status, headers, request)
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpectedException(ex: Exception, request: WebRequest): ResponseEntity<Any> {
        logInternalError(ex)
        return response(ApiError.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR, HttpHeaders.EMPTY, request)
    }

    override fun handleHttpRequestMethodNotSupported(
        ex: HttpRequestMethodNotSupportedException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? = handleExceptionInternal(ex, null, headers, status, request)

    override fun handleExceptionInternal(
        ex: Exception,
        body: Any?,
        headers: HttpHeaders,
        statusCode: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        if ((request as? ServletWebRequest)?.response?.isCommitted == true) {
            return null
        }
        val error =
            when {
                statusCode.is5xxServerError -> ApiError.INTERNAL_ERROR
                ex is HttpMessageNotReadableException -> ApiError.MALFORMED_JSON
                ex is MethodArgumentNotValidException || ex is HandlerMethodValidationException -> ApiError.VALIDATION_ERROR
                ex is TypeMismatchException -> ApiError.TYPE_MISMATCH
                ex is HttpRequestMethodNotSupportedException -> ApiError.METHOD_NOT_ALLOWED
                ex is HttpMediaTypeNotSupportedException -> ApiError.UNSUPPORTED_MEDIA_TYPE
                ex is HttpMediaTypeNotAcceptableException -> ApiError.NOT_ACCEPTABLE
                ex is NoHandlerFoundException || ex is NoResourceFoundException -> ApiError.NOT_FOUND
                else -> ApiError.INVALID_REQUEST
            }
        if (statusCode.is5xxServerError) {
            logInternalError(ex)
        }
        val errors = if (error == ApiError.VALIDATION_ERROR) validationErrors(ex) else emptyList()
        val response = response(error, statusCode, headers, request, errors)
        return super.handleExceptionInternal(ex, response.body, response.headers, statusCode, request)
    }

    private fun response(
        error: ApiError,
        status: HttpStatusCode,
        headers: HttpHeaders,
        request: WebRequest,
        errors: List<ValidationError> = emptyList(),
    ): ResponseEntity<Any> {
        val problem =
            ProblemDetail.forStatusAndDetail(status, error.detail).apply {
                type = URI.create("about:blank")
                title = HttpStatus.resolve(status.value())?.reasonPhrase ?: "Error"
                instance = URI.create((request as ServletWebRequest).request.requestURI)
                setProperty("code", error.name)
                if (errors.isNotEmpty()) {
                    setProperty("errors", errors.distinct().sortedWith(compareBy({ it.field.orEmpty() }, { it.code })))
                }
            }
        val responseHeaders =
            HttpHeaders().apply {
                putAll(headers)
                contentType = MediaType.APPLICATION_PROBLEM_JSON
            }
        return ResponseEntity(problem, responseHeaders, status)
    }

    private fun validationErrors(ex: Exception): List<ValidationError> = when (ex) {
        is MethodArgumentNotValidException -> {
            ex.bindingResult.allErrors.map {
                validationError((it as? FieldError)?.field, it)
            }
        }

        is HandlerMethodValidationException -> {
            ex.parameterValidationResults.flatMap { result ->
                if (result is ParameterErrors) {
                    result.allErrors.map { validationError((it as? FieldError)?.field, it) }
                } else {
                    val parameter = result.methodParameter
                    val field =
                        parameter.getParameterAnnotation(RequestParam::class.java)?.let {
                            it.name.ifBlank { it.value }.ifBlank { null }
                        } ?: parameter.getParameterAnnotation(PathVariable::class.java)?.let {
                            it.name.ifBlank { it.value }.ifBlank { null }
                        } ?: parameter.parameterName
                    result.resolvableErrors.map { validationError(field, it) }
                }
            } + ex.crossParameterValidationResults.map { validationError(null, it) }
        }

        else -> {
            emptyList()
        }
    }

    private fun validationError(field: String?, error: MessageSourceResolvable): ValidationError {
        val code = error.codes?.lastOrNull()
        val message =
            when (code) {
                "NotBlank" -> "Must not be blank."
                "NotNull" -> "Must not be null."
                "NotEmpty" -> "Must not be empty."
                "Size" -> "Size is outside the allowed range."
                "Min", "Max" -> "Value is outside the allowed range."
                "Positive" -> "Must be positive."
                "PositiveOrZero" -> "Must be zero or positive."
                "Email" -> "Must be a valid email address."
                "Pattern" -> "Must match the required format."
                else -> return ValidationError(field, "Invalid", "Invalid value.")
            }
        return ValidationError(field, code, message)
    }

    private fun logInternalError(ex: Exception) {
        log.error("Unhandled API exception: {}", ex.javaClass.name)
    }

    data class ValidationError(val field: String?, val code: String, val message: String)
}
