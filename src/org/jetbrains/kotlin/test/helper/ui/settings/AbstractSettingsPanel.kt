package org.jetbrains.kotlin.test.helper.ui.settings

import com.intellij.ide.IdeBundle
import com.intellij.openapi.compiler.JavaCompilerBundle
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.ui.PanelWithButtons
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.util.Arrays
import javax.swing.JButton
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableCellRenderer

abstract class AbstractSettingsPanel<T> : PanelWithButtons() {
    lateinit var myRemoveButton: JButton
    lateinit var myExcludedTable: JBTable

    abstract val numberOfElements: Int

    abstract fun addElement(index: Int, file: T)
    abstract fun removeElementAt(index: Int)
    abstract fun isElementExcluded(file: T): Boolean
    abstract fun createNewElementsOnAddClick(): List<T>

    override fun createButtons(): Array<JButton> {
        val addButton = JButton(JavaCompilerBundle.message("button.add"))
        addButton.addActionListener { onAddClick() }
        myRemoveButton = JButton(IdeBundle.message("button.remove")).apply {
            addActionListener { onRemoveClick() }
            isEnabled = false
            model.addChangeListener {
                if (myExcludedTable.selectedRow == -1) {
                    isEnabled = false
                }
            }
        }
        return arrayOf()
    }

    fun onAddClick() {
        var selected: Int = numberOfElements
        val savedSelected = selected
        val newElements = createNewElementsOnAddClick()
        for (element in newElements) {
            if (isElementExcluded(element)) {
                continue
            }

            addElement(selected, element)
            selected++
        }
        if (selected > savedSelected) { // actually added something
            val model = myExcludedTable.model as AbstractTableModel
            model.fireTableRowsInserted(savedSelected, selected - 1)
            myExcludedTable.setRowSelectionInterval(savedSelected, selected - 1)
        }
    }

    fun onRemoveClick() {
        val selected = myExcludedTable.selectedRows
        if (selected == null || selected.isEmpty()) {
            return
        }
        if (myExcludedTable.isEditing) {
            val editor = myExcludedTable.cellEditor
            editor?.stopCellEditing()
        }
        val model = myExcludedTable.model as AbstractTableModel
        Arrays.sort(selected)
        var indexToSelect = selected[selected.size - 1]
        var removedCount = 0
        for (indexToRemove in selected) {
            val row = indexToRemove - removedCount
            removeElementAt(row)
            model.fireTableRowsDeleted(row, row)
            removedCount += 1
        }
        if (indexToSelect >= numberOfElements) {
            indexToSelect = numberOfElements - 1
        }
        if (indexToSelect >= 0) {
            myExcludedTable.setRowSelectionInterval(indexToSelect, indexToSelect)
        }
        IdeFocusManager.getGlobalInstance().doWhenFocusSettlesDown {
            IdeFocusManager.getGlobalInstance().requestFocus(
                myExcludedTable, true
            )
        }
    }

    fun JBTable.configure(names: Array<String>, renderer: TableCellRenderer) {
        setShowGrid(false)
        setEnableAntialiasing(true)
        emptyText.text = JavaCompilerBundle.message("no.excludes")
        preferredScrollableViewportSize = JBUI.size(300, -1)
        visibleRowCount = 6
        setDefaultRenderer(Any::class.java, renderer)
        getColumn(names[0]).preferredWidth = 350
        selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        selectionModel.addListSelectionListener {
            myRemoveButton.isEnabled = selectedRow >= 0
        }
    }

    override fun getLabelText(): String? {
        return null
    }
}
