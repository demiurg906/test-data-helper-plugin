package org.jetbrains.kotlin.test.helper.actions

import com.intellij.execution.testframework.AbstractTestProxy
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.writeText
import java.nio.file.Paths

internal class ApplyFileDiffAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val presentation = e.presentation
        if (e.project == null) {
            presentation.isEnabledAndVisible = false
        } else {
            val context = e.dataContext
            val tests = AbstractTestProxy.DATA_KEYS.getData(context).orEmpty()
            presentation.isEnabledAndVisible = tests.any { it.leafDiffViewerProvider != null }
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val tests = AbstractTestProxy.DATA_KEYS.getData(e.dataContext) ?: return

        val diffs = tests.flatMap { test ->
            if (test.isLeaf) {
                test.diffViewerProviders
            } else {
                test.children.flatMap { it.diffViewerProviders }
            }
        }.distinct()

        WriteAction.run<Throwable> {
            for (diff in diffs) {
                val filePath = diff.filePath ?: continue
                val file = VfsUtil.findFile(Paths.get(filePath), true) ?: continue
                file.writeText(diff.right)
            }
        }
    }
}