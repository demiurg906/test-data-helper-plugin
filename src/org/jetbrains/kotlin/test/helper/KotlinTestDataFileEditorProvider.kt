package org.jetbrains.kotlin.test.helper

import com.intellij.openapi.fileEditor.AsyncFileEditorProvider
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.application
import org.jetbrains.kotlin.test.helper.ui.TestDataEditor

class KotlinTestDataFileEditorProvider: AsyncFileEditorProvider, DumbAware {
    companion object {
        private val supportedExtensions = listOf("kt", "kts", "args")
    }

    override fun accept(project: Project, file: VirtualFile): Boolean {
        if (file.extension !in supportedExtensions) return false
        val configuration = TestDataPathsConfiguration.getInstance(project)
        val filePath = file.path
        return configuration.testDataFiles.any { filePath.startsWith(it) }
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        return application.runReadAction<FileEditor> { createEditorAsync(project, file).build() }
    }

    override fun getEditorTypeId(): String = "Kotlin TestData Editor"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.PLACE_AFTER_DEFAULT_EDITOR

    override fun createEditorAsync(project: Project, file: VirtualFile): AsyncFileEditorProvider.Builder {
        return object: AsyncFileEditorProvider.Builder() {
            override fun build(): FileEditor {
                val baseEditor = TextEditorProvider.getInstance().createEditor(project, file) as TextEditor

                return TestDataEditor(baseEditor)
            }
        }
    }
}
