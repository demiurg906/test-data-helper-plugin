

package org.jetbrains.plugins.template.ui.editor;


import com.intellij.codeHighlighting.BackgroundEditorHighlighter;
import com.intellij.execution.Executor;
import com.intellij.execution.application.JvmApplicationRunLineMarkerContributor;
import com.intellij.execution.dashboard.actions.RunAction;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.lineMarker.ExecutorAction;
import com.intellij.execution.lineMarker.RunLineMarkerContributor;
import com.intellij.icons.AllIcons;
import com.intellij.ide.actions.runAnything.RunAnythingAction;
import com.intellij.ide.structureView.StructureViewBuilder;
import com.intellij.ide.ui.laf.darcula.DarculaUIUtil;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.lang.jvm.types.JvmReferenceType;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.ex.ComboBoxAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.module.ModuleUtil;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.pom.Navigatable;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.testIntegration.TestRunLineMarkerProvider;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.ui.JBUI;
import org.gradle.launcher.cli.RunBuildAction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.idea.refactoring.DataContextUtilsKt;


import javax.swing.*;
import java.awt.*;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static com.intellij.openapi.actionSystem.ActionPlaces.TEXT_EDITOR_WITH_PREVIEW;


public class CustomEditor extends UserDataHolderBase implements TextEditor {
    protected final TextEditor myEditor;
    protected final List<FileEditor> myPreview;
    @NotNull
    private final MyListenersMultimap myListenersGenerator = new MyListenersMultimap();
    private final Layout myDefaultLayout;
    private Layout myLayout;
    private JComponent myComponent;
    private SplitEditorToolbar myToolbarWrapper;
    private final String myName;
    private int currentPreview;
    private JBSplitter splitter = null;

    public CustomEditor(@NotNull TextEditor editor,
                                 @NotNull List<FileEditor> preview,
                                 @NotNull String editorName,
                                 @NotNull Layout defaultLayout,
                                int curPreview
    ) {
        myEditor = editor;
        myPreview = preview;
        myName = editorName;
        myDefaultLayout = defaultLayout;
        currentPreview = curPreview;
    }

    public CustomEditor(@NotNull TextEditor editor,
                        @NotNull List<FileEditor> preview,
                        @NotNull String editorName,
                        int curPreview) {
        this(editor, preview, editorName, Layout.SHOW_EDITOR_AND_PREVIEW, curPreview);
    }

    public CustomEditor(@NotNull TextEditor editor,
                        @NotNull List<FileEditor> preview,
                        int curPreview) {
        this(editor, preview, "TextEditorWithPreview", curPreview);
    }

    @Nullable
    public void changeEditor(FileEditor item) {
        currentPreview = myPreview.indexOf(item);
        splitter.setSecondComponent(myPreview.get(currentPreview).getComponent());
    }

    @Nullable
    @Override
    public BackgroundEditorHighlighter getBackgroundHighlighter() {
        return myEditor.getBackgroundHighlighter();
    }

    @Nullable
    @Override
    public FileEditorLocation getCurrentLocation() {
        return myEditor.getCurrentLocation();
    }

    @Nullable
    @Override
    public StructureViewBuilder getStructureViewBuilder() {
        return myEditor.getStructureViewBuilder();
    }

    @Override
    public void dispose() {
        Disposer.dispose(myEditor);
        FileEditor fileEditor = myPreview.get(currentPreview);
        fileEditor.dispose();
    }

    @Override
    public void selectNotify() {
        myEditor.selectNotify();
        FileEditor fileEditor = myPreview.get(currentPreview);
        fileEditor.selectNotify();
    }

    @Override
    public void deselectNotify() {
        myEditor.deselectNotify();
        FileEditor fileEditor = myPreview.get(currentPreview);
        fileEditor.deselectNotify();
    }

    @NotNull
    @Override
    public JComponent getComponent() {
        if (myComponent == null) {
            splitter = new JBSplitter(false, 0.5f, 0.15f, 0.85f);
            splitter.setSplitterProportionKey(getSplitterProportionKey());
            splitter.setFirstComponent(myEditor.getComponent());
            splitter.setSecondComponent(myPreview.get(currentPreview).getComponent());
            splitter.setDividerWidth(3);

            myToolbarWrapper = createMarkdownToolbarWrapper(splitter);

            if (myLayout == null) {
                String lastUsed = PropertiesComponent.getInstance().getValue(getLayoutPropertyName());
                myLayout = Layout.fromName(lastUsed, myDefaultLayout);
            }
            adjustEditorsVisibility();

            myComponent = JBUI.Panels.simplePanel(splitter).addToTop(myToolbarWrapper);
        }
        return myComponent;
    }

