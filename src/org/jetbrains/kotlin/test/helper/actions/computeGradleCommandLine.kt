package org.jetbrains.kotlin.test.helper.actions

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.util.parentOfType
import org.jetbrains.plugins.gradle.execution.test.runner.GradleTestRunConfigurationProducer
import org.jetbrains.plugins.gradle.util.createTestFilterFrom
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.text.substringAfterLast
import kotlin.text.substringBeforeLast

fun computeGradleCommandLine(testDeclarations: List<PsiNameIdentifierOwner>): String = buildString {
    testDeclarations
        .flatMap { psi ->
            val parentClass = psi as? PsiClass ?: psi.parentOfType<PsiClass>() ?: return@flatMap emptyList()
            val taskArguments = createTestFilterFrom(parentClass, (psi as? PsiMethod)?.name)
            val virtualFile = psi.containingFile?.virtualFile ?: return@flatMap emptyList()
            val allTasks = GradleTestRunConfigurationProducer.findAllTestsTaskToRun(virtualFile, psi.project)
                .flatMap { it.tasks }
            allTasks
                .map {
                    val group = it.substringBeforeLast(":")
                    val name = it.substringAfterLast(":")
                    group to name
                }
                .groupBy(keySelector = { it.first }, valueTransform = { it.second })
                .map { (group, names) -> Triple(group, names.minByOrNull { it.length }!!, taskArguments) }
        }
        .groupBy({ (group, name) -> group to name }) { it.third }
        .forEach { (group, name), taskArguments ->
            append("$group:cleanTest ")
            append("$group:$name ")
            for (taskArgument in taskArguments) {
                append(taskArgument)
                append(' ')
            }
        }

    append(" --continue")
}
