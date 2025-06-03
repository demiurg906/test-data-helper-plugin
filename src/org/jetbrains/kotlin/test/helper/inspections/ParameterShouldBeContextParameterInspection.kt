package org.jetbrains.kotlin.test.helper.inspections

import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInsight.options.JavaClassValidator
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.codeInspection.options.OptPane
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElementVisitor
import com.intellij.refactoring.safeDelete.SafeDeleteProcessor
import com.intellij.util.containers.OrderedSet
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.sourcePsi
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.idea.base.psi.textRangeIn
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtVisitorVoid
import kotlin.random.Random

class ParameterShouldBeContextParameterInspection : LocalInspectionTool() {
    var relevantTypes: OrderedSet<String> = OrderedSet()

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : KtVisitorVoid() {
            override fun visitNamedFunction(function: KtNamedFunction) {
                super.visitNamedFunction(function)
                analyze(function) {
                    val symbol = function.symbol

                    val parameters = symbol.valueParameters
                        .withIndex()
                        .filter {
                            val parameterType =
                                (it.value.returnType as? KaClassType)?.classId?.asFqNameString() ?: return@filter false
                            parameterType in relevantTypes
                        }

                    if (parameters.isNotEmpty()) {
                        val types = parameters.joinToString {
                            "'${(it.value.returnType as KaClassType).classId.shortClassName.identifierOrNullIfSpecial ?: ""}'"
                        }
                        holder.registerProblem(
                            function,
                            "Function has parameter(s) of type $types that should be context parameter(s).",
                            ProblemHighlightType.WEAK_WARNING,
                            parameters.first().value.sourcePsi<KtElement>()?.textRangeIn(function),
                            SafeDeleteParameterQuickFix(parameters.map { it.index }.toIntArray())
                        )
                    }
                }
            }
        }
    }

    override fun getOptionsPane(): OptPane {
        return OptPane.pane(
            OptPane.stringList("relevantTypes", "Types", JavaClassValidator())
        )
    }

    override fun getDisplayName(): String = "Function parameter should be context parameter."
    override fun getGroupDisplayName(): String = "Kotlin"
    override fun isEnabledByDefault(): Boolean = true
    override fun getShortName(): String = "ParameterShouldBeContextParameter"
    override fun getStaticDescription(): String =
        "Detects functions that have parameters that should be context parameters."

    private class SafeDeleteParameterQuickFix(
        private val indices: IntArray,
    ) : LocalQuickFix {
        override fun getName(): String = "Convert to context parameters"
        override fun getFamilyName(): String = name
        override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor): IntentionPreviewInfo {
            return IntentionPreviewInfo.EMPTY
        }

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val function = descriptor.psiElement as? KtNamedFunction ?: return

            val psiFactory = KtPsiFactory(project)
            val contextReceiverList = function.contextReceiverList
            val newContextReceiverList = StringBuilder(contextReceiverList?.text ?: "context()")
            contextReceiverList?.delete()

            for ((i, parameter) in function.valueParameters.withIndex()) {
                if (i !in indices) continue
                val parameterName = parameter.name ?: continue
                val parameterType = parameter.typeReference?.text ?: continue

                newContextReceiverList.insert(newContextReceiverList.length - 1, ", $parameterName: $parameterType")
                // Rename the existing parameter to make sure that Safe Delete doesn't find any usages.
                val tmpName = "temp${Random.nextInt(0, Int.MAX_VALUE)}"
                parameter.setName(tmpName)
            }

            if (newContextReceiverList.startsWith("context(,")) {
                newContextReceiverList.delete("context(".length, "context(".length + 1)
            }

            val newFunction = function.replace(
                psiFactory.createFunction("$newContextReceiverList\n${function.text}")
            ) as KtNamedFunction

            val processor = SafeDeleteProcessor.createInstance(
                project,
                null,
                newFunction.valueParameters.filterIndexed { i, _ -> i in indices }.toTypedArray(),
                false,
                false,
                false
            )

            DumbService.getInstance(project).smartInvokeLater(processor)
        }
    }
}
