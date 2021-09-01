package org.jetbrains.kotlin.test.helper.state

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.vfs.LocalFileSystem
import org.jetbrains.kotlin.test.helper.simpleNameUntilFirstDot
import java.io.File
import java.nio.file.Paths

class PreviewEditorState(
    val baseEditor: TextEditor,
    currentPreview: Int
) {
    var previewEditors: List<FileEditor> = findPreviewEditors()
        private set

    var currentPreviewIndex: Int = calculateIndex(currentPreview)
        private set

    private fun calculateIndex(currentPreview: Int): Int =
        currentPreview.takeIf { it in previewEditors.indices } ?: 0

    val currentPreview: FileEditor
        get() = previewEditors[currentPreviewIndex]

    val baseFileIsChosen: Boolean
        get() = baseEditor == previewEditors[currentPreviewIndex]

    fun chooseNewEditor(editor: FileEditor) {
        if (editor !in previewEditors) {
            // TODO: log
            return
        }
        currentPreviewIndex = previewEditors.indexOf(editor)
    }

    fun updatePreviewEditors() {
        val chosenPreview = currentPreview
        previewEditors = findPreviewEditors()
        currentPreviewIndex = calculateIndex(previewEditors.indexOf(chosenPreview))
    }

    private fun findPreviewEditors(): List<FileEditor> {
        val file = baseEditor.file ?: return emptyList()
        val project = baseEditor.editor.project ?: return emptyList()

        val curFileName = file.simpleNameUntilFirstDot

        val relatedFiles = file.parent.children.filter { it.name.startsWith(curFileName) }

        return relatedFiles.map { TextEditorProvider.getInstance().createEditor(project, it) }
    }
}
