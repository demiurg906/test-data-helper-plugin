package org.jetbrains.kotlin.test.helper.state

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.TextEditor

class PreviewEditorState(
    val baseEditor: TextEditor,
    val previewEditors: List<FileEditor>,
    currentPreview: Int
) {
    var currentPreviewIndex: Int = currentPreview.takeIf { it in previewEditors.indices } ?: 0
        private set

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
}
