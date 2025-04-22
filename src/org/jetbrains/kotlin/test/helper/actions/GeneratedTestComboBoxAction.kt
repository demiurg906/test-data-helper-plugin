package org.jetbrains.kotlin.test.helper.actions

import com.intellij.codeInsight.navigation.PsiTargetNavigator
import com.intellij.designer.actions.AbstractComboBoxAction
import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsAdapter
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.externalSystem.task.TaskCallback
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.externalSystem.util.task.TaskExecutionSpec
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.util.parentsOfType
import com.intellij.testIntegration.TestRunLineMarkerProvider
import com.intellij.ui.components.JBLabel
import com.intellij.util.application
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.kotlin.test.helper.TestDataPathsConfiguration
import org.jetbrains.kotlin.test.helper.buildRunnerLabel
import org.jetbrains.kotlin.test.helper.createGradleExternalSystemTaskExecutionSettings
import org.jetbrains.kotlin.test.helper.runGradleCommandLine
import org.jetbrains.kotlin.test.helper.services.TestDataRunnerService
import org.jetbrains.kotlin.test.helper.ui.WidthAdjustingPanel
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.nio.file.Paths
import java.util.concurrent.Callable
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel

class GeneratedTestComboBoxAction(val baseEditor: TextEditor) : AbstractComboBoxAction<String>(), DumbAware {
    companion object {
        private val logger = Logger.getInstance(GeneratedTestComboBoxAction::class.java)
        const val INDEX_RUN = 0
        const val INDEX_DEBUG = 1
    }

    private val configuration = TestDataPathsConfiguration.getInstance(baseEditor.editor.project!!)
    private val testTags: Map<String, Array<String>>
        get() = configuration.testTags

    val runAllTestsAction: RunAllTestsAction = RunAllTestsAction()
    val goToAction: GoToDeclaration = GoToDeclaration()
    val runAction = RunAction(INDEX_RUN, "Run", AllIcons.Actions.Execute)
    val debugAction = RunAction(INDEX_DEBUG, "Debug", AllIcons.Actions.StartDebugger)
    val moreActionsGroup = MoreActionsGroup()

    val state: State = State()

    init {
        state.updateTestsList()
    }

    override fun update(item: String?, presentation: Presentation, popup: Boolean) {
        presentation.text = item
    }

    override fun selectionChanged(item: String?): Boolean {
        state.currentChosenGroup = state.methodsClassNames.indexOf(item)
        state.onSelectionUpdated()
        return true
    }

