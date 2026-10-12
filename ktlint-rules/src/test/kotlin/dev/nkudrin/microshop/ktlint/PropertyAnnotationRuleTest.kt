package dev.nkudrin.microshop.ktlint

import com.pinterest.ktlint.cli.ruleset.core.api.RuleSetProviderV3
import com.pinterest.ktlint.rule.engine.api.Code
import com.pinterest.ktlint.rule.engine.api.EditorConfigOverride
import com.pinterest.ktlint.rule.engine.api.KtLintRuleEngine
import com.pinterest.ktlint.rule.engine.api.LintError
import com.pinterest.ktlint.rule.engine.core.api.AutocorrectDecision
import com.pinterest.ktlint.rule.engine.core.api.editorconfig.CODE_STYLE_PROPERTY
import com.pinterest.ktlint.rule.engine.core.api.editorconfig.CodeStyleValue
import com.pinterest.ktlint.rule.engine.core.api.editorconfig.MAX_LINE_LENGTH_PROPERTY
import com.pinterest.ktlint.ruleset.standard.StandardRuleSetProvider
import org.junit.jupiter.api.Test
import java.util.ServiceLoader
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PropertyAnnotationRuleTest {
    private val engine = KtLintRuleEngine(
        ruleProviders = StandardRuleSetProvider().getRuleProviders() + MicroshopRuleSetProvider().getRuleProviders(),
        editorConfigOverride = EditorConfigOverride.from(
            CODE_STYLE_PROPERTY to CodeStyleValue.intellij_idea,
            MAX_LINE_LENGTH_PROPERTY to 140,
        ),
    )

    @Test
    fun `body fields require separate annotation lines with or without arguments`() {
        val source = """
            class Example {
                @Autowired private lateinit var service: Service

                @Column(name = "title") val title: String = ""
            }
        """.trimIndent() + "\n"
        assertEquals(2, violations(source).size)
        val expected = """
            class Example {
                @Autowired
                private lateinit var service: Service

                @Column(name = "title")
                val title: String = ""
            }
        """.trimIndent() + "\n"
        assertEquals(expected, format(source))
        assertTrue(violations(expected).isEmpty())
    }

    @Test
    fun `constructor fields including use site annotations wrap above properties`() {
        val source = "class Example(@Id val id: Long, @field:Email var email: String?)\n"
        assertEquals(2, violations(source).size)
        val expected = """
            class Example(
                @Id
                val id: Long,
                @field:Email
                var email: String?,
            )
        """.trimIndent() + "\n"
        assertEquals(expected, format(source))
        assertEquals(expected, format(expected))
    }

    @Test
    fun `multiple annotations and annotation arrays stay above the field`() {
        val source = """
            class Example {
                @First @Second val value: String = ""

                @[First Second] var other: String = ""
            }
        """.trimIndent() + "\n"
        val formatted = format(source)
        assertTrue(formatted.contains("@First\n    @Second\n    val value"))
        assertTrue(formatted.contains("@[First Second]\n    var other"))
        assertTrue(violations(formatted).isEmpty())
    }

    @Test
    fun `method parameters constructor arguments and local variables keep existing formatting`() {
        val source = """
            class Example(@Inject dependency: Service) {
                fun me(@AuthenticationPrincipal id: String): String = id

                fun local() {
                    @Suppress("unused") val value = 1
                }
            }
        """.trimIndent() + "\n"
        assertTrue(violations(source).isEmpty())
        assertTrue(format(source).contains("fun me(@AuthenticationPrincipal id: String): String = id"))
    }

    @Test
    fun `SPI registers the rule with ktlint`() {
        val provider = ServiceLoader.load(RuleSetProviderV3::class.java).single { it.id.value == "microshop" }
        assertTrue(provider is MicroshopRuleSetProvider)
        assertEquals(1, provider.getRuleProviders().size)
    }

    private fun violations(source: String): List<LintError> {
        val errors = mutableListOf<LintError>()
        engine.lint(Code.fromSnippet(source)) { if (it.ruleId.value == "microshop:property-annotation") errors.add(it) }
        return errors
    }

    private fun format(source: String): String = engine.format(Code.fromSnippet(source)) { AutocorrectDecision.ALLOW_AUTOCORRECT }
}
