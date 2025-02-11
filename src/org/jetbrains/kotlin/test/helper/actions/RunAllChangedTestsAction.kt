package org.jetbrains.kotlin.test.helper.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.vcs.changes.ChangeListManager
import org.jetbrains.kotlin.test.helper.services.TestDataRunnerService

class RunAllChangedTestsAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val changedFiles = ChangeListManager.getInstance(project).affectedFiles

        project.service<TestDataRunnerService>().collectAndRunAllTests(e, changedFiles)
    }
}