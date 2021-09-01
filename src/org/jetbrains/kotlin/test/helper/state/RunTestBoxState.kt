package org.jetbrains.kotlin.test.helper.state

import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.util.parentsOfType
import com.intellij.testIntegration.TestRunLineMarkerProvider
import java.util.*
import javax.swing.Icon

class RunTestBoxState(private val project: Project) {
    val methodsClassNames: MutableList<String> = mutableListOf()
    val debugAndRunActionLists: MutableList<List<AnAction>> = mutableListOf()
    var currentChosenGroup: Int = 0
    val currentGroup = DefaultActionGroup()

    fun initialize(file: VirtualFile?) {
        if (file == null) {
            // TODO: log
            return
        }
        val name = file.nameWithoutExtension
        val truePath = file.path
        val path = project.basePath
        val testMethods = collectMethods(name, path!!, truePath)
        val ex = TestRunLineMarkerProvider()
        for (testMethod in testMethods) {
            val identifier = testMethod.nameIdentifier ?: continue
            val info = ex.getInfo(identifier) ?: continue
            val allActions = info.actions
            if (allActions.size < 2) {
                // TODO: log
                continue
            }
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
                }
            }

            val runTestAction = DelegatingAction(group[0], AllIcons.Actions.Execute)
            val debugTestAction: AnAction = DelegatingAction(group[1], AllIcons.Actions.StartDebugger)

            debugAndRunActionLists.add(listOf(runTestAction, debugTestAction))

            val topLevelClass = testMethod.parentsOfType<PsiClass>().last()
            methodsClassNames.add(topLevelClass.name!!)

            changeDebugAndRun(debugAndRunActionLists[currentChosenGroup])
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun collectMethods(baseName: String, path: String, truePath: String): List<PsiMethod> {
        val cache = PsiShortNamesCache.getInstance(project)

        val targetMethodName = "test${baseName.replaceFirstChar { it.toUpperCase() }}"
        val methods = cache.getMethodsByName(targetMethodName, GlobalSearchScope.allScope(project))
            .filter { it.hasAnnotation("org.jetbrains.kotlin.test.TestMetadata") }

        val foundMethods: MutableList<PsiMethod> = ArrayList()
        for (method in methods) {
            val classPath = method.containingClass?.extractTestMetadataValue() ?: continue
            val methodPathPart = method.extractTestMetadataValue() ?: continue
            val methodPath = "$path/$classPath/$methodPathPart"
            println("$methodPath | $truePath")
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


    fun changeDebugAndRun(item: List<AnAction>) {
        if (item !in debugAndRunActionLists) {
            // TODO: log
            return
        }
        currentChosenGroup = debugAndRunActionLists.indexOf(item)
        currentGroup.removeAll()
        currentGroup.addAll(debugAndRunActionLists[currentChosenGroup])
    }
}
