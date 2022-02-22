package org.jetbrains.kotlin.test.helper

import com.intellij.ide.IdeBundle
import com.intellij.openapi.compiler.JavaCompilerBundle
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.Logger
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
import com.intellij.ui.components.fields.ExpandableTextField
import com.intellij.ui.layout.fullRow
import com.intellij.ui.layout.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.execution.ParametersListUtil
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.util.*
import javax.swing.*
import javax.swing.table.*
import kotlin.io.path.Path
import kotlin.io.path.pathString
import kotlin.io.path.relativeTo

@State(name = "TestDataPluginSettings", storages = [(Storage("kotlinTestDataPluginTestDataPaths.xml"))])
class TestDataPathsConfiguration : PersistentStateComponent<TestDataPathsConfiguration> {
    companion object {
        fun getInstance(project: Project): TestDataPathsConfiguration {
            return project.getService(TestDataPathsConfiguration::class.java)
        }

        private val logger = Logger.getInstance(TestDataPathsConfiguration::class.java)
    }

    var testDataFiles: Array<String> = emptyArray()

    var relatedFilesSearchPaths: Map<String, Array<String>> = emptyMap()

    override fun getState(): TestDataPathsConfiguration {
        return this
    }

    override fun loadState(state: TestDataPathsConfiguration) {
        loadState(state.testDataFiles, state.relatedFilesSearchPaths)
    }

    fun loadState(newTestDataFiles: List<VirtualFile>, newRelatedFilesSearchPaths: List<Pair<VirtualFile, List<String>>>) {
        loadState(
            newTestDataFiles.map { it.path }.toTypedArray(),
            newRelatedFilesSearchPaths.associateBy(
                keySelector = { it.first.path },
                valueTransform = { it.second.toTypedArray() }
            )
        )
    }

    private fun loadState(newTestDataFiles: Array<String>, newRelatedFilesSearchPaths: Map<String, Array<String>>) {
        testDataFiles = newTestDataFiles.copyOf()
        relatedFilesSearchPaths = newRelatedFilesSearchPaths.toMap()
    }

    /**
     * Iterate over files in [relatedFilesSearchPaths] related to [baseFile].
     *
     * The [run] closure accepts a search pattern and returns a [Boolean] indicating whether to abort iteration,
     * or continue.
     *
     * @return true if [run] returned true, otherwise false.
     */
    private fun searchRelatedFiles(
        baseFile: VirtualFile,
        simpleNameUntilFirstDot: String,
        run: (String) -> Boolean
    ): Boolean {
        for ((testDataSubdir, searchPaths) in relatedFilesSearchPaths) {
            try {
                val testDataSubdirPath = Path(testDataSubdir)
                val baseFilePath = baseFile.toNioPath()

                if (!baseFilePath.startsWith(testDataSubdirPath))
                    continue

                for (searchPath in searchPaths) {
                    val testDataFileReplacement = baseFilePath.relativeTo(testDataSubdirPath).parent
                        ?.resolve(simpleNameUntilFirstDot)?.pathString
                        ?: simpleNameUntilFirstDot
                    val actualSearchPath = searchPath.replace("\$TEST_DATA_FILE\$", testDataFileReplacement).trim()

                    if (run(actualSearchPath))
                        return true
                }
            } catch (e: IllegalArgumentException) {
                logger.warn(e)
                continue
            }
        }
        return false
    }


    fun isFileRelated(baseFile: VirtualFile, fileToCheck: VirtualFile): Boolean =
        searchRelatedFiles(baseFile, baseFile.simpleNameUntilFirstDot) { searchPattern ->
            baseFile.toNioPath().fileSystem.getPathMatcher("glob:$searchPattern")
                .matches(fileToCheck.toNioPath())
        }

    fun findAdditionalRelatedFiles(
        baseFile: VirtualFile,
        simpleNameUntilFirstDot: String,
    ): List<VirtualFile> {
        val relatedFiles = mutableListOf<VirtualFile>()
        searchRelatedFiles(baseFile, simpleNameUntilFirstDot) { searchPattern ->
            glob(searchPattern) {
                baseFile.fileSystem.findFileByPath(it.pathString)?.let(relatedFiles::add)
            }
            false
        }
        return relatedFiles
    }
}

class TestDataPathsConfigurable(private val project: Project) : BoundConfigurable("Kotlin TestData Plugin Settings", "Tools.KotlinTestDataPluginSettings") {

    private val configuration: TestDataPathsConfiguration = TestDataPathsConfiguration.getInstance(project)

    private val testDataFiles: MutableList<VirtualFile> = run {
        val fileSystem = VirtualFileManager.getInstance().getFileSystem("file")
        configuration.testDataFiles.mapNotNullTo(mutableListOf()) { fileSystem.findFileByPath(it) }
    }

