package org.jetbrains.kotlin.test.helper.runAnything

import com.intellij.ide.actions.runAnything.RunAnythingAction.EXECUTOR_KEY
import com.intellij.ide.actions.runAnything.RunAnythingUtil
import com.intellij.ide.actions.runAnything.activity.RunAnythingCommandLineProvider
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.plugins.gradle.action.GradleExecuteTaskAction
import java.io.File
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.WindowManager
import java.awt.Component
import javax.swing.JWindow

private const val COMMAND = "testGlobally"
private const val TESTS = "--tests"
private val QUOTES = listOf('\'', '\"')

private val GRADLE_ARGS = listOf(
    // Proceed to next tasks if some `:test` fails
    "--continue",
    // Suppress possible "No tests found for given includes"
    "-PignoreTestFailures=true",
    // Native by default (and on TC as well) runs tests in parallel in such a way
    // that you can't be sure the output of the given test method really belongs
    // to this specific test.
    // Because of this, you will see way more failed tests than the actual number
    // of problematic ones and won't be able to understand what fails and where.
    "-Pkotlin.internal.native.test.forceStandalone=true",
)
private val TEST_TASK_ARGS = listOf(
    // Avoid `UP_TO_DATE`...
    "--rerun",
)

class TestGloballyRunAnythingProvider : RunAnythingCommandLineProvider() {
    /**
     * Basically a map that transforms the input somehow.
     * The plugin will only ever receive something coming through a successful mapping.
     * Otherwise, we consider the input as not-ours and don't work with it.
     */
    override fun findMatchingValue(dataContext: DataContext, pattern: String): String? =
        pattern.takeIf { COMMAND.startsWith(pattern) || pattern.startsWith(COMMAND) }

    /**
     * Some strange thing that is checked against the input command on whether one
     * is a prefix of the other.
     * See [execute] -> [RunAnythingCommandLineProvider.run] -> [RunAnythingCommandLineProvider.parseCommandLine].
     */
    override fun getHelpCommand(): String = COMMAND

    /**
     * Must be present for [suggestCompletionVariants] to be called.
     */
    override fun getCompletionGroupTitle(): String = "Global Test Runner"

    override fun suggestCompletionVariants(dataContext: DataContext, commandLine: CommandLine): Sequence<String> {
        return when {
            !commandLine.isCorrectSoFar -> emptySequence()
            commandLine.toComplete.suggests(TESTS) -> sequenceOf(TESTS)
            commandLine.toComplete.isEmpty() && commandLine.completedParameters.lastOrNull() != TESTS -> {
                sequenceOf(TESTS)
            }
            else -> sequenceOf()
        }
    }

    private fun String.suggests(another: String): Boolean =
        isNotEmpty() && another.startsWith(this) && another != this

    private val List<String>.isCorrectParametersList: Boolean
        get() = withIndex().all { (index, value) -> index.isOdd || value == TESTS }

    private val CommandLine.isCorrectSoFar: Boolean
        get() = completedParameters.isCorrectParametersList

    private val CommandLine.isCorrectCompletely: Boolean
        get() = parameters.isCorrectParametersList && parameters.size.isEven

    private val Int.isEven get() = this and 1 == 0
    private val Int.isOdd get() = !isEven

    override fun run(dataContext: DataContext, commandLine: CommandLine): Boolean {
        val project = RunAnythingUtil.fetchProject(dataContext)

        if (!commandLine.isCorrectCompletely) {
            return failure("This is not a valid `$COMMAND` call", project)
        }

        val workingDirectory = project.basePath
            ?: return failure("Failed to get the project base path and set up the working directory", project)

        val testFiltersFqNames = commandLine.parameters
            .filterIndexed { index, _ -> index.isOdd }
            .map { it.removeQuotesIfNeeded().removeJUnitDisplayNameIfNeeded() }
        val packagePathsToFilters = testFiltersFqNames.groupBy(::guessPackagePath)
        val moduleToFilters = mapModulesToRelatedFilters(project, packagePathsToFilters)

        val gradleCommand = GRADLE_ARGS + moduleToFilters.entries.flatMap { (module, filters) ->
            listOf(module.gradleSubprojectPath + ":test") + TEST_TASK_ARGS + filters.flatMap { listOf(TESTS, "'$it'") }
        }

        val executor = EXECUTOR_KEY.getData(dataContext)
        GradleExecuteTaskAction.runGradle(project, executor, workingDirectory, gradleCommand.joinToString(" "))
        return true
    }

