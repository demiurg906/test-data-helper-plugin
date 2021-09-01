package org.jetbrains.kotlin.test.helper.actions

import com.intellij.ide.ui.laf.darcula.DarculaUIUtil
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBLabel
import org.jetbrains.kotlin.test.helper.state.RunTestBoxState
import java.awt.Component
import javax.swing.*

class GeneratedTestComboBoxAction(val runTestBoxState: RunTestBoxState) : ComboBoxAction() {
    override fun createPopupActionGroup(button: JComponent): DefaultActionGroup {
        return DefaultActionGroup()
    }

    override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
        val box = ComboBox(DefaultComboBoxModel(runTestBoxState.debugAndRunActionLists.toTypedArray())).apply {
            addActionListener { runTestBoxState.changeDebugAndRun(item) }
            renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean
                ): Component {
                    val originalComponent = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    val order = runTestBoxState.debugAndRunActionLists.indexOf(value)
                    text = runTestBoxState.methodsClassNames[order]
                    return originalComponent
                }
            }
            putClientProperty(DarculaUIUtil.COMPACT_PROPERTY, true)
        }

        val label = JBLabel("Tests: ")

        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(label)
            add(box)
        }
    }
}
