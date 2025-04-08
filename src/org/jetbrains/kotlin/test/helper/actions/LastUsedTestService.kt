package org.jetbrains.kotlin.test.helper.actions

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import kotlin.io.path.Path

@Service(Service.Level.PROJECT)
@State(name = "ChosenGeneratedTestCache", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class LastUsedTestService(val project: Project) : PersistentStateComponent<LastUsedTestService> {
    companion object {
        fun getInstance(project: Project): LastUsedTestService {
            return project.service<LastUsedTestService>()
        }
    }

    private val chosenRunnerByDirectory: MutableMap<String, String> = mutableMapOf()

    override fun getState(): LastUsedTestService {
        return this
    }

    override fun loadState(state: LastUsedTestService) {
        this.chosenRunnerByDirectory.clear()
        this.chosenRunnerByDirectory.putAll(state.chosenRunnerByDirectory)
    }

    fun updateChosenRunner(directory: String?, runnerName: String) {
        if (directory == null) return
        chosenRunnerByDirectory[directory] = runnerName
    }

    fun getLastUsedRunnerForFile(testDataFile: VirtualFile): String? {
        val baseDirectory = project.basePath ?: return null
        val virtualFileManager = VirtualFileManager.getInstance()
        return chosenRunnerByDirectory.entries.firstOrNull p@{ (directory, _) ->
            val path = Path(baseDirectory, directory)
            val directoryFile = virtualFileManager.findFileByNioPath(path) ?: return@p false
            VfsUtil.isAncestor(directoryFile, testDataFile, false)
        }?.value
    }
}
