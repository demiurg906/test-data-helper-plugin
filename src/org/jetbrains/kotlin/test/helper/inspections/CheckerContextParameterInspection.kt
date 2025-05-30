package org.jetbrains.kotlin.test.helper.inspections

import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElementVisitor
import com.intellij.refactoring.safeDelete.SafeDeleteProcessor
import org.jetbrains.kotlin.idea.base.psi.textRangeIn
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtVisitorVoid
import kotlin.math.absoluteValue
import kotlin.random.Random

class CheckerContextParameterInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : KtVisitorVoid() {
            override fun visitNamedFunction(function: KtNamedFunction) {
                super.visitNamedFunction(function)

                for (parameter in function.valueParameters) {
                    val parameterType = parameter.typeReference?.text ?: continue

                    if (isRelevantType(parameterType)) {
                        holder.registerProblem(
                            function,
                            "Function has parameter of type $parameterType",
                            ProblemHighlightType.WEAK_WARNING,
                            parameter.textRangeIn(function),
                            SafeDeleteParameterQuickFix()
                        )
                        break
                    }
                }
            }
        }
    }

    override fun getDisplayName(): String = "Function has CheckerContext or DiagnosticReporter parameter"
    override fun getGroupDisplayName(): String = "Kotlin"
    override fun isEnabledByDefault(): Boolean = true
    override fun getShortName(): String = "CheckerContextParameter"
    override fun getStaticDescription(): String =
        "Detects functions that have parameters of type CheckerContext or DiagnosticReporter"

    private class SafeDeleteParameterQuickFix() : LocalQuickFix {
        override fun getName(): String = "Convert to context parameters"
        override fun getFamilyName(): String = name
        override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor): IntentionPreviewInfo {
            return IntentionPreviewInfo.EMPTY
        }

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val function = descriptor.psiElement as? KtNamedFunction ?: return
            WriteAction.runAndWait<Throwable> {
                val psiFactory = KtPsiFactory(project)
                val contextReceiverList = function.contextReceiverList
                val newContextReceiverList = StringBuilder(contextReceiverList?.text ?: "context()")
                contextReceiverList?.delete()
                val names = mutableSetOf<String>()

                for (parameter in function.valueParameters) {
                    val parameterName = parameter.name ?: continue
                    val parameterType = parameter.typeReference?.text ?: continue
                    if (!isRelevantType(parameterType)) continue

                    newContextReceiverList.insert(newContextReceiverList.length - 1, ", $parameterName: $parameterType")
                    val tmpName = "temp${Random.nextInt().absoluteValue}"
                    parameter.setName(tmpName)
                    names.add(tmpName)
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
                    newFunction.valueParameters.filter { it.name in names }.toTypedArray(),
                    false,
                    false,
                    false
                )

                DumbService.getInstance(project).smartInvokeLater(processor)
            }
        }
    }
}

private fun isRelevantType(parameterType: String): Boolean =
    parameterType == "CheckerContext" || parameterType == "DiagnosticReporter"
