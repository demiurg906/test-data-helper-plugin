package org.jetbrains.kotlin.test.helper

import com.intellij.ide.ui.fullRow
import com.intellij.openapi.observable.properties.GraphPropertyImpl.Companion.graphProperty
import com.intellij.openapi.observable.properties.PropertyGraph
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.layout.panel

class PluginSettings: BoundConfigurable("Kotlin TestData Plugin Settings", "Tools.KotlinTestDataPluginSettings") {
    private val settings
        get() = TestDataPluginSettings.getInstance()

    private val propertyGraph = PropertyGraph()
    private val pathsProperty = propertyGraph.graphProperty { settings.paths ?: "" }

    override fun createPanel(): DialogPanel {
        return panel {
            fullRow {
                textField(property = pathsProperty).apply {
                    onIsModified { pathsProperty.get() != (settings.paths ?: "") }
                    onReset { pathsProperty.set(settings.paths ?: "") }
                    onApply { settings.paths = pathsProperty.get().takeIf { it.isNotEmpty() } }
                }
            }
        }
    }
}
