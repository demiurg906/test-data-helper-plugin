package org.jetbrains.kotlin.test.helper.ui.settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.table.JBTable
import com.intellij.util.execution.ParametersListUtil
import org.jetbrains.kotlin.test.helper.PluginSettingsState
import javax.swing.DefaultCellEditor
import javax.swing.JComponent
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableCellEditor
import javax.swing.table.TableModel

class RelatedFileSearchPathsPanel(project: Project, private val state: PluginSettingsState) : FileSettingsPanel(project) {
    private val relatedFileSearchPaths: MutableList<Pair<VirtualFile, List<String>>>
        get() = state.relatedFileSearchPaths

    override val numberOfElements: Int
        get() = relatedFileSearchPaths.size

    override fun addElement(index: Int, file: VirtualFile) {
        relatedFileSearchPaths.add(index, Pair(file, mutableListOf()))
    }

    override fun removeElementAt(index: Int) {
        relatedFileSearchPaths.removeAt(index)
    }

    override fun isElementExcluded(file: VirtualFile): Boolean =
        relatedFileSearchPaths.find { it.first == file } != null

    override fun createMainComponent(): JComponent {
        val names = arrayOf(
            "Test files",
            "Where to search for related files (wildcards are supported)"
        )
        // Create a model of the data.
        val dataModel: TableModel = object : AbstractTableModel() {

            override fun getColumnCount(): Int {
                return names.size
            }

            override fun getRowCount(): Int {
                return relatedFileSearchPaths.size
            }

            override fun getValueAt(rowIndex: Int, columnIndex: Int): Any? {
                val (testDir, searchPatterns) = relatedFileSearchPaths[rowIndex]
                return when (columnIndex) {
                    0 -> testDir.presentableUrl
                    1 -> ParametersListUtil.join(searchPatterns)
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
                val (testDir, searchPatterns) = relatedFileSearchPaths[row]

                relatedFileSearchPaths[row] = when (col) {
                    0 -> {
                        val fileSystem = testDir.fileSystem
                        val newTestDir = fileSystem.findFileByPath(aValue) ?: return
                        Pair(newTestDir, searchPatterns)
                    }

                    1 -> {
                        Pair(testDir, ParametersListUtil.parse(aValue))
                    }

                    else -> throw IllegalArgumentException()
                }
            }
        }

        val expandableCellEditor = ExpandableCellEditor()

        myExcludedTable = object : JBTable(dataModel) {
            override fun getCellEditor(row: Int, column: Int): TableCellEditor {
                if (column == 1) return expandableCellEditor
                return super.getCellEditor(row, column)
            }
        }.apply {
            configure(names, FilePathRenderer { relatedFileSearchPaths[it].first })
        }

        val defaultEditor = myExcludedTable.getDefaultEditor(String::class.java)
        if (defaultEditor is DefaultCellEditor) {
            defaultEditor.clickCountToStart = 1
        }

        return ToolbarDecorator.createDecorator(myExcludedTable)
            .disableUpAction()
            .disableDownAction()
            .setAddAction { onAddClick() }
            .setRemoveAction { onRemoveClick() }.createPanel()
    }

    init {
        initPanel()
    }
}
