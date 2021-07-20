package org.jetbrains.plugins.template.ui.editor

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import javax.swing.PopupFactory

class MyAwesomeAction: AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        println("Yay")
    }
}