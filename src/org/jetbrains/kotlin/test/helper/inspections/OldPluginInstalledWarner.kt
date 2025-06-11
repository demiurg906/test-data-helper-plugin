package org.jetbrains.kotlin.test.helper.inspections

import com.intellij.ide.plugins.PluginManager
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class OldPluginInstalledWarner : ProjectActivity, DumbAware {
    companion object {
        private val LOG = Logger.getInstance(OldPluginInstalledWarner::class.java)
        private const val GROUP_DISPLAY_ID = "Kotlin Compiler DevKit Notifications"
    }

    override suspend fun execute(project: Project) {
        if (PluginManager.getPlugins().any { it.pluginId.idString  == "org.jetbrains.kotlin.test.helper" }) {
            val message = """"TestHelper" plugin and "Kotlin Compiler DevKit" plugin are installed at the same time.
            |Please uninstall the first one.""".trimMargin()

            NotificationGroupManager.getInstance()
                .getNotificationGroup(GROUP_DISPLAY_ID)
                .createNotification(message, NotificationType.WARNING)
                .notify(project)
        }
    }
}

/*

com.intellij.sisyphus
com.jetbrains.intellij.api.watcher
com.jetbrains.space
intellij.jupyter
org.jetbrains.plugins.kotlin.jupyter
TestNG-J
Coverage
com.intellij.ml.llm
com.intellij.java.ide
com.intellij.grazie.pro
tanvd.grazi
com.intellij.compose
com.intellij.plugins.eclipsekeymap
org.jetbrains.plugins.github
intellij.git.commit.modal
com.intellij.git.instant
org.jetbrains.idea.maven
org.jetbrains.plugins.gradle
org.jetbrains.plugins.gradle.analysis
org.jetbrains.plugins.gradle.maven
org.jetbrains.plugins.gitlab
com.intellij.internalTools
Kotlin Compiler DevKit
com.intellij.settingsSync
com.intellij.federatedCompute
com.jetbrains.idea.safepush
Git4Idea
PerforceDirectPlugin
com.intellij.configurationScript
intellij.indexing.shared.core
com.jetbrains.codeWithMe
com.intellij.monorepo.devkit
Subversion
org.jetbrains.security.package-checker
org.jetbrains.plugins.gradle.dependency.updater
org.jetbrains.kotlin.test.helper
com.jetbrains.performancePlugin.async
org.jetbrains.toolbox-enterprise-client
org.jetbrains.android
 */
