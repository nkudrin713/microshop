package dev.nkudrin.microshop.ktlint

import com.pinterest.ktlint.rule.engine.core.api.AutocorrectDecision
import com.pinterest.ktlint.rule.engine.core.api.ElementType.ANNOTATION
import com.pinterest.ktlint.rule.engine.core.api.ElementType.ANNOTATION_ENTRY
import com.pinterest.ktlint.rule.engine.core.api.ElementType.CLASS_BODY
import com.pinterest.ktlint.rule.engine.core.api.ElementType.MODIFIER_LIST
import com.pinterest.ktlint.rule.engine.core.api.ElementType.PROPERTY
import com.pinterest.ktlint.rule.engine.core.api.ElementType.VALUE_PARAMETER
import com.pinterest.ktlint.rule.engine.core.api.ElementType.VAL_KEYWORD
import com.pinterest.ktlint.rule.engine.core.api.ElementType.VAR_KEYWORD
import com.pinterest.ktlint.rule.engine.core.api.Rule
import com.pinterest.ktlint.rule.engine.core.api.RuleAutocorrectApproveHandler
import com.pinterest.ktlint.rule.engine.core.api.RuleId
import com.pinterest.ktlint.rule.engine.core.api.children20
import com.pinterest.ktlint.rule.engine.core.api.ifAutocorrectAllowed
import com.pinterest.ktlint.rule.engine.core.api.indentWithoutNewlinePrefix
import com.pinterest.ktlint.rule.engine.core.api.isWhiteSpaceWithNewline20
import com.pinterest.ktlint.rule.engine.core.api.lastChildLeafOrSelf20
import com.pinterest.ktlint.rule.engine.core.api.nextLeaf
import com.pinterest.ktlint.rule.engine.core.api.upsertWhitespaceAfterMe
import org.jetbrains.kotlin.com.intellij.lang.ASTNode

class PropertyAnnotationRule :
    Rule(RuleId("microshop:property-annotation"), About(maintainer = "Microshop")),
    RuleAutocorrectApproveHandler {
    override fun beforeVisitChildNodes(node: ASTNode, emit: (Int, String, Boolean) -> AutocorrectDecision) {
        if (node.elementType != MODIFIER_LIST) return
        val declaration = node.treeParent
        val isProperty = when (declaration.elementType) {
            PROPERTY -> declaration.treeParent.elementType == CLASS_BODY
            VALUE_PARAMETER -> declaration.findChildByType(VAL_KEYWORD) != null || declaration.findChildByType(VAR_KEYWORD) != null
            else -> false
        }
        if (!isProperty) return
        node.children20.filter { it.elementType == ANNOTATION_ENTRY || it.elementType == ANNOTATION }.forEach { annotation ->
            val last = annotation.lastChildLeafOrSelf20
            if (!last.nextLeaf.isWhiteSpaceWithNewline20) {
                emit(annotation.startOffset, "Place property annotations on separate lines above the property", true).ifAutocorrectAllowed {
                    last.upsertWhitespaceAfterMe("\n" + declaration.indentWithoutNewlinePrefix)
                }
            }
        }
    }
}
