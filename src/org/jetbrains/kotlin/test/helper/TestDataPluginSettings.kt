package org.jetbrains.kotlin.test.helper

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
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import org.jetbrains.kotlin.test.helper.ui.settings.RelatedFileSearchPathsPanel
import org.jetbrains.kotlin.test.helper.ui.settings.TestDataPathEntriesPanel
import javax.swing.table.AbstractTableModel
import kotlin.io.path.Path
import kotlin.io.path.pathString
import kotlin.io.path.relativeTo

class PluginSettingsState(
    var testDataFiles: MutableList<VirtualFile>,
    var relatedFileSearchPaths: MutableList<Pair<VirtualFile, List<String>>>
)

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

    // -------------------------------- state --------------------------------

    private val state: PluginSettingsState = PluginSettingsState(
        testDataFiles = resetTestDataFiles(),
        relatedFileSearchPaths = resetRelatedFileSearchPaths(),
    )

    private var testDataFiles: MutableList<VirtualFile>
        get() = state.testDataFiles
        set(value) { state.testDataFiles = value }

    private var relatedFileSearchPaths: MutableList<Pair<VirtualFile, List<String>>>
        get() = state.relatedFileSearchPaths
        set(value) { state.relatedFileSearchPaths = value }

    // -------------------------------- state initialization --------------------------------

    private fun resetTestDataFiles(): MutableList<VirtualFile> {
        val fileSystem = VirtualFileManager.getInstance().getFileSystem("file")
        return configuration.testDataFiles.mapNotNullTo(mutableListOf()) { fileSystem.findFileByPath(it) }
    }

    private fun resetRelatedFileSearchPaths(): MutableList<Pair<VirtualFile, List<String>>> {
        val fileSystem = VirtualFileManager.getInstance().getFileSystem("file")
        return configuration.relatedFilesSearchPaths.mapNotNullTo(mutableListOf()) { entry ->
            fileSystem.findFileByPath(entry.key)?.let { Pair(it, entry.value.toList()) }
        }
    }

    // -------------------------------- panels --------------------------------

    private val testDataPathPanel: TestDataPathEntriesPanel by lazy {
        TestDataPathEntriesPanel(project, state)
    }

    private val relatedFilesSearchPathsPanel: RelatedFileSearchPathsPanel by lazy {
        RelatedFileSearchPathsPanel(project, state)
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

    // -------------------------------- other stuff --------------------------------

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

    override fun isModified(): Boolean {
        return testDataFilesModified() || relatedFilesSearchPathsModified()
    }

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
}
