package org.jetbrains.kotlin.test.helper

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(name = "TestDataPluginSettings", storages = [(Storage("kotlinTestDataPluginSettings.xml"))])
class TestDataPluginSettings : SimplePersistentStateComponent<TestDataPluginSettings.MyState>(MyState()) {
    var paths
        get() = state.paths
        set(value) {
            state.paths = value
        }

    class MyState : BaseState() {
        var paths by string()
    }

    companion object {
        @JvmStatic
        fun getInstance(): TestDataPluginSettings {
            return ApplicationManager.getApplication().getService(TestDataPluginSettings::class.java)
        }
    }
}