    private @NotNull List<PsiMethod> collectMethods(@NotNull String baseName, @NotNull String path, @NotNull String truePath) {
        System.out.println(baseName + " | " + path + " | " + truePath);
        final Project project = getEditor().getProject();
        final PsiShortNamesCache cache = PsiShortNamesCache.getInstance(project);
        final String targetMethodName = "test" + Character.toUpperCase(baseName.charAt(0)) + baseName.substring(1);
        System.out.println( "method searching name : " + targetMethodName);
        final PsiMethod[] psiMethods = cache.getMethodsByName(targetMethodName, GlobalSearchScope.allScope(project));
        List<PsiMethod> methods = Arrays
                .stream(psiMethods)
                .filter(method -> method.hasAnnotation("org.jetbrains.kotlin.test.TestMetadata"))
                .collect(Collectors.toList());
        System.out.println( "find methods: " + methods.size());

        List<PsiMethod> findingMethods = new ArrayList<>();
        for (PsiMethod method: methods) {
            final String classPath = method.getContainingClass()
                    .getAnnotation("org.jetbrains.kotlin.test.TestMetadata")
                    .getParameterList()
                    .getAttributes()[0]
                    .getLiteralValue();
            final String methodPathPart = method
                    .getAnnotation("org.jetbrains.kotlin.test.TestMetadata")
                    .getParameterList()
                    .getAttributes()[0]
                    .getLiteralValue();
           // D:\programming\summer_nir\kotlin\kotlin\compiler\testData\diagnostics\nativeTests\sharedImmutable.kt
            final String methodPath = path + '/' + classPath + '/' + methodPathPart;
            System.out.println( methodPath + " | " + truePath);
            if (methodPath.equals(truePath)) {
                findingMethods.add(method);
            }
        }
        return findingMethods;
    }

    private @NotNull List<String> collectTestMethods(@NotNull String baseName) {
        //System.out.println("Searching for file: " + baseName);
        final Project project = getEditor().getProject();
        final PsiShortNamesCache cache = PsiShortNamesCache.getInstance(project);
        final String targetName = baseName + "Generated";
        final String targetMethodName = "test" + Character.toUpperCase(baseName.charAt(0)) + baseName.substring(1);
        System.out.println(targetMethodName);
      //  System.out.println("Target file name: " + targetName);
        final PsiClass[] classes = cache.getClassesByName(targetName, GlobalSearchScope.allScope(project));
        final PsiMethod[] psiMethods = cache.getMethodsByName(targetName, GlobalSearchScope.allScope(project));
        System.out.println("Size: " + classes.length);
        for (PsiClass cls: classes) {
            //System.out.println(cls);
            final List<PsiMethod> methods = Arrays.stream(cls.getAllMethods())
                    .filter(method -> StringUtil.startsWith(method.getName(), "test"))
                    .collect(Collectors.toList());
            for (PsiMethod method: methods) {
             //   System.out.println(method.getName());
            }
        }
        return Collections.emptyList();
    }

    @NotNull
    private SplitEditorToolbar createMarkdownToolbarWrapper(@NotNull JComponent targetComponentForActions) {
        final ActionToolbar leftToolbar = createToolbar();
        if (leftToolbar != null) {
            leftToolbar.setTargetComponent(targetComponentForActions);
            leftToolbar.setReservePlaceAutoPopupIcon(false);
        }

        final ActionToolbar rightToolbar = createRightToolbar();
        rightToolbar.setTargetComponent(targetComponentForActions);
        rightToolbar.setReservePlaceAutoPopupIcon(false);

        return new SplitEditorToolbar(leftToolbar, rightToolbar);
    }

    @Override
    public void setState(@NotNull FileEditorState state) {
        if (state instanceof MyFileEditorState) {
            final MyFileEditorState compositeState = (MyFileEditorState)state;
            if (compositeState.getFirstState() != null) {
                myEditor.setState(compositeState.getFirstState());
            }
            if (compositeState.getSecondState() != null) {
                myPreview.get(currentPreview).setState(compositeState.getSecondState());
            }
            if (compositeState.getSplitLayout() != null) {
                myLayout = compositeState.getSplitLayout();
                invalidateLayout();
            }
        }
    }

