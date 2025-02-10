package org.jetbrains.kotlin.test.helper.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiClass
import com.intellij.psi.util.parentsOfType
import org.jetbrains.kotlin.test.helper.TestDataPathsConfiguration
import org.jetbrains.kotlin.test.helper.buildRunnerLabel
import org.jetbrains.kotlin.test.helper.isTestDataFile
import org.jetbrains.kotlin.test.helper.runGradleCommandLine

abstract class RunSelectedFilesActionBase : AnAction(), DumbAware {
    override fun update(e: AnActionEvent) {
        val project = e.project ?: return
        val selectedFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) ?: emptyArray()
        e.presentation.isEnabledAndVisible = selectedFiles.any { it.isTestDataFile(project) }
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }
}

class RunSelectedFilesTestsAction : RunSelectedFilesActionBase() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selectedFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.filter { it.isTestDataFile(project) } ?: return
        val testMethods = selectedFiles.flatMap { it.collectTestMethods(project) }
        val commandLine = computeGradleCommandLine(testMethods)
        runGradleCommandLine(e, commandLine)
    }
}

class RunSelectedFilesSpecificTestsAction : RunSelectedFilesActionBase() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selectedFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.filter { it.isTestDataFile(project) } ?: return
        val testMethods = selectedFiles.flatMap { it.collectTestMethods(project) }
        val byClass = testMethods.groupBy { it.parentsOfType<PsiClass>().last() }

        val testTags = TestDataPathsConfiguration.getInstance(project).testTags

        val popup = JBPopupFactory.getInstance()
            .createPopupChooserBuilder(byClass.keys.map { it.buildRunnerLabel(testTags) }.sortedBy { it })
            .setTitle("Select Test Class")
            .setItemChosenCallback { selected ->
                val commandLine = computeGradleCommandLine(byClass.entries.first { it.key.name == selected }.value)
                runGradleCommandLine(e, commandLine)
            }
            .createPopup()

        popup.showInBestPositionFor(e.dataContext)
    }
}
