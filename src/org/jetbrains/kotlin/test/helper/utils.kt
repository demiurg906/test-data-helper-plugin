package org.jetbrains.kotlin.test.helper

import com.intellij.openapi.vfs.VirtualFile
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.Path
import kotlin.io.path.pathString
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

fun <T : Any> lazyVar(init: () -> T) : ReadWriteProperty<Any?, T> {
    return object : ReadWriteProperty<Any?, T> {
        private var value: T? = null

        override fun getValue(thisRef: Any?, property: KProperty<*>): T {
            if (value == null) {
                value = init()
            }
            return value!!
        }

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            this.value = value
        }
    }
}

private val DIGIT_REGEX = """\d+""".toRegex()

@OptIn(ExperimentalStdlibApi::class)
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
            override fun visitFile(file: Path, attrs: BasicFileAttributes?): FileVisitResult {
                if (pathMatcher.matches(file))
                    run(file)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException?): FileVisitResult {
                return FileVisitResult.CONTINUE
            }
        }
    )
}
