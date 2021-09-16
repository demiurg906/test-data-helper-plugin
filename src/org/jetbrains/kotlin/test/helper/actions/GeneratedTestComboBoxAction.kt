package org.jetbrains.kotlin.test.helper.actions

import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.icons.AllIcons
import com.intellij.ide.ui.laf.darcula.DarculaUIUtil
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.util.parentsOfType
import com.intellij.testIntegration.TestRunLineMarkerProvider
import com.intellij.ui.components.JBLabel
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.kotlin.utils.addToStdlib.firstNotNullResult
import java.awt.Component
import java.util.concurrent.Callable
import javax.swing.*

class GeneratedTestComboBoxAction(val baseEditor: TextEditor) : ComboBoxAction() {
    companion object {
        private val logger = Logger.getInstance(GeneratedTestComboBoxAction::class.java)
    }

    private lateinit var box: ComboBox<List<AnAction>>
    private lateinit var boxModel: DefaultComboBoxModel<List<AnAction>>

    val state: State = State().also { it.updateTestsList() }

    override fun createPopupActionGroup(button: JComponent): DefaultActionGroup {
        return DefaultActionGroup()
    }

    override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
        boxModel = DefaultComboBoxModel(state.debugAndRunActionLists.toTypedArray())
        box = ComboBox(boxModel).apply {
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

        return JPanel().apply {
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
        boxModel.removeAllElements()
        boxModel.addAll(state.debugAndRunActionLists)
        box.item = chosenActionsList
        box.updateWidth()
    }

    inner class State {
        val project: Project = baseEditor.editor.project!!
        val methodsClassNames: MutableList<String> = mutableListOf()
        val debugAndRunActionLists: MutableList<List<AnAction>> = mutableListOf()
        var currentChosenGroup: Int = 0
        val currentGroup = DefaultActionGroup()
        var topLevelDirectory: String? = null

        fun updateTestsList() {
            logger.info("task scheduled")
            ReadAction
                .nonBlocking(Callable { createActionsForTestRunners().also { logger.info("actions created") } })
                .inSmartMode(project)
                .finishOnUiThread(ModalityState.defaultModalityState(), this@State::updateUiAccordingCollectedTests)
                .submit(AppExecutorUtil.getAppExecutorService())
        }

        private fun createActionsForTestRunners(): List<Pair<String, List<AnAction>>> {
            val file = baseEditor.file ?: return emptyList() // TODO: log
            val name = file.nameWithoutExtension
            val truePath = file.path
            val path = project.basePath
            logger.info("task started")
            val testMethods = collectMethods(name, path!!, truePath)
            logger.info("methods collected")

            topLevelDirectory = testMethods.firstNotNullResult { method ->
                method.parentsOfType(PsiClass::class.java)
                    .toList()
                    .asReversed()
                    .firstNotNullResult { it.extractTestMetadataValue() }
            }

            val ex = TestRunLineMarkerProvider()
            debugAndRunActionLists.clear()
            methodsClassNames.clear()
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
                    object : AnAction() {
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

                        override fun toString(): String {
                            return "Run/Debug ${topLevelClass.name}"
                        }
                    }
                }

                class DelegatingAction(val delegate: AnAction, val icon: Icon) : AnAction() {
                    override fun actionPerformed(e: AnActionEvent) {
                        delegate.actionPerformed(e)
                    }

                    override fun update(e: AnActionEvent) {
                        delegate.update(e)
                        e.presentation.icon = icon
                        e.presentation.description = topLevelClass.name!!
                    }
                }

                val runTestAction = DelegatingAction(group[0], AllIcons.Actions.Execute)
                val debugTestAction: AnAction = DelegatingAction(group[1], AllIcons.Actions.StartDebugger)

                topLevelClass.name!! to listOf(runTestAction, debugTestAction)
            }
        }

        private fun updateUiAccordingCollectedTests(classAndActions: List<Pair<String, List<AnAction>>>) {
            logger.info("ui update started")
            for ((className, actions) in classAndActions) {
                debugAndRunActionLists.add(actions)
                methodsClassNames.add(className)
            }
            val lastUsedRunner = LastUsedTestService.getInstance(project)?.getLastUsedRunnerForFile(baseEditor.file!!)
            methodsClassNames.indexOf(lastUsedRunner).takeIf { it in methodsClassNames.indices }?.let {
                currentChosenGroup = it
            }

            val chosenActionsList = debugAndRunActionLists.getOrNull(currentChosenGroup)
            changeDebugAndRun(chosenActionsList)
            updateBox(chosenActionsList)
            logger.info("ui update finished")
        }

        @OptIn(ExperimentalStdlibApi::class)
        private fun collectMethods(baseName: String, path: String, truePath: String): List<PsiMethod> {
            val cache = PsiShortNamesCache.getInstance(project)

            val targetMethodName = "test${baseName.replaceFirstChar { it.toUpperCase() }.replace(".", "_")}"
            val methods = cache.getMethodsByName(targetMethodName, GlobalSearchScope.allScope(project))
                .filter { it.hasAnnotation("org.jetbrains.kotlin.test.TestMetadata") }

            val foundMethods: MutableList<PsiMethod> = ArrayList()
            for (method in methods) {
                val classPath = method.containingClass?.extractTestMetadataValue() ?: continue
                val methodPathPart = method.extractTestMetadataValue() ?: continue
                val methodPath = "$path/$classPath/$methodPathPart"
                if (methodPath == truePath) {
                    foundMethods.add(method)
                }
            }
            return foundMethods
        }

        private fun PsiModifierListOwner?.extractTestMetadataValue(): String? {
            return this?.getAnnotation("org.jetbrains.kotlin.test.TestMetadata")
                ?.parameterList
                ?.attributes
                ?.get(0)
                ?.literalValue
        }


        fun changeDebugAndRun(item: List<AnAction>?) {
            currentGroup.removeAll()
            if (item !in debugAndRunActionLists) {
                logger.info("Actions not in list: $item")
                return
            }
            currentChosenGroup = debugAndRunActionLists.indexOf(item)
            currentGroup.addAll(debugAndRunActionLists[currentChosenGroup])
            LastUsedTestService.getInstance(project)?.updateChosenRunner(topLevelDirectory, methodsClassNames[currentChosenGroup])
        }
    }
}
