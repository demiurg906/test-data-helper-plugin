package org.jetbrains.plugins.template.ui.editor

import com.intellij.diff.util.FileEditorBase
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.annotations.NotNull
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlinx.serialization.compiler.backend.jvm.OBJECT
import java.awt.BorderLayout
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.JComponent
import javax.swing.JPanel


class MyKotlinFileEditorProvider: AsyncFileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean {
        return file.fileType == KotlinFileType.INSTANCE
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        val originalEditor = TextEditorProvider.getInstance().createEditor(project, file) as TextEditor
        return object: FileEditorBase() {
            private val actualComponent by lazy {
                JPanel(BorderLayout()).apply {
                    add(createActionToolbar("MyAwesomeToolbarGroup").component, BorderLayout.NORTH)
                    add(originalEditor.component, BorderLayout.CENTER)
                }
            }

            init {
                Disposer.register(this, originalEditor)
            }

            override fun getComponent(): JComponent = actualComponent

            override fun getName(): String = originalEditor.name

            override fun getPreferredFocusedComponent(): JComponent? = actualComponent
        }
    }

    override fun getEditorTypeId(): String = "My Kotlin Editor With Toolbar"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR

    override fun createEditorAsync(project: Project, file: VirtualFile): AsyncFileEditorProvider.Builder {
        return object: AsyncFileEditorProvider.Builder() {
            override fun build(): FileEditor {
                val originalEditor = TextEditorProvider.getInstance().createEditor(project, file) as TextEditor
                val originalEditor2 = TextEditorProvider.getInstance().createEditor(project, file) as TextEditor
                val originalEditor3 = TextEditorProvider.getInstance().createEditor(project, file) as TextEditor
                val name = file.name;
                val name2 = file.presentableName;
                val fileSystem = LocalFileSystem.getInstance();
                val newFile = fileSystem.findFileByIoFile(Paths.get(File(file.path).parentFile.toString(), File(file.path).nameWithoutExtension + ".txt").toFile())
                val originalEditor4 = newFile?.let { TextEditorProvider.getInstance().createEditor(project, it) } as TextEditor
                return object: CustomEditor(originalEditor,  listOf(originalEditor2, originalEditor4), 0){
//                    override fun createLeftToolbarActionGroup(): ActionGroup? {
//                        return ActionManager.getInstance().getAction("MyAwesomeToolbarGroup")!! as ActionGroup
//                    }
                }
            }
        }
    }

    companion object {
        private fun createActionToolbar(groupId: String, horizontal: Boolean = true): ActionToolbar {
            val group = ActionManager.getInstance().getAction(groupId)
            checkNotNull(group)
            check(group is ActionGroup)
            return createActionToolbar(group, horizontal)
        }

        private fun createActionToolbar(group: ActionGroup, horizontal: Boolean = true): ActionToolbar {
            return ActionManager.getInstance().createActionToolbar("", group, horizontal)
        }
    }
}