    private fun mapModulesToRelatedFilters(
        project: Project,
        packagePathsToFilters: Map<String, List<String>>,
    ): MutableMap<Module, MutableSet<String>> {
        val packagePrefixes = packagePathsToFilters.keys.flatMapTo(mutableSetOf(), ::getAllPrefixes)
        val moduleToFilters = mutableMapOf<Module, MutableSet<String>>()

        fun traverseDirectory(directory: VirtualFile, project: Module, pathSoFar: String) {
            if (packagePrefixes.none { it.startsWith(pathSoFar) }) {
                return
            }

            val affectedFilters = packagePathsToFilters.entries
                .filter { (path, _) -> path == pathSoFar }
                .flatMap { (_, filters) -> filters }

            if (affectedFilters.isNotEmpty()) {
                moduleToFilters.getOrPut(project) { mutableSetOf() } += affectedFilters
            }

            for (it in directory.subdirectories) {
                traverseDirectory(it, project, "$pathSoFar/" + it.name)
            }
        }

        val manager = ModuleManager.getInstance(project)
        val modules = manager.modules

        modules.forEach { module ->
            val moduleRootManager = ModuleRootManager.getInstance(module)
            val testSources = moduleRootManager.sourceRoots
                .filter { moduleRootManager.fileIndex.isInTestSourceContent(it) }

            for (source in testSources) {
                traverseDirectory(source, module, pathSoFar = "")
            }
        }

        return moduleToFilters
    }

    /**
     * Returns `:path:to:module`
     */
    private val Module.gradleSubprojectPath: String
        // In kotlin.git, source roots don't reside in the module directly,
        // but rather in either the `main` or `test` submodules.
        // Note that `tests-gen` roots reside in `test` submodules.
        get() = ":" + nameParts.joinToString(":")
            .removePrefix("kotlin:")
            .removeSuffix(":tests")
            .removeSuffix(":test")

    /**
     * Returns [`kotlin`, `path`, `to`, `module`]
     */
    @Suppress("RecursivePropertyAccessor")
    private val Module.nameParts: List<String>
        get() {
            val allParts = name.split(".").takeIf { it.size >= 2 } ?: return listOf(name)
            var ownNamePartsCount = 0
            var parentModule: Module? = null

            while (parentModule == null && ownNamePartsCount < allParts.size) {
                ownNamePartsCount += 1
                val parentPrefix = allParts.dropLast(ownNamePartsCount).joinToString(".")
                val manager = ModuleManager.getInstance(this.project)
                parentModule = manager.findModuleByName(parentPrefix)
            }

            val ownNamePart = allParts.takeLast(ownNamePartsCount).joinToString(".")
            return parentModule?.nameParts.orEmpty() + ownNamePart
        }

    private val VirtualFile.subdirectories get() = children.filter { it.isDirectory }

    private operator fun File.div(name: String) = File(absolutePath + File.separator + name)

    /**
     * Tries to get a package name from a filter value.
     * Assumes that the filter starts with a full package name containing no wildcards.
     *
     * Examples:
     * - `"a.b.C.d"` -> `"/a/b"`
     * - `"a.b"` -> `"/a/b"`
     * - `"C.d"` -> ``
     */
    private fun guessPackagePath(filter: String): String = filter
        .split(".")
        .takeWhile { it.matches("""[a-z]\w*""".toRegex()) }
        .let { listOf("") + it }
        .joinToString("/")

    /**
     * `"/a/b/c"` -> `["/a/b/c", "/a/b", "/a", ""]`
     */
    private fun getAllPrefixes(path: String): List<String> {
        var current = path
        val prefixes = mutableListOf(current)
        var lastDot = current.lastIndexOf('/')

        while (lastDot != -1) {
            current = current.substring(0, lastDot)
            lastDot = current.lastIndexOf('/')
            prefixes += current
        }

        return prefixes
    }

    private fun String.removeQuotesIfNeeded(): String {
        val firstIndex = if (firstOrNull() in QUOTES) 1 else 0
        val lastIndex = if (lastOrNull() in QUOTES) lastIndex else length

        return when {
            firstIndex != 0 || lastIndex != length -> substring(firstIndex, lastIndex)
            else -> this
        }
    }

    /**
     * A countermeasure for functions like: `my.package.path.MyTest.testSomething()(some random commend)`.
     * Despite `@DisplayName` is a JUnit annotation, passing this to `--tests` will produce "No tests found
     * for given includes".
     */
    private fun String.removeJUnitDisplayNameIfNeeded(): String {
        val parenthesesIndex = indexOf('(')

        return when {
            parenthesesIndex != -1 -> substring(0, parenthesesIndex)
            else -> this
        }
    }

    private fun failure(error: String, project: Project) = false.also {
        // There's a bug that the error dialog is partially covered with the
        // Run Anything window, so you can't see it.
        // Let's try closing it eagerly.
        WindowManager.getInstance().getFocusedComponent(project)?.parentOfType<JWindow>()?.dispose()
        Messages.showErrorDialog(error, "Could Not Run Command")
    }

    private inline fun <reified C> Component.parentOfType(): C? {
        var result = parent

        while (result !is C && result != null) {
            result = result.parent
        }

        return result as? C
    }
}