    private void adjustEditorsVisibility() {
        myEditor
                .getComponent()
                .setVisible(myLayout == Layout.SHOW_EDITOR || myLayout == Layout.SHOW_EDITOR_AND_PREVIEW);
        myPreview
                .get(currentPreview)
                .getComponent()
                .setVisible(myLayout == Layout.SHOW_PREVIEW || myLayout == Layout.SHOW_EDITOR_AND_PREVIEW);
    }

    private void invalidateLayout() {
        adjustEditorsVisibility();
        myToolbarWrapper.refresh();
        myComponent.repaint();

        final JComponent focusComponent = getPreferredFocusedComponent();
        if (focusComponent != null) {
            IdeFocusManager.findInstanceByComponent(focusComponent).requestFocus(focusComponent, true);
        }
    }

    @NotNull
    protected String getSplitterProportionKey() {

        return "TextEditorWithPreview.SplitterProportionKey";
    }

    @Nullable
    @Override
    public JComponent getPreferredFocusedComponent() {
        switch (myLayout) {
            case SHOW_EDITOR_AND_PREVIEW:
            case SHOW_EDITOR:
                return myEditor.getPreferredFocusedComponent();
            case SHOW_PREVIEW:
                return myPreview.get(currentPreview).getPreferredFocusedComponent();
            default:
                throw new IllegalStateException(myLayout.myName);
        }
    }

    @NotNull
    @Override
    public String getName() {
        return myName;
    }

    @NotNull
    @Override
    public FileEditorState getState(@NotNull FileEditorStateLevel level) {
        return new MyFileEditorState(myLayout, myEditor.getState(level), myPreview.get(currentPreview).getState(level));
    }

    @Override
    public void addPropertyChangeListener(@NotNull PropertyChangeListener listener) {
        myEditor.addPropertyChangeListener(listener);
        myPreview.get(currentPreview).addPropertyChangeListener(listener);

        final DoublingEventListenerDelegate delegate = myListenersGenerator.addListenerAndGetDelegate(listener);
        myEditor.addPropertyChangeListener(delegate);
        myPreview.get(currentPreview).addPropertyChangeListener(delegate);
    }

    @Override
    public void removePropertyChangeListener(@NotNull PropertyChangeListener listener) {
        myEditor.removePropertyChangeListener(listener);
        myPreview.get(currentPreview).removePropertyChangeListener(listener);

        final DoublingEventListenerDelegate delegate = myListenersGenerator.removeListenerAndGetDelegate(listener);
        if (delegate != null) {
            myEditor.removePropertyChangeListener(delegate);
            myPreview.get(currentPreview).removePropertyChangeListener(delegate);
        }
    }

    @NotNull
    public TextEditor getTextEditor() {
        return myEditor;
    }

    public Layout getLayout() {
        return myLayout;
    }

    static class MyFileEditorState implements FileEditorState {
        private final Layout mySplitLayout;
        private final FileEditorState myFirstState;
        private final FileEditorState mySecondState;

        MyFileEditorState(Layout layout, FileEditorState firstState, FileEditorState secondState) {
            mySplitLayout = layout;
            myFirstState = firstState;
            mySecondState = secondState;
        }

        @Nullable
        public Layout getSplitLayout() {
            return mySplitLayout;
        }

        @Nullable
        public FileEditorState getFirstState() {
            return myFirstState;
        }

        @Nullable
        public FileEditorState getSecondState() {
            return mySecondState;
        }

        @Override
        public boolean canBeMergedWith(FileEditorState otherState, FileEditorStateLevel level) {
            return otherState instanceof MyFileEditorState
                    && (myFirstState == null || myFirstState.canBeMergedWith(((MyFileEditorState)otherState).myFirstState, level))
                    && (mySecondState == null || mySecondState.canBeMergedWith(((MyFileEditorState)otherState).mySecondState, level));
        }
    }

    @Override
    public boolean isModified() {
        return myEditor.isModified() || myPreview.get(currentPreview).isModified();
    }

    @Override
    public boolean isValid() {
        return myEditor.isValid() && myPreview.get(currentPreview).isValid();
    }