    override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
        return WidthAdjustingPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(JBLabel("Tests: "))
            val panel = super.createCustomComponent(presentation, place) as JPanel
            add(panel)
        }
    }

    private fun updateBox() {
        setItems(state.methodsClassNames, state.methodsClassNames.elementAtOrNull(state.currentChosenGroup))
    }

    inner class State {
        val project: Project = baseEditor.editor.project!!
        var methodsClassNames: List<String> = emptyList()
        var debugAndRunActionLists: List<List<AnAction>> = emptyList()
        var currentChosenGroup: Int = 0
        var topLevelDirectory: String? = null

        fun updateTestsList() {
            logger.info("task scheduled")
            methodsClassNames = emptyList()
            debugAndRunActionLists = emptyList()
            ReadAction
                .nonBlocking(Callable { createActionsForTestRunners().also { logger.info("actions created") } })
                .inSmartMode(project)
                .finishOnUiThread(ModalityState.defaultModalityState(), this@State::updateUiAccordingCollectedTests)
                .submit(AppExecutorUtil.getAppExecutorService())
        }

        private fun createActionsForTestRunners(): List<Pair<String, List<AnAction>>> {
            val file = baseEditor.file ?: return emptyList() // TODO: log
            logger.info("task started")

            val testDeclarations = file.collectTestMethodsIfTestData(project)
            goToAction.testMethods = testDeclarations
            logger.info("methods collected")

            topLevelDirectory = testDeclarations.firstNotNullOfOrNull { method ->
                method.parentsOfType(PsiClass::class.java)
                    .toList()
                    .asReversed()
                    .firstNotNullOfOrNull { it.extractTestMetadataValue() }
            }

            val ex = TestRunLineMarkerProvider()
            return testDeclarations.mapNotNull { testMethod ->
                val identifier = testMethod.nameIdentifier ?: return@mapNotNull null
                val info = ex.getInfo(identifier) ?: return@mapNotNull null
                val allActions = info.actions
                if (allActions.size < 2) {
                    logger.info("Not enough actions: ${allActions.size}")
                    return@mapNotNull null
                }
                val topLevelClass = testMethod.parentsOfType<PsiClass>().last()

                val group: List<AnAction> = allActions.take(2).map {
                    object : AnAction(), DumbAware {
                        override fun actionPerformed(e: AnActionEvent) {
                            val dataContext = SimpleDataContext.builder().apply {
                                val newLocation = PsiLocation.fromPsiElement(identifier)
                                setParent(e.dataContext)
                                add(Location.DATA_KEY, newLocation)
                            }.build()

                            val newEvent = AnActionEvent(
                                e.inputEvent,
                                dataContext,
                                e.place,
                                e.presentation,
                                e.actionManager,
                                e.modifiers
                            )
                            it.actionPerformed(newEvent)
                        }

                        override fun update(e: AnActionEvent) {
                            it.update(e)
                            e.presentation.isEnabledAndVisible = true
                            e.presentation.description = topLevelClass.name!!
                        }

                        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

                        override fun toString(): String {
                            return "Run/Debug ${topLevelClass.name}"
                        }
                    }
                }

                val runnerLabel = topLevelClass.buildRunnerLabel(testTags)
                runnerLabel to group
            }.sortedBy { it.first }
        }

        internal fun executeRunConfigAction(e: AnActionEvent, index: Int) {
            debugAndRunActionLists.elementAtOrNull(currentChosenGroup)?.elementAtOrNull(index)?.actionPerformed(e)
        }

        private fun updateUiAccordingCollectedTests(classAndActions: List<Pair<String, List<AnAction>>>) {
            logger.info("ui update started")
            debugAndRunActionLists = classAndActions.map { it.second }
            methodsClassNames = classAndActions.map { it.first }
            val lastUsedRunner = LastUsedTestService.getInstance(project).getLastUsedRunnerForFile(baseEditor.file!!)
            methodsClassNames.indexOf(lastUsedRunner).takeIf { it in methodsClassNames.indices }?.let {
                currentChosenGroup = it
            }

            onSelectionUpdated()
            updateBox()
            logger.info("ui update finished")
        }

        fun onSelectionUpdated() {
            val runnerName = methodsClassNames.elementAtOrNull(currentChosenGroup) ?: return
            LastUsedTestService.getInstance(project).updateChosenRunner(topLevelDirectory, runnerName)
        }
    }

    inner class RunAction(private val index: Int, text: String, icon: Icon) : AnAction(text, text, icon), DumbAware {
        override fun actionPerformed(e: AnActionEvent) {
            state.executeRunConfigAction(e, index)
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = state.currentChosenGroup in state.debugAndRunActionLists.indices
            super.update(e)
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    }

    inner class GoToDeclaration : AnAction(
        "Go To Test Method",
        "Go to test method declaration",
        AllIcons.Nodes.Method
    ), DumbAware {
        var testMethods: List<PsiNameIdentifierOwner> = emptyList()

        override fun actionPerformed(e: AnActionEvent) {
            PsiTargetNavigator { testMethods }.navigate(baseEditor.editor, "")
        }

    }

    inner class RunAllTestsAction : AnAction(
        "Run All...",
        "Run all tests via gradle",
        AllIcons.RunConfigurations.Junit
    ), DumbAware {
        override fun actionPerformed(e: AnActionEvent) {
            runAllTests(e, delay = false)
        }
    }

    inner class MoreActionsGroup : ActionGroup(
        "More",
        "More actions",
        AllIcons.Actions.More,
    ), DumbAware {
        override fun update(e: AnActionEvent) {
            e.presentation.isPopupGroup = true
        }

        override fun getActionUpdateThread(): ActionUpdateThread {
            return ActionUpdateThread.BGT
        }

        private val actions = arrayOf<AnAction>(
            object : AnAction("Reload Tests"), DumbAware {
                override fun actionPerformed(e: AnActionEvent) {
                    state.updateTestsList()
                }
            },
            object : AnAction("Generate Tests"), DumbAware {
                override fun actionPerformed(e: AnActionEvent) {
                    val project = e.project ?: return
                    val (commandLine, title) = generateTestsCommandLine(project)
                    runGradleCommandLine(e, commandLine, false, title)
                }
            },
            object : AnAction("Run Selected && Apply Diffs"), DumbAware {
                override fun actionPerformed(e: AnActionEvent) {
                    state.executeRunConfigAction(e, INDEX_RUN)
                    awaitTestRunAndApplyDiffs(e.project ?: return)
                }
            },
            object : AnAction("Run All && Apply Diffs"), DumbAware {
                override fun actionPerformed(e: AnActionEvent) {
                    runAllAndApplyDiff(e, delay = false)
                }
            },
            object : AnAction("Generate Tests, Run All && Apply Diffs"), DumbAware {
                override fun actionPerformed(e: AnActionEvent) {
                    val project = e.project ?: return
                    val (commandLine, _) = generateTestsCommandLine(project)
                    ExternalSystemUtil.runTask(
                        TaskExecutionSpec.create(
                            project = project,
                            systemId = GradleConstants.SYSTEM_ID,
                            executorId = DefaultRunExecutor.getRunExecutorInstance().id,
                            settings = createGradleExternalSystemTaskExecutionSettings(project,
                                commandLine
                            )
                        )
                            .withActivateToolWindowBeforeRun(true)
                            .withCallback(object : TaskCallback {
                                override fun onSuccess() {
                                    runAllAndApplyDiff(e, delay = true)
                                }

                                override fun onFailure() {
                                }
                            }).build()
                    )
                }
            },
        )

        private fun generateTestsCommandLine(project: Project): Pair<String, String> {
            val basePath = project.basePath
            return if (basePath != null &&
                (isAncestor(basePath, "compiler", "testData", "diagnostics") ||
                        isAncestor(basePath, "compiler", "fir", "analysis-tests", "testData"))
            ) {
                "generateFrontendApiTests compiler:tests-for-compiler-generator:generateTests" to "Generate Diagnostic Tests"
            } else {
                "generateTests" to "Generate Tests"
            }
        }

        private fun isAncestor(basePath: String, vararg strings: String): Boolean {
            val file = VfsUtil.findFile(Paths.get(basePath, *strings), false) ?: return false
            return VfsUtil.isAncestor(file, baseEditor.file, false)
        }

        private fun runAllAndApplyDiff(e: AnActionEvent, delay: Boolean) {
            val project = e.project ?: return

            runAllTests(e, delay)
            awaitTestRunAndApplyDiffs(project)
        }

        override fun getChildren(e: AnActionEvent?): Array<AnAction> {
            return actions
        }
    }

    private fun runAllTests(e: AnActionEvent, delay: Boolean) {
        e.project?.service<TestDataRunnerService>()?.collectAndRunAllTests(e, listOf(baseEditor.file), debug = false, delay = delay)
    }

    private fun awaitTestRunAndApplyDiffs(project: Project) {
        val connection = project.messageBus.connect(baseEditor)
        connection
            .subscribe(SMTRunnerEventsListener.TEST_STATUS, object : SMTRunnerEventsAdapter() {
                override fun onTestingFinished(testsRoot: SMTestProxy.SMRootTestProxy) {
                    connection.disconnect()
                    application.invokeLater { applyDiffs(arrayOf(testsRoot)) }
                }
            })
    }
}

