package org.jetbrains.plugins.template.ui.editor

import com.intellij.ide.ui.fullRow
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.observable.properties.GraphPropertyImpl.Companion.graphProperty
import com.intellij.openapi.observable.properties.PropertyGraph
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.layout.panel

class MyAwesomeConfigurable: BoundConfigurable("My Awesome Plugin Settings", "Tools.MyAwesomePluginSettings") {
    private val settings
        get() = MyAwesomeSettings.getInstance()

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