    private class DoublingEventListenerDelegate implements PropertyChangeListener {
        @NotNull
        private final PropertyChangeListener myDelegate;

        private DoublingEventListenerDelegate(@NotNull PropertyChangeListener delegate) {
            myDelegate = delegate;
        }

        @Override
        public void propertyChange(PropertyChangeEvent evt) {
            myDelegate.propertyChange(
                    new PropertyChangeEvent(this, evt.getPropertyName(), evt.getOldValue(), evt.getNewValue()));
        }
    }

    private class MyListenersMultimap {
        private final Map<PropertyChangeListener, Pair<Integer, DoublingEventListenerDelegate>> myMap = new HashMap<>();

        @NotNull
        public DoublingEventListenerDelegate addListenerAndGetDelegate(@NotNull PropertyChangeListener listener) {
            if (!myMap.containsKey(listener)) {
                myMap.put(listener, Pair.create(1, new DoublingEventListenerDelegate(listener)));
            }
            else {
                final Pair<Integer, DoublingEventListenerDelegate> oldPair = myMap.get(listener);
                myMap.put(listener, Pair.create(oldPair.getFirst() + 1, oldPair.getSecond()));
            }

            return myMap.get(listener).getSecond();
        }

        @Nullable
        public DoublingEventListenerDelegate removeListenerAndGetDelegate(@NotNull PropertyChangeListener listener) {
            final Pair<Integer, DoublingEventListenerDelegate> oldPair = myMap.get(listener);
            if (oldPair == null) {
                return null;
            }

            if (oldPair.getFirst() == 1) {
                myMap.remove(listener);
            }
            else {
                myMap.put(listener, Pair.create(oldPair.getFirst() - 1, oldPair.getSecond()));
            }
            return oldPair.getSecond();
        }
    }

    private ActionGroup createBuildActionGroup() {
//        DumbService.getInstance(myEditor.getEditor().getProject()).runWhenSmart(() -> {
            final String name = myEditor.getFile().getNameWithoutExtension();
            final String truePath = myEditor.getFile().getPath();
            final String path = myEditor.getEditor().getProject().getBasePath();
            List<PsiMethod> testMethods = collectMethods(name, path, truePath);
            System.out.println("testMethods were: " + testMethods.size());
            TestRunLineMarkerProvider ex = new TestRunLineMarkerProvider();
            for (PsiMethod testMethod : testMethods) {
                System.out.println("Processing " + testMethod.getName());
                PsiElement first = Arrays
                        .stream(testMethod.getChildren())
                        .filter(p -> p instanceof PsiIdentifier)
                        .findFirst()
                        .orElse(null);
                if (first == null) {
                    continue;
                }
                RunLineMarkerContributor.Info info = ex.getInfo(first);
                if (info == null) {
                    continue;
                }
                AnAction[] allActions = info.actions;
                if (allActions.length == 0) {
                    continue;
                }

                final DefaultActionGroup group = new DefaultActionGroup(Arrays.stream(allActions).map(it -> new AnAction() {
                    @Override
                    public void actionPerformed(@NotNull AnActionEvent e) {

                    }

                    @Override
                    public void update(@NotNull AnActionEvent e) {
                        it.update(e);
                        e.getPresentation().setEnabledAndVisible(true);
                    }
                }).collect(Collectors.toList()));
                group.setPopup(true);
                return group;
//                ((DefaultActionGroup) actionGroup).add(new DefaultActionGroup(allActions));
//                break;
            }
            if (myComponent != null) {
                myComponent.revalidate();
                myComponent.repaint();
            }
//        });
        throw new IllegalStateException();
    }

    @Nullable
    protected ActionToolbar createToolbar() {
//        actionGroup = createPreviewActionGroup();
        final ActionGroup actionGroup = createBuildActionGroup();
        ActionToolbar actionToolbar = ActionManager.getInstance().createActionToolbar(TEXT_EDITOR_WITH_PREVIEW, actionGroup, true);
        return actionToolbar;
    }

    @NotNull
    protected ActionGroup createPreviewActionGroup() {
        return new DefaultActionGroup(new MyComboboxAction());
    }

    private class MyComboboxAction extends ComboBoxAction {

        @Override
        protected @NotNull DefaultActionGroup createPopupActionGroup(JComponent button) {
            return new DefaultActionGroup();
        }

