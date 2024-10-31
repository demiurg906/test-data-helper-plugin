package org.jetbrains.kotlin.test.helper.ui.settings

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

abstract class FileSettingsPanel(private val project: Project) : AbstractSettingsPanel<VirtualFile>() {
    companion object {
        private val fileChooserDescriptor: FileChooserDescriptor = FileChooserDescriptor(
            /* chooseFiles = */ true,
            /* chooseFolders = */ true,
            /* chooseJars = */ false,
            /* chooseJarsAsFiles = */ false,
            /* chooseJarContents = */ false,
            /* chooseMultiple = */ true
        )
    }

    override fun createNewElementsOnAddClick(): List<VirtualFile> {
        return FileChooser.chooseFiles(fileChooserDescriptor, project, /* toSelect = */ null).toList()
    }
}
