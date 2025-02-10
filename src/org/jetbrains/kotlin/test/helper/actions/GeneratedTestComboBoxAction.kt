package org.jetbrains.kotlin.test.helper.actions

import com.intellij.codeInsight.navigation.PsiTargetNavigator
import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.icons.AllIcons
import com.intellij.ide.actions.runAnything.RunAnythingAction
import com.intellij.ide.ui.laf.darcula.DarculaUIUtil
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.parentsOfType
import com.intellij.testIntegration.TestRunLineMarkerProvider
import com.intellij.ui.components.JBLabel
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.kotlin.test.helper.TestDataPathsConfiguration
import org.jetbrains.kotlin.test.helper.runGradleCommandLine
import org.jetbrains.kotlin.test.helper.ui.WidthAdjustingPanel
import org.jetbrains.plugins.gradle.action.GradleExecuteTaskAction
import java.awt.Component
import java.util.concurrent.Callable
import javax.swing.*

class GeneratedTestComboBoxAction(val baseEditor: TextEditor) : ComboBoxAction(), DumbAware {
    companion object {
        private val logger = Logger.getInstance(GeneratedTestComboBoxAction::class.java)
    }

    private var box: ComboBox<List<AnAction>>? = null
    private var boxModel: DefaultComboBoxModel<List<AnAction>>? = null
    
    private val configuration = TestDataPathsConfiguration.getInstance(baseEditor.editor.project!!)
    private val testTags: Map<String, Array<String>>
        get() = configuration.testTags
    
    val runAllTestsAction: RunAllTestsAction = RunAllTestsAction()
    val goToAction: GoToDeclaration = GoToDeclaration()
    val runAction = RunAction(0, "Run", AllIcons.Actions.Execute)
    val debugAction = RunAction(1, "Debug", AllIcons.Actions.StartDebugger)

    val state: State = State().also { it.updateTestsList() }

    override fun createPopupActionGroup(button: JComponent, dataContext: DataContext): DefaultActionGroup {
        return DefaultActionGroup()
    }

    override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
        val boxModel = DefaultComboBoxModel(state.debugAndRunActionLists.toTypedArray())
        this.boxModel = boxModel
        box = ComboBox(boxModel).apply {
            isUsePreferredSizeAsMinimum = false
            addActionListener {
                item?.let { state.changeDebugAndRun(it) }
            }
            renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean
                ): Component {
                    val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    if (value != null) {
                        val order = state.debugAndRunActionLists.indexOf(value)
                        if (order in state.methodsClassNames.indices) {
                            text = state.methodsClassNames[order]
                        }
                    }
                    return component
                }
            }
            putClientProperty(DarculaUIUtil.COMPACT_PROPERTY, true)
            updateWidth()
        }

        val label = JBLabel("Tests: ")

        return WidthAdjustingPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(label)
            add(box)
        }
    }

    private fun ComboBox<List<AnAction>>.updateWidth() {
        val maxTestName = state.methodsClassNames.maxByOrNull { it.length } ?: ""
        setMinimumAndPreferredWidth(getFontMetrics(font).stringWidth(maxTestName) + 80)
    }

    private fun updateBox(chosenActionsList: List<AnAction>?) {
        val boxModel = this.boxModel ?: return
        val box = this.box ?: return
        boxModel.removeAllElements()
        boxModel.addAll(state.debugAndRunActionLists)
        box.item = chosenActionsList
        box.updateWidth()
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
            updateBox(emptyList())
            ReadAction
                .nonBlocking(Callable { createActionsForTestRunners().also { logger.info("actions created") } })
                .inSmartMode(project)
                .finishOnUiThread(ModalityState.defaultModalityState(), this@State::updateUiAccordingCollectedTests)
                .submit(AppExecutorUtil.getAppExecutorService())
        }

        private fun createActionsForTestRunners(): List<Pair<String, List<AnAction>>> {
            val file = baseEditor.file ?: return emptyList() // TODO: log
            logger.info("task started")

            val testMethods = file.collectTestMethods(project)
            runAllTestsAction.computeTasksToRun(testMethods)
            goToAction.testMethods = testMethods
            logger.info("methods collected")

            topLevelDirectory = testMethods.firstNotNullOfOrNull { method ->
                method.parentsOfType(PsiClass::class.java)
                    .toList()
                    .asReversed()
                    .firstNotNullOfOrNull { it.extractTestMetadataValue() }
            }

            val ex = TestRunLineMarkerProvider()
            return testMethods.mapNotNull { testMethod ->
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

                val runnerName = topLevelClass.name!!
                val tags = testTags.firstNotNullOfOrNull { (pattern, tags) ->
                    val patternRegex = pattern.toRegex()
                    if(patternRegex.matches(runnerName)) {
                        tags.toList()
                    } else null
                }.orEmpty()
                val runnerLabel = buildString {
                    if (tags.isNotEmpty()) {
                        append(tags.joinToString(prefix = "[", postfix = "] ", separator = ", "))
                    }
                    append(runnerName)
                }
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
            val lastUsedRunner = LastUsedTestService.getInstance(project)?.getLastUsedRunnerForFile(baseEditor.file!!)
            methodsClassNames.indexOf(lastUsedRunner).takeIf { it in methodsClassNames.indices }?.let {
                currentChosenGroup = it
            }

            val chosenActionsList = debugAndRunActionLists.getOrNull(currentChosenGroup)
            changeDebugAndRun(chosenActionsList)
            updateBox(chosenActionsList)
            logger.info("ui update finished")
        }


        fun changeDebugAndRun(item: List<AnAction>?) {
            if (item !in debugAndRunActionLists) {
                logger.info("Actions not in list: $item")
                return
            }
            currentChosenGroup = debugAndRunActionLists.indexOf(item)
            LastUsedTestService.getInstance(project)?.updateChosenRunner(topLevelDirectory, methodsClassNames[currentChosenGroup])
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

    inner class GoToDeclaration: AnAction(
    "Go To Test Method",
    "Go to test method declaration",
    AllIcons.Nodes.Method
    ), DumbAware {
        var testMethods: List<PsiMethod> = emptyList()

        override fun actionPerformed(e: AnActionEvent) {
            PsiTargetNavigator { testMethods }.navigate(baseEditor.editor, "")
        }

    }

    inner class RunAllTestsAction : AnAction(
        "Run All...",
        "Run all tests via gradle",
        AllIcons.RunConfigurations.Junit
    ), DumbAware {
        private var commandLine = ""

        fun computeTasksToRun(testMethods: List<PsiMethod>) {
            commandLine = computeGradleCommandLine(testMethods)
        }

        override fun actionPerformed(e: AnActionEvent) {
            runGradleCommandLine(e, commandLine)
        }
    }
}
