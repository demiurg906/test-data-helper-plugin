package org.jetbrains.kotlin.test.helper

import com.intellij.ide.actions.runAnything.RunAnythingAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import org.jetbrains.plugins.gradle.action.GradleExecuteTaskAction
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
        var processingFirst: Boolean = true
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
}

fun VirtualFile.getTestDataType(project: Project): TestDataType? {
    val configuration = TestDataPathsConfiguration.getInstance(project)
    val filePath = path
    if (configuration.testDataDirectories.any { filePath.startsWith(it) }) return TestDataType.Directory
    if (extension !in supportedExtensions) return null
    if (configuration.testDataFiles.any { filePath.startsWith(it) }) return TestDataType.File
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
            !subSequence(dotIndex + 1, dotPreviousIndex).all { it.isDigit() }
        )

        return substring(0, dotPreviousIndex)
    }

val VirtualFile.nameWithoutAllExtensions get() = name.asPathWithoutAllExtensions

fun runGradleCommandLine(
    e: AnActionEvent,
    fullCommandLine: String
) {
    val project = e.project ?: return
    GradleExecuteTaskAction.runGradle(
        /* project = */ project,
        /* executor = */ RunAnythingAction.EXECUTOR_KEY.getData(e.dataContext),
        /* workDirectory = */ project.basePath ?: "",
        /* fullCommandLine = */ fullCommandLine
    )
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