package org.jetbrains.kotlin.test.helper.ui.settings

import com.intellij.openapi.compiler.JavaCompilerBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.table.JBTable
import org.jetbrains.kotlin.test.helper.PluginSettingsState
import javax.swing.DefaultCellEditor
import javax.swing.JComponent
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableModel

class TestDataPathEntriesPanel(project: Project, val state: PluginSettingsState) : FileSettingsPanel(project) {
    private val testDataFiles: MutableList<VirtualFile>
        get() = state.testDataFiles

    override val numberOfElements: Int
        get() = testDataFiles.size

    override fun addElement(index: Int, element: VirtualFile) {
        testDataFiles.add(index, element)
    }

    override fun isElementExcluded(element: VirtualFile): Boolean = element in testDataFiles

    override fun removeElementAt(index: Int) {
        testDataFiles.removeAt(index)
    }

    override fun createMainComponent(): JComponent {
        val names = arrayOf(
            JavaCompilerBundle.message("exclude.from.compile.table.path.column.name"),
//                JavaCompilerBundle.message("exclude.from.compile.table.recursively.column.name")
        )
        // Create a model of the data.
        val dataModel: TableModel = object : AbstractTableModel() {
            override fun getColumnCount(): Int {
                return names.size
            }

            override fun getRowCount(): Int {
                return testDataFiles.size
            }

            override fun getValueAt(row: Int, col: Int): Any? {
                val file = testDataFiles[row]
                if (col == 0) {
                    return file.presentableUrl
                }
                return null
            }

            override fun getColumnName(column: Int): String {
                return names[column]
            }

            override fun getColumnClass(c: Int): Class<*>? {
                if (c == 0) {
                    return String::class.java
                }
                return if (c == 1) {
                    Boolean::class.java
                } else null
            }

            override fun isCellEditable(row: Int, col: Int): Boolean {
                return true
            }

            override fun setValueAt(aValue: Any, row: Int, col: Int) {
                if (col != 0) return
                val fileSystem = testDataFiles[row].fileSystem
                val path = aValue as String
                val newFile = fileSystem.findFileByPath(path) ?: return
                testDataFiles[row] = newFile
            }
        }
        myExcludedTable = JBTable(dataModel).apply {
            configure(names, FilePathRenderer(testDataFiles::get))
        }
        val editor = myExcludedTable.getDefaultEditor(String::class.java)
        if (editor is DefaultCellEditor) {
            editor.clickCountToStart = 1
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