    private val relatedFileSearchPaths: MutableList<Pair<VirtualFile, List<String>>> = run {
        val fileSystem = VirtualFileManager.getInstance().getFileSystem("file")
        configuration.relatedFilesSearchPaths.mapNotNullTo(mutableListOf()) { entry ->
            fileSystem.findFileByPath(entry.key)?.let { Pair(it, entry.value.toList()) }
        }
    }

    private val testDataPathPanel: TestDataPathEntriesPanel by lazy {
        TestDataPathEntriesPanel()
    }

    private val relatedFilesSearchPathsPanel: RelatedFileSearchPathsPanel by lazy {
        RelatedFileSearchPathsPanel()
    }

    override fun createPanel(): DialogPanel {
        return panel {
            fullRow {
                component(testDataPathPanel)
            }
            fullRow {
                component(relatedFilesSearchPathsPanel)
            }
        }
    }

    private fun testDataFilesModified(): Boolean {
        val filesFromConfiguration = configuration.testDataFiles
        if (filesFromConfiguration.size != testDataFiles.size) return true
        return filesFromConfiguration.zip(testDataFiles).all { it.first == it.second.path }
    }

    private fun relatedFilesSearchPathsModified(): Boolean {
        val pathsFromConfiguration = configuration.relatedFilesSearchPaths
        if (pathsFromConfiguration.size != relatedFileSearchPaths.size) return true
        return pathsFromConfiguration.asSequence().zip(relatedFileSearchPaths.asSequence())
            .all { it.first.key == it.second.first.path && it.first.value.toList() == it.second.second }
    }

    override fun isModified() = testDataFilesModified() || relatedFilesSearchPathsModified()

    override fun apply() {
        configuration.loadState(testDataFiles, relatedFileSearchPaths)
    }

    private inner class TestDataPathEntriesPanel : TestDataPluginSettingsPanel(project) {

        override val numberOfFiles: Int
            get() = testDataFiles.size

        override fun addFile(index: Int, file: VirtualFile) {
            testDataFiles.add(index, file)
        }

        override fun isFileExcluded(file: VirtualFile): Boolean = file in testDataFiles

        override fun removeFileAt(index: Int) {
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
                .setAddAction { addPath() }
                .setRemoveAction { removePaths() }.createPanel()
        }

        init {
            initPanel()
        }
    }

    private inner class RelatedFileSearchPathsPanel : TestDataPluginSettingsPanel(project) {

        override val numberOfFiles: Int
            get() = relatedFileSearchPaths.size

        override fun addFile(index: Int, file: VirtualFile) {
            relatedFileSearchPaths.add(index, Pair(file, mutableListOf()))
        }

        override fun removeFileAt(index: Int) {
            relatedFileSearchPaths.removeAt(index)
        }

        override fun isFileExcluded(file: VirtualFile): Boolean =
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
                .setAddAction { addPath() }
                .setRemoveAction { removePaths() }.createPanel()
        }

        init {
            initPanel()
        }
    }
}

private abstract class TestDataPluginSettingsPanel(private val project: Project): PanelWithButtons() {

    companion object {
        private val fileChooserDescriptor: FileChooserDescriptor = FileChooserDescriptor(
            true,
            true,
            false,
            false,
            false,
            true
        )
    }

    protected lateinit var myRemoveButton: JButton
    protected lateinit var myExcludedTable: JBTable

    override fun getLabelText(): String? {
        return null
    }

    override fun createButtons(): Array<JButton> {
        val addButton = JButton(JavaCompilerBundle.message("button.add"))
        addButton.addActionListener { addPath() }
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

    protected abstract val numberOfFiles: Int

    protected abstract fun addFile(index: Int, file: VirtualFile)

    protected abstract fun removeFileAt(index: Int)

    protected abstract fun isFileExcluded(file: VirtualFile): Boolean

    protected fun addPath() {
        var selected: Int = numberOfFiles
        val savedSelected = selected
        val chosen = FileChooser.chooseFiles(fileChooserDescriptor, project, null)
        for (chosenFile in chosen) {
            if (isFileExcluded(chosenFile)) {
                continue
            }

            addFile(selected, chosenFile)
            selected++
        }
        if (selected > savedSelected) { // actually added something
            val model = myExcludedTable.model as AbstractTableModel
            model.fireTableRowsInserted(savedSelected, selected - 1)
            myExcludedTable.setRowSelectionInterval(savedSelected, selected - 1)
        }
    }

    protected fun removePaths() {
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
            removeFileAt(row)
            model.fireTableRowsDeleted(row, row)
            removedCount += 1
        }
        if (indexToSelect >= numberOfFiles) {
            indexToSelect = numberOfFiles - 1
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

    protected fun JBTable.configure(names: Array<String>, renderer: TableCellRenderer) {
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
}

private class ExpandableCellEditor: AbstractCellEditor(), TableCellEditor {

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

private class FilePathRenderer(private val fileForRow: (Int) -> VirtualFile) : DefaultTableCellRenderer() {
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
