package org.jetbrains.kotlin.test.helper

import com.intellij.openapi.compiler.JavaCompilerBundle
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.execution.ParametersListUtil
import org.jetbrains.kotlin.test.helper.ui.settings.ExpandableCellEditor
import org.jetbrains.kotlin.test.helper.ui.settings.FilePathRenderer
import org.jetbrains.kotlin.test.helper.ui.settings.FileSettingsPanel
import javax.swing.DefaultCellEditor
import javax.swing.JComponent
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableCellEditor
import javax.swing.table.TableModel
import kotlin.io.path.Path
import kotlin.io.path.pathString
import kotlin.io.path.relativeTo

@Service(Service.Level.PROJECT)
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

    fun loadState(
        newTestDataFiles: List<VirtualFile>,
        newRelatedFilesSearchPaths: List<Pair<VirtualFile, List<String>>>
    ) {
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

class TestDataPathsConfigurable(private val project: Project) :
    BoundConfigurable(MyBundle.message("pluginSettingsDisplayName"), "Tools.KotlinTestDataPluginSettings") {

    private val configuration: TestDataPathsConfiguration = TestDataPathsConfiguration.getInstance(project)

    private fun resetTestDataFiles(): MutableList<VirtualFile> {
        val fileSystem = VirtualFileManager.getInstance().getFileSystem("file")
        return configuration.testDataFiles.mapNotNullTo(mutableListOf()) { fileSystem.findFileByPath(it) }
    }

    private var testDataFiles: MutableList<VirtualFile> = resetTestDataFiles()

    private fun resetRelatedFileSearchPaths(): MutableList<Pair<VirtualFile, List<String>>> {
        val fileSystem = VirtualFileManager.getInstance().getFileSystem("file")
        return configuration.relatedFilesSearchPaths.mapNotNullTo(mutableListOf()) { entry ->
            fileSystem.findFileByPath(entry.key)?.let { Pair(it, entry.value.toList()) }
        }
    }

    private var relatedFileSearchPaths: MutableList<Pair<VirtualFile, List<String>>> = resetRelatedFileSearchPaths()

    private val testDataPathPanel: TestDataPathEntriesPanel by lazy {
        TestDataPathEntriesPanel()
    }

    private val relatedFilesSearchPathsPanel: RelatedFileSearchPathsPanel by lazy {
        RelatedFileSearchPathsPanel()
    }

    override fun createPanel(): DialogPanel {
        return panel {
            row("Test data directories:") {}
            row {
                cell(testDataPathPanel)
                    .align(AlignX.FILL)
            }
            separator()
            row("Related file search paths:") {}
            row {
                cell(relatedFilesSearchPathsPanel)
                    .align(AlignX.FILL)
            }
        }
    }

    private fun testDataFilesModified(): Boolean {
        val filesFromConfiguration = configuration.testDataFiles
        if (filesFromConfiguration.size != testDataFiles.size) return true
        return filesFromConfiguration.zip(testDataFiles).any { it.first != it.second.path }
    }

    private fun relatedFilesSearchPathsModified(): Boolean {
        val pathsFromConfiguration = configuration.relatedFilesSearchPaths
        if (pathsFromConfiguration.size != relatedFileSearchPaths.size) return true
        return pathsFromConfiguration.asSequence().zip(relatedFileSearchPaths.asSequence())
            .any { it.first.key != it.second.first.path || it.first.value.toList() != it.second.second }
    }

    override fun isModified() = testDataFilesModified() || relatedFilesSearchPathsModified()

    override fun reset() {
        super.reset()
        testDataFiles = resetTestDataFiles()
        relatedFileSearchPaths = resetRelatedFileSearchPaths()
        (testDataPathPanel.myExcludedTable.model as? AbstractTableModel)?.fireTableDataChanged()
        (relatedFilesSearchPathsPanel.myExcludedTable.model as? AbstractTableModel)?.fireTableDataChanged()
    }

    override fun apply() {
        super.apply()
        configuration.loadState(testDataFiles, relatedFileSearchPaths)
    }

    private inner class TestDataPathEntriesPanel : FileSettingsPanel(project) {
        override val numberOfElements: Int
            get() = testDataFiles.size

        override fun addElement(index: Int, file: VirtualFile) {
            testDataFiles.add(index, file)
        }

        override fun isElementExcluded(file: VirtualFile): Boolean = file in testDataFiles

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

    private inner class RelatedFileSearchPathsPanel : FileSettingsPanel(project) {
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
}


