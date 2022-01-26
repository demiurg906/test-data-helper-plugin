package org.jetbrains.kotlin.test.helper

import com.intellij.ide.IdeBundle
import com.intellij.openapi.compiler.JavaCompilerBundle
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.ui.JBColor
import com.intellij.ui.PanelWithButtons
import com.intellij.ui.RightAlignedLabelUI
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.layout.fullRow
import com.intellij.ui.layout.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.util.*
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableModel

@State(name = "TestDataPluginSettings", storages = [(Storage("kotlinTestDataPluginTestDataPaths.xml"))])
class TestDataPathsConfiguration : PersistentStateComponent<TestDataPathsConfiguration> {
    companion object {
        fun getInstance(project: Project): TestDataPathsConfiguration {
            return project.getService(TestDataPathsConfiguration::class.java)
        }
    }

    var testDataFiles: Array<String> = emptyArray()

    override fun getState(): TestDataPathsConfiguration {
        return this
    }

    override fun loadState(state: TestDataPathsConfiguration) {
        loadState(state.testDataFiles)
    }

    fun loadState(newTestDataFiles: Array<VirtualFile>) {
        loadState(newTestDataFiles.map { it.path }.toTypedArray())
    }

    private fun loadState(newTestDataFiles: Array<String>) {
        testDataFiles = newTestDataFiles.copyOf()
    }
}

class TestDataPathsConfigurable(private val project: Project) : BoundConfigurable("Kotlin TestData Plugin Settings", "Tools.KotlinTestDataPluginSettings") {
    private val fileChooserDescriptor: FileChooserDescriptor = FileChooserDescriptor(
        true,
        true,
        false,
        false,
        false,
        true
    )

    private val configuration: TestDataPathsConfiguration = TestDataPathsConfiguration.getInstance(project)

    private val testDataFiles: MutableList<VirtualFile> = run {
        val fileSystem = VirtualFileManager.getInstance().getFileSystem("file")
        configuration.testDataFiles.mapNotNullTo(mutableListOf()) { fileSystem.findFileByPath(it) }
    }

    private val testDataPathPanel: TestDataPathEntriesPanel by lazy {
        TestDataPathEntriesPanel()
    }

    override fun createPanel(): DialogPanel {
        return panel {
            fullRow {
                component(testDataPathPanel)
            }
        }
    }

    override fun isModified(): Boolean {
        val filesFromConfiguration = configuration.testDataFiles
        if (filesFromConfiguration.size != testDataFiles.size) return true
        return filesFromConfiguration.zip(testDataFiles).all { it.first == it.second.path }
    }

    override fun apply() {
        configuration.loadState(testDataFiles.toTypedArray())
    }

    private inner class TestDataPathEntriesPanel : PanelWithButtons() {
        private lateinit var myRemoveButton: JButton
        private lateinit var myExcludedTable: JBTable

        override fun getLabelText(): String? {
            return null
        }

        override fun createButtons(): Array<JButton> {
            val addButton = JButton(JavaCompilerBundle.message("button.add"))
            addButton.addActionListener { addPath(fileChooserDescriptor) }
            myRemoveButton = JButton(IdeBundle.message("button.remove")).apply {
                addActionListener { removePaths() }
                isEnabled = false
                model.addChangeListener {
                    if (myExcludedTable.selectedRow == -1) {
                        isEnabled = false
                    }
                }
            }
            return arrayOf()
        }

        private fun addPath(descriptor: FileChooserDescriptor) {
            var selected: Int = testDataFiles.size
            val savedSelected = selected
            val chosen = FileChooser.chooseFiles(descriptor, project, null)
            for (chosenFile in chosen) {
                if (isFileExcluded(chosenFile)) {
                    continue
                }
                testDataFiles.add(selected, chosenFile)
                selected++
            }
            if (selected > savedSelected) { // actually added something
                val model = myExcludedTable.model as AbstractTableModel
                model.fireTableRowsInserted(savedSelected, selected - 1)
                myExcludedTable.setRowSelectionInterval(savedSelected, selected - 1)
            }
        }

        private fun isFileExcluded(file: VirtualFile): Boolean {
            return file in testDataFiles
        }

        private fun removePaths() {
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
                testDataFiles.removeAt(row)
                model.fireTableRowsDeleted(row, row)
                removedCount += 1
            }
            if (indexToSelect >= testDataFiles.size) {
                indexToSelect = testDataFiles.size - 1
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
                    val file = testDataFiles.get(row)
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
                    testDataFiles.set(row, newFile)

                }
            }
            myExcludedTable = JBTable(dataModel).apply {
                setShowGrid(false)
                setEnableAntialiasing(true)
                emptyText.setText(JavaCompilerBundle.message("no.excludes"))
                preferredScrollableViewportSize = JBUI.size(300, -1)
                visibleRowCount = 6
                setDefaultRenderer(Any::class.java, MyObjectRenderer())
                getColumn(names[0]).preferredWidth = 350
                selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
                selectionModel.addListSelectionListener {
                    myRemoveButton.isEnabled = selectedRow >= 0
                }
            }
            val editor = myExcludedTable.getDefaultEditor(String::class.java)
            if (editor is DefaultCellEditor) {
                editor.clickCountToStart = 1
            }
            return ToolbarDecorator.createDecorator(myExcludedTable)
                .disableUpAction()
                .disableDownAction()
                .setAddAction { addPath(fileChooserDescriptor) }
                .setRemoveAction { removePaths() }.createPanel()
        }

        init {
            initPanel()
        }
    }

    private inner class MyObjectRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ): Component {
            val file = testDataFiles[row]
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
}

