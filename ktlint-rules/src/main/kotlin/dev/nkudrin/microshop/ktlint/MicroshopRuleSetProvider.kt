package dev.nkudrin.microshop.ktlint

import com.pinterest.ktlint.cli.ruleset.core.api.RuleSetProviderV3
import com.pinterest.ktlint.rule.engine.core.api.RuleProvider
import com.pinterest.ktlint.rule.engine.core.api.RuleSetId

class MicroshopRuleSetProvider : RuleSetProviderV3(RuleSetId("microshop")) {
    override fun getRuleProviders(): Set<RuleProvider> = setOf(RuleProvider { PropertyAnnotationRule() })
}
