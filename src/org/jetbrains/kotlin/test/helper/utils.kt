package org.jetbrains.kotlin.test.helper

import com.intellij.ide.actions.runAnything.RunAnythingAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
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

private val supportedExtensions = listOf("kt", "kts", "args")

fun VirtualFile.isTestDataFile(project: Project): Boolean {
    if (this.extension !in supportedExtensions) return false
    val configuration = TestDataPathsConfiguration.getInstance(project)
    val filePath = this.path
    return configuration.testDataFiles.any { filePath.startsWith(it) }
}

val String.asPathWithoutAllExtensions: String
    get() {
        val path = if (contains(File.separator)) substringBeforeLast(File.separator) else ""
        val name = substringAfterLast(File.separator)

        val result = buildString {
            if (path.isNotEmpty()) {
                append(path)
                append(File.separator)
            }

            val nameParts = name.split('.').toMutableList()
            nameParts.removeAt(nameParts.lastIndex)

            while (nameParts.size > 1 && !nameParts.last().all { it.isDigit() }) {
                nameParts.removeAt(nameParts.lastIndex)
            }

            nameParts.joinTo(this, separator = ".")
        }
        return result
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