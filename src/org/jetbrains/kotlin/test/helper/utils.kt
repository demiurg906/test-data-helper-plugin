package org.jetbrains.kotlin.test.helper

import com.intellij.openapi.vfs.VirtualFile
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
