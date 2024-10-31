package org.jetbrains.kotlin.test.helper.ui.settings

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.RightAlignedLabelUI
import java.awt.Component
import javax.swing.JTable
import javax.swing.table.DefaultTableCellRenderer

class FilePathRenderer(private val fileForRow: (Int) -> VirtualFile) : DefaultTableCellRenderer() {
    override fun getTableCellRendererComponent(
        table: JTable,
        value: Any,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int
    ): Component {
        val file = fileForRow(row)
        return super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column).apply {
            foreground =
                if (!file.isValid) JBColor.RED else if (isSelected) table.selectionForeground else table.foreground
            background = if (isSelected) table.selectionBackground else table.background
        }
    }

    init {
        setUI(RightAlignedLabelUI())
    }
}