        @NotNull
        @Override
        public JComponent createCustomComponent(@NotNull Presentation presentation, @NotNull String place) {
            final ComboBox box = new ComboBox(new DefaultComboBoxModel(myPreview.toArray()));
            box.addActionListener((event) -> {
                changeEditor((FileEditor) box.getItem());
            });
            box.setRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                    final Component originalComponent = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                    if (value instanceof FileEditor) {
                        setText(Objects.requireNonNull((FileEditor) value).getFile().getName());
                    } else {
                        setText("#########");
                    }
                    return originalComponent;
                }
            });
            box.putClientProperty(DarculaUIUtil.COMPACT_PROPERTY, true);
            final JPanel panel = new JPanel();
            panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));
            final JBLabel label = new JBLabel("Test file: ");
//            label.setFont(JBUI.Fonts.smallFont());
            panel.add(label);
            panel.add(box);
            return panel;
        }
    }

    @NotNull
    private ActionToolbar createRightToolbar() {
        final ActionGroup viewActions = createViewActionGroup();
        final ActionGroup group = createRightToolbarActionGroup();
        final ActionGroup rightToolbarActions = group == null
                ? viewActions
                : new DefaultActionGroup(group, Separator.create(), viewActions);
        return ActionManager.getInstance().createActionToolbar(TEXT_EDITOR_WITH_PREVIEW, rightToolbarActions, true);
    }

    @NotNull
    protected ActionGroup createViewActionGroup() {
        return new DefaultActionGroup(
                getShowEditorAction(),
                getShowEditorAndPreviewAction(),
                getShowPreviewAction()
        );
    }

    @Nullable
    protected ActionGroup createRightToolbarActionGroup() {
        return null;
    }

    @NotNull
    protected ToggleAction getShowEditorAction() {
        return new ChangeViewModeAction(Layout.SHOW_EDITOR);
    }

    @NotNull
    protected ToggleAction getShowPreviewAction() {
        return new ChangeViewModeAction(Layout.SHOW_PREVIEW);
    }

    @NotNull
    protected ToggleAction getShowEditorAndPreviewAction() {
        return new ChangeViewModeAction(Layout.SHOW_EDITOR_AND_PREVIEW);
    }

    public enum Layout {
        SHOW_EDITOR("Editor only", AllIcons.General.LayoutEditorOnly),
        SHOW_PREVIEW("Preview only", AllIcons.General.LayoutPreviewOnly),
        SHOW_EDITOR_AND_PREVIEW("Editor and Preview", AllIcons.General.LayoutEditorPreview);

        private final String myName;
        private final Icon myIcon;

        Layout(String name, Icon icon) {
            myName = name;
            myIcon = icon;
        }

        public static Layout fromName(String name, Layout defaultValue) {
            for (Layout layout : Layout.values()) {
                if (layout.myName.equals(name)) {
                    return layout;
                }
            }
            return defaultValue;
        }

        public String getName() {
            return myName;
        }

        public Icon getIcon() {
            return myIcon;
        }
    }

    private class ChangeViewModeAction extends ToggleAction implements DumbAware {
        private final Layout myActionLayout;

        ChangeViewModeAction(Layout layout) {
            super(layout.getName(), layout.getName(), layout.getIcon());
            myActionLayout = layout;
        }

        @Override
        public boolean isSelected(@NotNull AnActionEvent e) {
            return myLayout == myActionLayout;
        }

        @Override
        public void setSelected(@NotNull AnActionEvent e, boolean state) {
            if (state) {
                myLayout = myActionLayout;
                PropertiesComponent.getInstance().setValue(getLayoutPropertyName(), myLayout.myName, myDefaultLayout.myName);
                adjustEditorsVisibility();
            }
        }
    }

    @NotNull
    private String getLayoutPropertyName() {
        return myName + "Layout";
    }

    @Override
    public @Nullable
    VirtualFile getFile() {
        return getTextEditor().getFile();
    }

    @Override
    public @NotNull
    Editor getEditor() {
        return getTextEditor().getEditor();
    }

    @Override
    public boolean canNavigateTo(@NotNull Navigatable navigatable) {
        return getTextEditor().canNavigateTo(navigatable);
    }

    @Override
    public void navigateTo(@NotNull Navigatable navigatable) {
        getTextEditor().navigateTo(navigatable);
    }
}