package org.jetbrains.kotlin.test.helper.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.parentsOfType
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.kotlin.test.helper.TestDataPathsConfiguration
import org.jetbrains.kotlin.test.helper.buildRunnerLabel
import org.jetbrains.kotlin.test.helper.isTestDataFile
import org.jetbrains.kotlin.test.helper.runGradleCommandLine
import org.jetbrains.kotlin.test.helper.services.TestDataRunnerService
import java.util.concurrent.Callable
import javax.swing.ListSelectionModel

abstract class RunSelectedFilesActionBase : AnAction() {
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
        project.service<TestDataRunnerService>()
            .collectAndRunAllTests(e, e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList())
    }
}

class RunSelectedFilesSpecificTestsAction : RunSelectedFilesActionBase() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        project.service<TestDataRunnerService>()
            .collectAndRunSpecificTests(e, e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList())
    }
}
