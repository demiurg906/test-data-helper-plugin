package org.jetbrains.kotlin.test.helper.actions

import com.intellij.codeInsight.daemon.impl.PsiElementListNavigator
import org.jetbrains.kotlin.test.helper.ui.WidthAdjustingPanel
import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.icons.AllIcons
import com.intellij.ide.actions.runAnything.RunAnythingAction
import com.intellij.ide.ui.laf.darcula.DarculaUIUtil
import com.intellij.ide.util.DefaultPsiElementCellRenderer
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.util.parentOfType
import com.intellij.psi.util.parentsOfType
import com.intellij.testIntegration.TestRunLineMarkerProvider
import com.intellij.ui.components.JBLabel
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.plugins.gradle.action.GradleExecuteTaskAction
import org.jetbrains.plugins.gradle.execution.test.runner.GradleTestRunConfigurationProducer
import org.jetbrains.plugins.gradle.util.createTestFilterFrom
import java.awt.Component
import java.io.File
import java.util.*
import java.util.concurrent.Callable
import javax.swing.*

class GeneratedTestComboBoxAction(val baseEditor: TextEditor) : ComboBoxAction() {
    companion object {
        private val logger = Logger.getInstance(GeneratedTestComboBoxAction::class.java)
    }

    private var box: ComboBox<List<AnAction>>? = null
    private var boxModel: DefaultComboBoxModel<List<AnAction>>? = null
    val runAllTestsAction: RunAllTestsAction = RunAllTestsAction()
    val goToAction: GoToDeclaration = GoToDeclaration()

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

        private fun String.indexOfOrLength(symbol: Char, startIndex: Int): Int {
            val index = indexOf(symbol, startIndex)
            return if (index > -1) index else length
        }

        private val String.asPathWithoutAllExtensions: String
            get() {
                val indexOfSeparator = lastIndexOf(File.separator)
                val indexOfFirstExtensionDot = indexOfOrLength('.', indexOfSeparator + 1)
                return substring(0, indexOfFirstExtensionDot)
            }

        private val VirtualFile.nameWithoutAllExtensions get() = name.asPathWithoutAllExtensions

