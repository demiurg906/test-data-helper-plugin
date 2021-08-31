package org.jetbrains.plugins.template.ui.editor

import com.intellij.codeHighlighting.BackgroundEditorHighlighter
import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.execution.actions.ConfigurationContext
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
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.testIntegration.TestRunLineMarkerProvider
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.event.ActionEvent
import java.beans.PropertyChangeEvent
import java.beans.PropertyChangeListener
import java.util.*
import java.util.stream.Collectors
import javax.swing.*
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty


fun <T : Any> lazyVar(init: () -> T) : ReadWriteProperty<Any?, T> {
    return object : ReadWriteProperty<Any?, T> {
        private var value: T? = null

        override fun getValue(thisRef: Any?, property: KProperty<*>): T {
            if (value == null) {
                value = init()
            }
            return value!!
        }

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            this.value = value
        }
    }
}

class CustomEditor(
    val myEditor: TextEditor,
    protected val myPreview: List<FileEditor>,
    private val myName: String,
    private val myDefaultLayout: Layout,
    private var currentPreview: Int
) : UserDataHolderBase(), TextEditor {
    private val myListenersGenerator: MyListenersMultimap = MyListenersMultimap()
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
            firstComponent = myEditor.component
            secondComponent = myPreview[currentPreview].component
            dividerWidth = 3
        }
    }
    private val debugAndRun: MutableList<List<AnAction>> = ArrayList()
    private var currentChosenGroup = 0
    private val currentGroup = DefaultActionGroup()
    private val methodsClassNames: MutableList<String> = ArrayList()

    constructor(
        editor: TextEditor,
        preview: List<FileEditor>,
        editorName: String,
        curPreview: Int
    ) : this(editor, preview, editorName, Layout.SHOW_EDITOR_AND_PREVIEW, curPreview) {
    }

    constructor(
        editor: TextEditor,
        preview: List<FileEditor>,
        curPreview: Int
    ) : this(editor, preview, "TextEditorWithPreview", curPreview) {
    }

    fun changeEditor(item: FileEditor) {
        currentPreview = myPreview.indexOf(item)
        splitter.secondComponent = myPreview[currentPreview].component
    }

    fun changeDebugAndRun(item: List<AnAction>) {
        currentChosenGroup = debugAndRun.indexOf(item)
        currentGroup.removeAll()
        val current = debugAndRun[currentChosenGroup]
        currentGroup.addAll(current)
    }

    override fun getBackgroundHighlighter(): BackgroundEditorHighlighter? {
        return myEditor.backgroundHighlighter
    }

    override fun getCurrentLocation(): FileEditorLocation? {
        return myEditor.currentLocation
    }

    override fun getStructureViewBuilder(): StructureViewBuilder? {
        return myEditor.structureViewBuilder
    }

    override fun dispose() {
        Disposer.dispose(myEditor)
        val fileEditor = myPreview[currentPreview]
        fileEditor.dispose()
    }

    override fun selectNotify() {
        myEditor.selectNotify()
        val fileEditor = myPreview[currentPreview]
        fileEditor.selectNotify()
    }

    override fun deselectNotify() {
        myEditor.deselectNotify()
        val fileEditor = myPreview[currentPreview]
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
            val classPath = method.containingClass
                ?.getAnnotation("org.jetbrains.kotlin.test.TestMetadata")
                ?.getParameterList()
                ?.attributes
                ?.get(0)
                ?.literalValue
                ?: TODO()
            val methodPathPart = method
                ?.getAnnotation("org.jetbrains.kotlin.test.TestMetadata")
                ?.getParameterList()
                ?.attributes
                ?.get(0)
                ?.literalValue
                ?: TODO()
            val methodPath = "$path/$classPath/$methodPathPart"
            println("$methodPath | $truePath")
            if (methodPath == truePath) {
                findingMethods.add(method)
            }
        }
        return findingMethods
    }

    private fun collectTestMethods(baseName: String): List<String> {
        val project = editor.project
        val cache = PsiShortNamesCache.getInstance(project)
        val targetName = baseName + "Generated"
        val targetMethodName = "test" + Character.toUpperCase(baseName[0]) + baseName.substring(1)
        val classes = cache.getClassesByName(targetName, GlobalSearchScope.allScope(project!!))
        val psiMethods = cache.getMethodsByName(targetName, GlobalSearchScope.allScope(project))
        for (cls in classes) {
            val methods = Arrays.stream(cls.allMethods)
                .filter { method: PsiMethod -> StringUtil.startsWith(method.name, "test") }
                .collect(Collectors.toList())
            for (method in methods) {
                //   System.out.println(method.getName());
            }
        }
        return emptyList()
    }

    private fun createMarkdownToolbarWrapper(targetComponentForActions: JComponent): SplitEditorToolbar {
        val leftToolbar = createToolbar()
        if (leftToolbar != null) {
            leftToolbar.setTargetComponent(targetComponentForActions)
            leftToolbar.setReservePlaceAutoPopupIcon(false)
        }
        val rightToolbar = createRightToolbar()
        rightToolbar.setTargetComponent(targetComponentForActions)
        rightToolbar.setReservePlaceAutoPopupIcon(false)
        return SplitEditorToolbar(leftToolbar, rightToolbar)
    }

    override fun setState(state: FileEditorState) {
        if (state is MyFileEditorState) {
            val compositeState = state
            if (compositeState.firstState != null) {
                myEditor.setState(compositeState.firstState)
            }
            if (compositeState.secondState != null) {
                myPreview[currentPreview].setState(compositeState.secondState)
            }
            if (compositeState.splitLayout != null) {
                myLayout = compositeState.splitLayout
                invalidateLayout()
            }
        }
    }

    private fun adjustEditorsVisibility() {
        myEditor
            .component.isVisible =
            myLayout == Layout.SHOW_EDITOR || myLayout == Layout.SHOW_EDITOR_AND_PREVIEW
        myPreview[currentPreview]
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
            Layout.SHOW_EDITOR_AND_PREVIEW, Layout.SHOW_EDITOR -> myEditor.preferredFocusedComponent
            Layout.SHOW_PREVIEW -> myPreview[currentPreview].preferredFocusedComponent
        }
    }

    override fun getName(): String {
        return myName
    }

    override fun getState(level: FileEditorStateLevel): FileEditorState {
        return MyFileEditorState(myLayout, myEditor.getState(level), myPreview[currentPreview].getState(level))
    }

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {
        myEditor.addPropertyChangeListener(listener)
        myPreview[currentPreview].addPropertyChangeListener(listener)
        val delegate = myListenersGenerator.addListenerAndGetDelegate(listener)
        myEditor.addPropertyChangeListener(delegate)
        myPreview[currentPreview].addPropertyChangeListener(delegate)
    }

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {
        myEditor.removePropertyChangeListener(listener)
        myPreview[currentPreview].removePropertyChangeListener(listener)
        val delegate = myListenersGenerator.removeListenerAndGetDelegate(listener)
        if (delegate != null) {
            myEditor.removePropertyChangeListener(delegate)
            myPreview[currentPreview].removePropertyChangeListener(delegate)
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
        return myEditor.isModified || myPreview[currentPreview].isModified
    }

    override fun isValid(): Boolean {
        return myEditor.isValid && myPreview[currentPreview].isValid
    }

    private inner class DoublingEventListenerDelegate(private val myDelegate: PropertyChangeListener) :
        PropertyChangeListener {
        override fun propertyChange(evt: PropertyChangeEvent) {
            myDelegate.propertyChange(
                PropertyChangeEvent(this, evt.propertyName, evt.oldValue, evt.newValue)
            )
        }
    }

    private inner class MyListenersMultimap {
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
        val file = myEditor.file ?: return
        val name = file.nameWithoutExtension
        val truePath = file.path
        val path = myEditor.editor.project!!.basePath
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
                        val dataId2data: MutableMap<String, Any> = HashMap()
                        val newLocation = PsiLocation.fromPsiElement(identifier)
                        dataId2data[ConfigurationContext.SHARED_CONTEXT.toString()] =
                            ConfigurationContext.createEmptyContextForLocation(newLocation)
                        dataId2data[Location.DATA_KEY.name] = newLocation
                        val dataContext = SimpleDataContext.getSimpleContext(
                            dataId2data,
                            e.dataContext
                        )
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
            var firstContainingClass = testMethod.containingClass
            while (firstContainingClass !== firstContainingClass!!.containingClass && firstContainingClass!!.containingClass != null) {
                firstContainingClass = firstContainingClass.containingClass
            }
            methodsClassNames.add(testMethod.containingClass!!.name!!)
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
            val box: ComboBox<*> = ComboBox(DefaultComboBoxModel<Any?>(debugAndRun.toTypedArray()))
            box.addActionListener { event: ActionEvent? -> changeDebugAndRun(box.item as List<AnAction>) }
            box.renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean
                ): Component {
                    var value = value
                    val originalComponent =
                        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    value = value as List<AnAction?>
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
            val box: ComboBox<*> = ComboBox(DefaultComboBoxModel<Any?>(myPreview.toTypedArray()))
            box.addActionListener { event: ActionEvent? -> changeEditor(box.item as FileEditor) }
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
            val label = JBLabel("dump files: ")
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
        get() = myName + "Layout"

    override fun getFile(): VirtualFile? {
        return myEditor.file
    }

    override fun getEditor(): Editor {
        return myEditor.editor
    }

    override fun canNavigateTo(navigatable: Navigatable): Boolean {
        return myEditor.canNavigateTo(navigatable)
    }

    override fun navigateTo(navigatable: Navigatable) {
        myEditor.navigateTo(navigatable)
    }
}
