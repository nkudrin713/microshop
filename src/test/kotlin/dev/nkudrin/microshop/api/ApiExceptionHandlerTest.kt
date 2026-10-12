package dev.nkudrin.microshop.api

import jakarta.servlet.http.Cookie
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.hasItems
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import kotlin.test.assertFalse

@WebMvcTest(ApiExceptionHandlerTest.TestController::class)
// This MVC slice verifies error serialization; Security is covered by setup integration tests.
@AutoConfigureMockMvc(addFilters = false)
@Import(ApiExceptionHandler::class, ApiExceptionHandlerTest.TestController::class)
@ExtendWith(OutputCaptureExtension::class)
class ApiExceptionHandlerTest {
    @Autowired
    private lateinit var mvc: MockMvc

    @Test
    fun `malformed JSON has a safe problem response`(output: CapturedOutput) {
        val result =
            expectProblem(
                post("$PATH/input")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"BODY_SECRET","quantity": """),
                ApiError.MALFORMED_JSON,
                "$PATH/input",
            )
        assertNoSensitiveValues(result, output)
    }

    @Test
    fun `invalid parameter type excludes its value and query string`(output: CapturedOutput) {
        val result =
            expectProblem(
                get("$PATH/parameters").queryParam("count", "QUERY_SECRET"),
                ApiError.TYPE_MISMATCH,
                "$PATH/parameters",
            )
        assertNoSensitiveValues(result, output)
    }

    @Test
    fun `DTO validation identifies fields without rejected values or annotation messages`(output: CapturedOutput) {
        val result =
            expectProblem(
                post("$PATH/input")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"BODY_SECRET","quantity":0,"password":""}"""),
                ApiError.VALIDATION_ERROR,
                "$PATH/input",
            )
        jsonPath("$.errors[*].field", hasItems("name", "quantity", "password")).match(result)
        jsonPath("$.errors[*].code", hasItems("Size", "NotBlank", "Min")).match(result)
        jsonPath("$.errors[*].message", hasItems("Must not be blank.", "Value is outside the allowed range.")).match(result)
        assertNoSensitiveValues(result, output)
    }

    @Test
    fun `method parameter validation identifies the API parameter`(output: CapturedOutput) {
        val result =
            expectProblem(
                get("$PATH/validated-parameters").queryParam("count", "0"),
                ApiError.VALIDATION_ERROR,
                "$PATH/validated-parameters",
            )
        jsonPath("$.errors[0].field").value("count").match(result)
        jsonPath("$.errors[0].code").value("Min").match(result)
        assertNoSensitiveValues(result, output)
    }

    @Test
    fun `unsupported method preserves Allow header`(output: CapturedOutput) {
        val result = expectProblem(post("$PATH/parameters"), ApiError.METHOD_NOT_ALLOWED, "$PATH/parameters")
        header().string(HttpHeaders.ALLOW, containsString("GET")).match(result)
        assertNoSensitiveValues(result, output)
    }

    @Test
    fun `unsupported content type uses the common contract`(output: CapturedOutput) {
        val result =
            expectProblem(
                post("$PATH/input").contentType(MediaType.TEXT_PLAIN).content("BODY_SECRET"),
                ApiError.UNSUPPORTED_MEDIA_TYPE,
                "$PATH/input",
            )
        assertNoSensitiveValues(result, output)
    }

    @Test
    fun `unsupported response format still returns problem JSON`(output: CapturedOutput) {
        val result =
            expectProblem(
                get("$PATH/parameters").queryParam("count", "1").accept(MediaType.APPLICATION_PDF),
                ApiError.NOT_ACCEPTABLE,
                "$PATH/parameters",
            )
        assertNoSensitiveValues(result, output)
    }

    @Test
    fun `unexpected exception excludes SQL and nested secrets from response and logs`(output: CapturedOutput) {
        val result = expectProblem(get("$PATH/failure"), ApiError.INTERNAL_ERROR, "$PATH/failure")
        assertFalse(result.response.contentAsString.contains("IllegalStateException"))
        assertNoSensitiveValues(result, output)
    }

    @Test
    fun `operation errors use fixed codes and statuses`(output: CapturedOutput) {
        listOf(ApiError.INVALID_REQUEST, ApiError.NOT_FOUND, ApiError.CONFLICT).forEach { error ->
            val result = expectProblem(get("$PATH/operation/${error.name}"), error, "$PATH/operation/${error.name}")
            assertNoSensitiveValues(result, output)
        }
    }

    @Test
    fun `missing parameter uses the common contract`(output: CapturedOutput) {
        val result = expectProblem(get("$PATH/parameters"), ApiError.INVALID_REQUEST, "$PATH/parameters")
        assertNoSensitiveValues(result, output)
    }

    private fun expectProblem(
        request: MockHttpServletRequestBuilder,
        error: ApiError,
        instance: String,
    ): MvcResult =
        mvc
            .perform(
                request
                    .header(HttpHeaders.AUTHORIZATION, "Bearer AUTH_SECRET")
                    .cookie(Cookie("SESSION", "COOKIE_SECRET")),
            ).andExpect(status().`is`(error.status.value()))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.type").value("about:blank"))
            .andExpect(jsonPath("$.title").value(error.status.reasonPhrase))
            .andExpect(jsonPath("$.status").value(error.status.value()))
            .andExpect(jsonPath("$.detail").value(error.detail))
            .andExpect(jsonPath("$.instance").value(instance))
            .andExpect(jsonPath("$.code").value(error.name))
            .andExpect(jsonPath("$.exception").doesNotExist())
            .andExpect(jsonPath("$.trace").doesNotExist())
            .andReturn()

    private fun assertNoSensitiveValues(
        result: MvcResult,
        output: CapturedOutput,
    ) {
        listOf("BODY_SECRET", "QUERY_SECRET", "AUTH_SECRET", "COOKIE_SECRET", "SQL_SECRET", "PASSWORD_SECRET", "MESSAGE_SECRET")
            .forEach { secret ->
                assertFalse(result.response.contentAsString.contains(secret), "Sensitive value in response: $secret")
                assertFalse(output.all.contains(secret), "Sensitive value in logs: $secret")
            }
    }

    @RestController
    @RequestMapping(PATH)
    class TestController {
        @PostMapping("/input", consumes = [MediaType.APPLICATION_JSON_VALUE])
        fun input(
            @Valid @RequestBody input: Input,
        ): Input = input

        @GetMapping("/parameters", produces = [MediaType.APPLICATION_JSON_VALUE])
        fun parameters(
            @RequestParam count: Int,
        ): Map<String, Int> = mapOf("count" to count)

        @GetMapping("/validated-parameters")
        fun validatedParameters(
            @RequestParam("count") @Min(1) count: Int,
        ): Map<String, Int> = mapOf("count" to count)

        @GetMapping("/failure")
        fun failure(): Nothing =
            throw IllegalStateException(
                "SQL_SECRET SELECT password_hash FROM admin_user",
                RuntimeException("PASSWORD_SECRET"),
            )

        @GetMapping("/operation/{error}")
        fun operation(
            @PathVariable error: ApiError,
        ): Nothing = throw ApiException(error)
    }

    data class Input(
        @field:NotBlank @field:Size(max = 4) val name: String,
        @field:Min(1) val quantity: Int,
        @field:NotBlank(message = "MESSAGE_SECRET") val password: String,
    )

    companion object {
        private const val PATH = "/__test/errors"
    }
}
