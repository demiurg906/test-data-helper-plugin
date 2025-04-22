package org.jetbrains.kotlin.test.helper

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.Path
import kotlin.io.path.pathString

private val DIGIT_REGEX = """\d+""".toRegex()

val VirtualFile.simpleNameUntilFirstDot: String
    get() {
        var processingFirst = true
        val parts = buildList {
            for (part in name.split(".")) {
                val isNumber = DIGIT_REGEX.matches(part)
                if (processingFirst) {
                    add(part)
                    processingFirst = false
                    continue
                }
                if (!isNumber) {
                    break
                }
                add(part)
            }
        }
        return parts.joinToString(".")
    }

private val wildcardPattern = Regex("[{?*\\[]")

fun glob(searchPattern: String, run: (Path) -> Unit) {
    var prefixWithoutWildcards = Path(searchPattern)
    var suffixWithWildcards = Path("")
    while (prefixWithoutWildcards.pathString.contains(wildcardPattern)) {
        suffixWithWildcards = prefixWithoutWildcards.fileName.resolve(suffixWithWildcards)
        prefixWithoutWildcards = prefixWithoutWildcards.parent
    }
    val pathMatcher = prefixWithoutWildcards.fileSystem.getPathMatcher("glob:$searchPattern")

    Files.walkFileTree(
        prefixWithoutWildcards,
        emptySet(),
        suffixWithWildcards.nameCount,
        object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (pathMatcher.matches(file))
                    run(file)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                return FileVisitResult.CONTINUE
            }
        }
    )
}

private val supportedExtensions = listOf("kt", "kts", "args", "nkt")

enum class TestDataType {
    File,
    Directory,
    DirectoryOfFiles,
}

fun VirtualFile.getTestDataType(project: Project): TestDataType? {
    val configuration = TestDataPathsConfiguration.getInstance(project)
    if (configuration.testDataDirectories.any { path.startsWith(it) }) return TestDataType.Directory
    if (configuration.testDataFiles.any { path.startsWith(it) }) {
        return when {
            extension in supportedExtensions -> TestDataType.File
            isDirectory -> TestDataType.DirectoryOfFiles
            else -> null
        }
    }
    return null
}

val String.asPathWithoutAllExtensions: String
    get() {
        val separatorLastIndex = lastIndexOf(File.separatorChar)
        var dotPreviousIndex: Int
        var dotIndex = length

        do {
            dotPreviousIndex = dotIndex
            dotIndex = lastIndexOf('.', dotPreviousIndex - 1)
        } while (
            dotIndex > separatorLastIndex && // it also handles `-1`
            !subSequence(dotIndex + 1, dotPreviousIndex).let { it.isNotEmpty() && it.all { c -> c.isDigit() } }
        )

        return substring(0, dotPreviousIndex)
    }

val VirtualFile.nameWithoutAllExtensions get() = name.asPathWithoutAllExtensions

fun runGradleCommandLine(
    e: AnActionEvent,
    fullCommandLine: String,
    debug: Boolean,
    title: String? = e.toFileNamesString()
) {
    val project = e.project ?: return
    val runSettings = createGradleRunAndConfigurationSettings(project, fullCommandLine, title) ?: return

    ProgramRunnerUtil.executeConfiguration(
        runSettings,
        if (debug) DefaultDebugExecutor.getDebugExecutorInstance() else DefaultRunExecutor.getRunExecutorInstance()
    )

    val runManager = RunManager.getInstance(project)
    val existingConfiguration = runManager.findConfigurationByTypeAndName(runSettings.type, runSettings.name)
    if (existingConfiguration == null) {
        runManager.setTemporaryConfiguration(runSettings)
    } else {
        runManager.selectedConfiguration = existingConfiguration
    }
}

fun createGradleRunAndConfigurationSettings(
    project: Project,
    fullCommandLine: String,
    title: String?
): RunnerAndConfigurationSettings? {
    val settings = createGradleExternalSystemTaskExecutionSettings(project, fullCommandLine)
    val runSettings = ExternalSystemUtil.createExternalSystemRunnerAndConfigurationSettings(
        settings,
        project,
        GradleConstants.SYSTEM_ID,
    )

    if (title != null) {
        runSettings?.name = title
    }
    return runSettings
}

fun createGradleExternalSystemTaskExecutionSettings(
    project: Project,
    fullCommandLine: String
): ExternalSystemTaskExecutionSettings {
    return ExternalSystemTaskExecutionSettings().apply {
        externalProjectPath = project.basePath
        taskNames = fullCommandLine.split(" ")
        externalSystemIdString = GradleConstants.SYSTEM_ID.id
    }
}

fun AnActionEvent.toFileNamesString(): String? {
    return getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        ?.map { it.nameWithoutAllExtensions }?.distinct()
        ?.joinToString(separator = ", ")
}

fun PsiClass.buildRunnerLabel(allTags: Map<String, Array<String>>): String {
    val runnerName = this.name!!
    val tags = allTags.firstNotNullOfOrNull { (pattern, tags) ->
        val patternRegex = pattern.toRegex()
        if (patternRegex.matches(runnerName)) {
            tags.toList()
        } else null
    }.orEmpty()
    return buildString {
        if (tags.isNotEmpty()) {
            this.append(tags.joinToString(prefix = "[", postfix = "] ", separator = ", "))
        }
        this.append(runnerName)
    }
}