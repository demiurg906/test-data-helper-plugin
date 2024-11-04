package org.jetbrains.kotlin.test.helper.ui.settings

import javax.swing.table.AbstractTableModel

abstract class TwoColumnTableModel<T : Any, R : Any>(
    val data: MutableList<Pair<T, R>>,
    val names: Array<String>
) : AbstractTableModel() {
    init {
        require(names.size == 2)
    }

    protected abstract fun T.presentableFirst(): String
    protected abstract fun R.presentableSecond(): String

    protected abstract fun parseFirst(oldValue: T, newValue: String): T?
    protected abstract fun parseSecond(oldValue: R, newValue: String): R?


    override fun getColumnCount(): Int {
        return names.size
    }

    override fun getRowCount(): Int {
        return data.size
    }

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any? {
        val (first, second) = data[rowIndex]
        return when (columnIndex) {
            0 -> first.presentableFirst()
            1 -> second.presentableSecond()
            else -> null
        }
    }

    override fun getColumnName(column: Int): String {
        return names[column]
    }

    override fun getColumnClass(columnIndex: Int): Class<*> {
        return String::class.java
    }

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean {
        return true
    }

    override fun setValueAt(aValue: Any, row: Int, col: Int) {
        if (aValue !is String) return
        val (first, second) = data[row]

        data[row] = when (col) {
            0 -> {
                val newFirstValue = parseFirst(first, aValue) ?: return
                Pair(newFirstValue, second)
            }

            1 -> {
                val newSecondValue = parseSecond(second, aValue) ?: return
                Pair(first, newSecondValue)
            }

            else -> error("Unexpected column index: $col")
        }
    }
}
