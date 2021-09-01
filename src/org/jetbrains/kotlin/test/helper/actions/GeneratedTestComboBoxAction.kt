package org.jetbrains.kotlin.test.helper.actions

import com.intellij.ide.ui.laf.darcula.DarculaUIUtil
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBLabel
import org.jetbrains.kotlin.test.helper.state.RunTestBoxState
import java.awt.Component
import javax.swing.*

class GeneratedTestComboBoxAction(val runTestBoxState: RunTestBoxState) : ComboBoxAction() {
    private lateinit var box: ComboBox<List<AnAction>>
    private lateinit var boxModel: DefaultComboBoxModel<List<AnAction>>

    override fun createPopupActionGroup(button: JComponent): DefaultActionGroup {
        return DefaultActionGroup()
    }

    override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
        boxModel = DefaultComboBoxModel(runTestBoxState.debugAndRunActionLists.toTypedArray())
        box = ComboBox(boxModel).apply {
            addActionListener {
                item?.let { runTestBoxState.changeDebugAndRun(it) }
            }
            renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean
                ): Component {
                    val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    if (value != null) {
                        val order = runTestBoxState.debugAndRunActionLists.indexOf(value)
                        if (order in runTestBoxState.methodsClassNames.indices) {
                            text = runTestBoxState.methodsClassNames[order]
                        }
                    }
                    return component
                }
            }
            putClientProperty(DarculaUIUtil.COMPACT_PROPERTY, true)
            updateWidth()
        }

        val label = JBLabel("Tests: ")


        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(label)
            add(box)
        }
    }

    private fun ComboBox<List<AnAction>>.updateWidth() {
        val maxTestName = runTestBoxState.methodsClassNames.maxByOrNull { it.length } ?: ""
        setMinimumAndPreferredWidth(getFontMetrics(font).stringWidth(maxTestName) + 80)
    }

    fun updateBox() {
        runTestBoxState.initialize()
        boxModel.removeAllElements()
        boxModel.addAll(runTestBoxState.debugAndRunActionLists)
        box.updateWidth()
    }
}
