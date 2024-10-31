package org.jetbrains.kotlin.test.helper.ui.settings

import com.intellij.ui.components.fields.ExpandableTextField
import com.intellij.util.execution.ParametersListUtil
import java.awt.Component
import javax.swing.AbstractCellEditor
import javax.swing.JTable
import javax.swing.table.TableCellEditor

class ExpandableCellEditor : AbstractCellEditor(), TableCellEditor {

    private val expandableTextField = ExpandableTextField(ParametersListUtil::parse, ParametersListUtil::join)

    override fun getCellEditorValue(): Any {
        return expandableTextField.text
    }

    override fun getTableCellEditorComponent(
        table: JTable?,
        value: Any?,
        isSelected: Boolean,
        row: Int,
        column: Int
    ): Component {
        expandableTextField.text = value as String
        return expandableTextField
    }
}
