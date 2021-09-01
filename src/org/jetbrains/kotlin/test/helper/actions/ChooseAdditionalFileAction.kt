package org.jetbrains.kotlin.test.helper.actions

import com.intellij.ide.ui.laf.darcula.DarculaUIUtil
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBLabel
import org.jetbrains.kotlin.test.helper.state.PreviewEditorState
import org.jetbrains.kotlin.test.helper.ui.TestDataEditor
import java.awt.Component
import javax.swing.*

class ChooseAdditionalFileAction(
    private val testDataEditor: TestDataEditor,
    private val previewEditorState: PreviewEditorState
) : ComboBoxAction() {
    override fun createPopupActionGroup(button: JComponent): DefaultActionGroup {
        return DefaultActionGroup()
    }

    override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
        val box = ComboBox(DefaultComboBoxModel(previewEditorState.previewEditors.toTypedArray())).apply {
            item = previewEditorState.previewEditors[previewEditorState.currentPreviewIndex]
            addActionListener {
                previewEditorState.chooseNewEditor(item)

                testDataEditor.updatePreviewEditor()
            }
            renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean
                ): Component {
                    val originalComponent = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    text = (value as? FileEditor)?.file?.name ?: "## no name provided ##"
                    return originalComponent
                }
            }
            putClientProperty(DarculaUIUtil.COMPACT_PROPERTY, true)
        }

        val label = JBLabel("Available files: ")

        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(label)
            add(box)
        }
    }
}
