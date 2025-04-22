package org.jetbrains.kotlin.test.helper.services

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.util.progress.reportSequentialProgress
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.util.parentsOfType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.test.helper.TestDataPathsConfiguration
import org.jetbrains.kotlin.test.helper.actions.collectTestMethodsIfTestData
import org.jetbrains.kotlin.test.helper.actions.computeGradleCommandLine
import org.jetbrains.kotlin.test.helper.buildRunnerLabel
import org.jetbrains.kotlin.test.helper.runGradleCommandLine
import org.jetbrains.kotlin.test.helper.toFileNamesString
import javax.swing.ListSelectionModel
import kotlin.time.Duration.Companion.seconds

@Service(Service.Level.PROJECT)
class TestDataRunnerService(
    val project: Project,
    val scope: CoroutineScope
) {
    fun collectAndRunAllTests(e: AnActionEvent, files: List<VirtualFile>?, debug: Boolean, delay: Boolean = false) {
        if (files == null) return

        scope.launch(Dispatchers.Default) {
            if (delay) delay(1.seconds)

            val commandLine = withBackgroundProgress(project, title = "Running all tests") {
                reportSequentialProgress { reporter ->
                    reporter.indeterminateStep("Collecting tests")

                    smartReadAction(project) {
                        val testDeclarations = filterAndCollectTestDeclarations(files)
                        computeGradleCommandLine(testDeclarations)
                    }
                }
            }

            runGradleCommandLine(e, commandLine, debug)
        }
    }

    fun collectAndRunSpecificTests(e: AnActionEvent, files: List<VirtualFile>?, debug: Boolean) {
        if (files == null) return

        scope.launch(Dispatchers.Default) {
            val byClass = withBackgroundProgress(project, title = "Running specific tests") {
                reportSequentialProgress { reporter ->
                    reporter.indeterminateStep("Collecting tests")

                    smartReadAction(project) {
                        val testDeclarations = filterAndCollectTestDeclarations(files)
                        val testTags = TestDataPathsConfiguration.getInstance(project).testTags
                        testDeclarations
                            .groupBy { it.parentsOfType<PsiClass>().last() }
                            .mapKeys { it.key.buildRunnerLabel(testTags) }
                    }
                }
            }

            withContext(Dispatchers.EDT) {
                JBPopupFactory.getInstance()
                    .createPopupChooserBuilder(byClass.keys.sortedBy { it })
                    .setTitle("Select Test Class")
                    .setItemChosenCallback { selected ->
                        val testMethods = byClass[selected] ?: return@setItemChosenCallback

                        scope.launch(Dispatchers.Default) {
                            val commandLine = smartReadAction(project) {
                                computeGradleCommandLine(testMethods)
                            }

                            withContext(Dispatchers.EDT) {
                                runGradleCommandLine(e, commandLine, debug, title = e.toFileNamesString()?.let { "$selected: $it" })
                            }
                        }

                    }
                    .setNamerForFiltering { it }
                    .setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
                    .createPopup()
                    .showInBestPositionFor(e.dataContext)
            }
        }
    }

    fun filterAndCollectTestDeclarations(files: List<VirtualFile>?): List<PsiNameIdentifierOwner> {
        if (files == null) return emptyList()
        return files.flatMap { it.collectTestMethodsIfTestData(project) }
    }
}