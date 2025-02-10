package org.jetbrains.kotlin.test.helper.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiClass
import com.intellij.psi.util.parentsOfType
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.kotlin.test.helper.TestDataPathsConfiguration
import org.jetbrains.kotlin.test.helper.buildRunnerLabel
import org.jetbrains.kotlin.test.helper.isTestDataFile
import org.jetbrains.kotlin.test.helper.runGradleCommandLine
import java.util.concurrent.Callable
import javax.swing.ListSelectionModel

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

        ReadAction
            .nonBlocking(Callable {
                val selectedFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
                    ?.filter { it.isTestDataFile(project) }
                    ?: return@Callable emptyMap()

                val testTags = TestDataPathsConfiguration.getInstance(project).testTags
                selectedFiles
                    .flatMap { it.collectTestMethods(project) }
                    .groupBy { it.parentsOfType<PsiClass>().last() }
                    .mapKeys { it.key.buildRunnerLabel(testTags) }
            })
            .inSmartMode(project)
            .finishOnUiThread(ModalityState.defaultModalityState(), { byClass ->
                if (byClass.isEmpty()) return@finishOnUiThread

                JBPopupFactory.getInstance()
                    .createPopupChooserBuilder(byClass.keys.sortedBy { it })
                    .setTitle("Select Test Class")
                    .setItemChosenCallback { selected ->
                        val commandLine = computeGradleCommandLine(byClass[selected]!!)
                        runGradleCommandLine(e, commandLine)
                    }
                    .setNamerForFiltering { it }
                    .setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
                    .createPopup()
                    .showInBestPositionFor(e.dataContext)
            })
            .submit(AppExecutorUtil.getAppExecutorService())
    }
}