        private fun createActionsForTestRunners(): List<Pair<String, List<AnAction>>> {
            val file = baseEditor.file ?: return emptyList() // TODO: log
            val name = file.nameWithoutAllExtensions
            val truePath = file.path
            val path = project.basePath
            logger.info("task started")
            val testMethods = collectMethods(name, path!!, File(truePath).absolutePath)
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

                        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

                        override fun toString(): String {
                            return "Run/Debug ${topLevelClass.name}"
                        }
                    }
                }

                class DelegatingAction(val delegate: AnAction, icon: Icon, text: String) : AnAction(text, topLevelClass.name, icon) {
                    override fun actionPerformed(e: AnActionEvent) {
                        delegate.actionPerformed(e)
                    }

                    override fun update(e: AnActionEvent) {
                        delegate.update(e)
                    }

                    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
                }

                val runTestAction = DelegatingAction(group[0], AllIcons.Actions.Execute, "Run")
                val debugTestAction = DelegatingAction(group[1], AllIcons.Actions.StartDebugger, "Debug")

                topLevelClass.name!! to listOf(runTestAction, debugTestAction)
            }.sortedBy { it.first }
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

        private fun collectMethods(baseName: String, path: String, truePath: String): List<PsiMethod> {
            val cache = PsiShortNamesCache.getInstance(project)

            val targetMethodName = "test${baseName.replaceFirstChar { it.uppercaseChar() }.replace(".", "_")}"
            val methods = cache.getMethodsByName(targetMethodName, GlobalSearchScope.allScope(project))
                .filter { it.hasAnnotation("org.jetbrains.kotlin.test.TestMetadata") }

            val truePathWithoutAllExtensions = truePath.asPathWithoutAllExtensions
            val foundMethods: MutableList<PsiMethod> = ArrayList()
            for (method in methods) {
                val psiClass = method.containingClass

                var currentPsiClass = psiClass
                var testMetaData: String? = null
                var testDataPath: String? = null
                while (currentPsiClass != null) {
                    testMetaData = testMetaData
                        ?: if (currentPsiClass == psiClass) currentPsiClass.extractTestMetadataValue()
                         else null
                    val localTestDataPath: String? = currentPsiClass.extractTestDataPath()
                    testDataPath = localTestDataPath ?: testDataPath
                    val containingClass = currentPsiClass.containingClass
                    currentPsiClass = containingClass
                }

                val methodPathPart = method.extractTestMetadataValue() ?: continue

                val methodPathComponents = buildList {
                    add(methodPathPart)
                    testMetaData?.takeIf(String::isNotEmpty)?.let(::add)
                    testDataPath?.takeIf(String::isNotEmpty)?.let(::add)
                    if (testDataPath == null) add(path)
                }
                val methodPath = File(methodPathComponents.reversed().joinToString("/"))
                    .canonicalFile.absolutePath
                if (methodPath.asPathWithoutAllExtensions == truePathWithoutAllExtensions) {
                    foundMethods.add(method)
                }
            }
            return foundMethods
        }

        private fun PsiModifierListOwner?.extractTestMetadataValue(): String? {
            return annotationValue("org.jetbrains.kotlin.test.TestMetadata")
        }

        private fun PsiModifierListOwner?.extractTestDataPath(): String? {
            var path = annotationValue("com.intellij.testFramework.TestDataPath") ?: return null
            if (path.contains("\$CONTENT_ROOT")) {
                val fileIndex = ProjectRootManager.getInstance(project).fileIndex
                val file = this?.containingFile?.virtualFile ?: return null
                val contentRoot = fileIndex.getContentRootForFile(file) ?: return null
                path = path.replace("\$CONTENT_ROOT", contentRoot.path)
            }
            if (path.contains("\$PROJECT_ROOT")) {
                val baseDir = this?.project?.basePath ?: return null
                path = path.replace("\$PROJECT_ROOT", baseDir)
            }
            return path
        }

        private fun PsiModifierListOwner?.annotationValue(name: String): String? {
            return this?.getAnnotation(name)
                ?.parameterList
                ?.attributes
                ?.firstOrNull()
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

    inner class GoToDeclaration: AnAction(
    "Go To Test Method",
    "Go to test method declaration",
    AllIcons.Nodes.Method
    ) {

        var testMethods: List<PsiMethod> = emptyList()

        override fun actionPerformed(e: AnActionEvent) {
            PsiElementListNavigator.openTargets<NavigatablePsiElement>(
                baseEditor.editor, testMethods.toTypedArray(), "", "",
                DefaultPsiElementCellRenderer(), null
            )
        }

    }

    inner class RunAllTestsAction : AnAction(
        "Run All...",
        "Run all tests via gradle",
        AllIcons.RunConfigurations.Junit
    ) {
        private var commandLine = ""

        fun computeTasksToRun(testMethods: List<PsiMethod>) {
            commandLine = buildString {
                append("--continue ")

                testMethods
                        .flatMap { testMethod ->
                            val parentClass = testMethod.parentOfType<PsiClass>() ?: return@flatMap emptyList()
                            val taskArguments = createTestFilterFrom(parentClass, testMethod.name)
                            val virtualFile = testMethod.containingFile?.virtualFile ?: return@flatMap emptyList()
                            val allTasks = GradleTestRunConfigurationProducer.findAllTestsTaskToRun(virtualFile, testMethod.project).flatMap { it.tasks }
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
            }
        }

        override fun actionPerformed(e: AnActionEvent) {
            val project = e.project!!
            GradleExecuteTaskAction.runGradle(project, RunAnythingAction.EXECUTOR_KEY.getData(e.dataContext), project.basePath ?: "", commandLine)
        }
    }
}
