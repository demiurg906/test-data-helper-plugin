package org.jetbrains.kotlin.test.helper

import com.intellij.codeHighlighting.BackgroundEditorHighlighter
import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.icons.AllIcons
import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.ui.laf.darcula.DarculaUIUtil
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Pair
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.pom.Navigatable
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.util.parentOfType
import com.intellij.psi.util.parentsOfType
import com.intellij.testIntegration.TestRunLineMarkerProvider
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.beans.PropertyChangeEvent
import java.beans.PropertyChangeListener
import java.util.*
import java.util.stream.Collectors
import javax.swing.*

class TestDataEditor(
    private val baseEditor: TextEditor,
    private val splitEditors: List<FileEditor>,
    private var currentPreview: Int,
    private val name: String = "TextEditorWithPreview",
    private val myDefaultLayout: Layout = Layout.SHOW_EDITOR_AND_PREVIEW
) : UserDataHolderBase(), TextEditor {
    private val listenersGenerator: ListenersMultimap = ListenersMultimap()

    var myLayout: Layout by lazyVar {
        val lastUsed = PropertiesComponent.getInstance().getValue(layoutPropertyName)
        Layout.fromName(lastUsed, myDefaultLayout)
    }

    private val myComponent: JComponent by lazy {
        adjustEditorsVisibility()
        JBUI.Panels.simplePanel(splitter).addToTop(myToolbarWrapper)
    }

    private val myToolbarWrapper: SplitEditorToolbar by lazy {
        createMarkdownToolbarWrapper(splitter)
    }
    private val splitter: JBSplitter by lazy {
        JBSplitter(false, 0.5f, 0.15f, 0.85f).apply {
            splitterProportionKey = splitterProportionKey
            firstComponent = baseEditor.component
            secondComponent = splitEditors[currentPreview].component
            dividerWidth = 3
        }
    }
    private val debugAndRun: MutableList<List<AnAction>> = ArrayList()
    private var currentChosenGroup = 0
    private val currentGroup = DefaultActionGroup()
    private val methodsClassNames: MutableList<String> = ArrayList()

    fun changeEditor(item: FileEditor) {
        currentPreview = splitEditors.indexOf(item)
        splitter.secondComponent = splitEditors[currentPreview].component
    }

    fun changeDebugAndRun(item: List<AnAction>) {
        currentChosenGroup = debugAndRun.indexOf(item)
        currentGroup.removeAll()
        val current = debugAndRun[currentChosenGroup]
        currentGroup.addAll(current)
    }

    override fun getBackgroundHighlighter(): BackgroundEditorHighlighter? {
        return baseEditor.backgroundHighlighter
    }

    override fun getCurrentLocation(): FileEditorLocation? {
        return baseEditor.currentLocation
    }

    override fun getStructureViewBuilder(): StructureViewBuilder? {
        return baseEditor.structureViewBuilder
    }

    override fun dispose() {
        Disposer.dispose(baseEditor)
        val fileEditor = splitEditors[currentPreview]
        fileEditor.dispose()
    }

    override fun selectNotify() {
        baseEditor.selectNotify()
        val fileEditor = splitEditors[currentPreview]
        fileEditor.selectNotify()
    }

    override fun deselectNotify() {
        baseEditor.deselectNotify()
        val fileEditor = splitEditors[currentPreview]
        fileEditor.deselectNotify()
    }

    override fun getComponent(): JComponent {
        return myComponent
    }

    private fun collectMethods(baseName: String, path: String, truePath: String): List<PsiMethod> {
        val project = editor.project
        val cache = PsiShortNamesCache.getInstance(project)
        val targetMethodName = "test" + Character.toUpperCase(baseName[0]) + baseName.substring(1)
        val psiMethods = cache.getMethodsByName(
            targetMethodName, GlobalSearchScope.allScope(
                project!!
            )
        )
        val methods = Arrays
            .stream(psiMethods)
            .filter { method: PsiMethod -> method.hasAnnotation("org.jetbrains.kotlin.test.TestMetadata") }
            .collect(Collectors.toList())
        val findingMethods: MutableList<PsiMethod> = ArrayList()
        for (method in methods) {
            val classPath = method.containingClass?.extractTestMetadataValue() ?: continue
            val methodPathPart = method?.extractTestMetadataValue() ?: continue
            val methodPath = "$path/$classPath/$methodPathPart"
            println("$methodPath | $truePath")
            if (methodPath == truePath) {
                findingMethods.add(method)
            }
        }
        return findingMethods
    }

    private fun PsiModifierListOwner?.extractTestMetadataValue(): String? {
        return this?.getAnnotation("org.jetbrains.kotlin.test.TestMetadata")
            ?.parameterList
            ?.attributes
            ?.get(0)
            ?.literalValue
    }

    private fun createMarkdownToolbarWrapper(targetComponentForActions: JComponent): SplitEditorToolbar {
        val leftToolbar = createToolbar()
        leftToolbar.setTargetComponent(targetComponentForActions)
        leftToolbar.setReservePlaceAutoPopupIcon(false)
        val rightToolbar = createRightToolbar()
        rightToolbar.setTargetComponent(targetComponentForActions)
        rightToolbar.setReservePlaceAutoPopupIcon(false)
        return SplitEditorToolbar(leftToolbar, rightToolbar)
    }

    override fun setState(state: FileEditorState) {
        if (state !is MyFileEditorState) return
        if (state.firstState != null) {
            baseEditor.setState(state.firstState)
        }
        if (state.secondState != null) {
            splitEditors[currentPreview].setState(state.secondState)
        }
        if (state.splitLayout != null) {
            myLayout = state.splitLayout
            invalidateLayout()
        }
    }

    private fun adjustEditorsVisibility() {
        baseEditor
            .component.isVisible =
            myLayout == Layout.SHOW_EDITOR || myLayout == Layout.SHOW_EDITOR_AND_PREVIEW
        splitEditors[currentPreview]
            .component.isVisible =
            myLayout == Layout.SHOW_PREVIEW || myLayout == Layout.SHOW_EDITOR_AND_PREVIEW
    }

    private fun invalidateLayout() {
        adjustEditorsVisibility()
        myToolbarWrapper.refresh()
        myComponent.repaint()
        val focusComponent = preferredFocusedComponent
        if (focusComponent != null) {
            IdeFocusManager.findInstanceByComponent(focusComponent).requestFocus(focusComponent, true)
        }
    }

    override fun getPreferredFocusedComponent(): JComponent? {
        return when (myLayout) {
            Layout.SHOW_EDITOR_AND_PREVIEW, Layout.SHOW_EDITOR -> baseEditor.preferredFocusedComponent
            Layout.SHOW_PREVIEW -> splitEditors[currentPreview].preferredFocusedComponent
        }
    }

    override fun getName(): String {
        return name
    }

    override fun getState(level: FileEditorStateLevel): FileEditorState {
        return MyFileEditorState(myLayout, baseEditor.getState(level), splitEditors[currentPreview].getState(level))
    }

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {
        baseEditor.addPropertyChangeListener(listener)
        splitEditors[currentPreview].addPropertyChangeListener(listener)
        val delegate = listenersGenerator.addListenerAndGetDelegate(listener)
        baseEditor.addPropertyChangeListener(delegate)
        splitEditors[currentPreview].addPropertyChangeListener(delegate)
    }

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {
        baseEditor.removePropertyChangeListener(listener)
        splitEditors[currentPreview].removePropertyChangeListener(listener)
        val delegate = listenersGenerator.removeListenerAndGetDelegate(listener)
        if (delegate != null) {
            baseEditor.removePropertyChangeListener(delegate)
            splitEditors[currentPreview].removePropertyChangeListener(delegate)
        }
    }

    class MyFileEditorState(
        val splitLayout: Layout?,
        val firstState: FileEditorState?,
        val secondState: FileEditorState?
    ) : FileEditorState {
        override fun canBeMergedWith(otherState: FileEditorState, level: FileEditorStateLevel): Boolean {
            return (otherState is MyFileEditorState
                    && (firstState == null || firstState.canBeMergedWith(otherState.firstState!!, level))
                    && (secondState == null || secondState.canBeMergedWith(otherState.secondState!!, level)))
        }
    }

    override fun isModified(): Boolean {
        return baseEditor.isModified || splitEditors[currentPreview].isModified
    }

    override fun isValid(): Boolean {
        return baseEditor.isValid && splitEditors[currentPreview].isValid
    }

    private inner class DoublingEventListenerDelegate(private val myDelegate: PropertyChangeListener) :
        PropertyChangeListener {
        override fun propertyChange(evt: PropertyChangeEvent) {
            myDelegate.propertyChange(
                PropertyChangeEvent(this, evt.propertyName, evt.oldValue, evt.newValue)
            )
        }
    }

    private inner class ListenersMultimap {
        private val myMap: MutableMap<PropertyChangeListener, Pair<Int, DoublingEventListenerDelegate>> = HashMap()

        fun addListenerAndGetDelegate(listener: PropertyChangeListener): DoublingEventListenerDelegate {
            if (!myMap.containsKey(listener)) {
                myMap[listener] =
                    Pair.create(
                        1,
                        DoublingEventListenerDelegate(listener)
                    )
            } else {
                val oldPair = myMap[listener]!!
                myMap[listener] =
                    Pair.create(
                        oldPair.getFirst() + 1,
                        oldPair.getSecond()
                    )
            }
            return myMap[listener]!!.getSecond()
        }

        fun removeListenerAndGetDelegate(listener: PropertyChangeListener): DoublingEventListenerDelegate? {
            val oldPair = myMap[listener] ?: return null
            if (oldPair.getFirst() == 1) {
                myMap.remove(listener)
            } else {
                myMap[listener] =
                    Pair.create(
                        oldPair.getFirst() - 1,
                        oldPair.getSecond()
                    )
            }
            return oldPair.getSecond()
        }
    }

    private fun createBuildActionGroup() {
        val file = baseEditor.file ?: return
        val name = file.nameWithoutExtension
        val truePath = file.path
        val path = baseEditor.editor.project!!.basePath
        val testMethods = collectMethods(name, path!!, truePath)
        val ex = TestRunLineMarkerProvider()
        for (testMethod in testMethods) {
            val identifier = Arrays
                .stream(testMethod.children)
                .filter { p: PsiElement? -> p is PsiIdentifier }
                .findFirst()
                .orElse(null) ?: continue
            val info = ex.getInfo(identifier) ?: continue
            var allActions = info.actions
            if (allActions.isEmpty()) {
                continue
            }
            allActions = Arrays.copyOf(allActions, 2)
            val group: List<AnAction> = Arrays.stream(allActions).map {
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
            }.collect(Collectors.toList())
            val first = group[0]
            val newAction1: AnAction = object : AnAction() {
                override fun actionPerformed(e: AnActionEvent) {
                    first.actionPerformed(e)
                }

                override fun update(e: AnActionEvent) {
                    first.update(e)
                    e.presentation.icon = AllIcons.Actions.Execute
                }
            }
            val second = group[1]
            val newAction2: AnAction = object : AnAction() {
                override fun actionPerformed(e: AnActionEvent) {
                    second.actionPerformed(e)
                }

                override fun update(e: AnActionEvent) {
                    second.update(e)
                    e.presentation.icon = AllIcons.Actions.StartDebugger
                }
            }
            val finalActions: MutableList<AnAction> = ArrayList()
            finalActions.add(newAction1)
            finalActions.add(newAction2)
            debugAndRun.add(finalActions)

            val topLevelClass = testMethod.parentsOfType<PsiClass>().last()
            methodsClassNames.add(topLevelClass.name!!)
        }
    }

    private fun createToolbar(): ActionToolbar {
        createBuildActionGroup()
        return ActionManager
            .getInstance()
            .createActionToolbar(
                ActionPlaces.TEXT_EDITOR_WITH_PREVIEW,
                DefaultActionGroup(createPreviewActionGroup(), GeneratedTestComboBox(), currentGroup),
                true
            )
    }

    private fun createPreviewActionGroup(): ActionGroup {
        return DefaultActionGroup(MyComboboxAction())
    }

    private inner class GeneratedTestComboBox : ComboBoxAction() {
        override fun createPopupActionGroup(button: JComponent): DefaultActionGroup {
            return DefaultActionGroup()
        }

        override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
            val box = ComboBox(DefaultComboBoxModel(debugAndRun.toTypedArray()))
            box.addActionListener { changeDebugAndRun(box.item) }
            box.renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean
                ): Component {
                    val originalComponent = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    val order = debugAndRun.indexOf(value)
                    text = methodsClassNames[order]
                    return originalComponent
                }
            }
            box.putClientProperty(DarculaUIUtil.COMPACT_PROPERTY, true)
            val panel = JPanel()
            panel.layout = BoxLayout(panel, BoxLayout.X_AXIS)
            val label = JBLabel("Tests: ")
            panel.add(label)
            panel.add(box)
            return panel
        }
    }

    private inner class MyComboboxAction : ComboBoxAction() {
        override fun createPopupActionGroup(button: JComponent): DefaultActionGroup {
            return DefaultActionGroup()
        }

        override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
            val box: ComboBox<*> = ComboBox(DefaultComboBoxModel<Any?>(splitEditors.toTypedArray()))
            box.addActionListener { changeEditor(box.item as FileEditor) }
            box.renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean
                ): Component {
                    val originalComponent =
                        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    text = if (value is FileEditor) {
                        Objects.requireNonNull(value)!!
                            .file!!.name
                    } else {
                        "#########"
                    }
                    return originalComponent
                }
            }
            box.putClientProperty(DarculaUIUtil.COMPACT_PROPERTY, true)
            val panel = JPanel()
            panel.layout = BoxLayout(panel, BoxLayout.X_AXIS)
            val label = JBLabel("Dump files: ")
            //            label.setFont(JBUI.Fonts.smallFont());
            panel.add(label)
            panel.add(box)
            return panel
        }
    }

    private fun createRightToolbar(): ActionToolbar {
        val viewActions = createViewActionGroup()
        val group = createRightToolbarActionGroup()
        val rightToolbarActions =
            if (group == null) viewActions else DefaultActionGroup(group, Separator.create(), viewActions)
        return ActionManager.getInstance()
            .createActionToolbar(ActionPlaces.TEXT_EDITOR_WITH_PREVIEW, rightToolbarActions, true)
    }

    private fun createViewActionGroup(): ActionGroup {
        return DefaultActionGroup(
            showEditorAction,
            showEditorAndPreviewAction,
            showPreviewAction
        )
    }

    private fun createRightToolbarActionGroup(): ActionGroup? {
        return null
    }

    private val showEditorAction: ToggleAction
        get() = ChangeViewModeAction(Layout.SHOW_EDITOR)
    private val showPreviewAction: ToggleAction
        get() = ChangeViewModeAction(Layout.SHOW_PREVIEW)
    private val showEditorAndPreviewAction: ToggleAction
        get() = ChangeViewModeAction(Layout.SHOW_EDITOR_AND_PREVIEW)

    enum class Layout(val presentableName: String, val icon: Icon) {
        SHOW_EDITOR("Editor only", AllIcons.General.LayoutEditorOnly),
        SHOW_PREVIEW("Preview only", AllIcons.General.LayoutPreviewOnly),
        SHOW_EDITOR_AND_PREVIEW("Editor and Preview", AllIcons.General.LayoutEditorPreview);

        companion object {
            fun fromName(name: String?, defaultValue: Layout): Layout {
                for (layout in values()) {
                    if (layout.presentableName == name) {
                        return layout
                    }
                }
                return defaultValue
            }
        }
    }

    private inner class ChangeViewModeAction(private val myActionLayout: Layout) : ToggleAction(
        myActionLayout.presentableName,
        myActionLayout.presentableName,
        myActionLayout.icon
    ), DumbAware {
        override fun isSelected(e: AnActionEvent): Boolean {
            return myLayout == myActionLayout
        }

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (state) {
                myLayout = myActionLayout
                PropertiesComponent.getInstance()
                    .setValue(layoutPropertyName, myLayout.presentableName, myDefaultLayout.presentableName)
                adjustEditorsVisibility()
            }
        }
    }

    private val layoutPropertyName: String
        get() = name + "Layout"

    override fun getFile(): VirtualFile? {
        return baseEditor.file
    }

    override fun getEditor(): Editor {
        return baseEditor.editor
    }

    override fun canNavigateTo(navigatable: Navigatable): Boolean {
        return baseEditor.canNavigateTo(navigatable)
    }

    override fun navigateTo(navigatable: Navigatable) {
        baseEditor.navigateTo(navigatable)
    }
}
