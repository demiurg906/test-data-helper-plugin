package org.jetbrains.plugins.template.ui.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.psi.search.PsiShortNamesCache

@State(name = "MyAwesomeSettings", storages = [(Storage("myAwesomeSettings.xml"))])
class MyAwesomeSettings : SimplePersistentStateComponent<MyAwesomeSettings.MyState>(MyState()) {
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
        fun getInstance(): MyAwesomeSettings {
            return ApplicationManager.getApplication().getService(MyAwesomeSettings::class.java)
        }
    }
}

fun some() {
    //val cache = PsiShortNamesCache.getInstance(null).
    val paths = MyAwesomeSettings.getInstance().paths?.split(';') ?: emptyList()
}