package com.codepattern.plugin.actions

import com.codepattern.plugin.RealTimeAnalysisManager
import com.codepattern.scanner.ProjectScannerService
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

class ToggleRealTimeAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = project.getService(ProjectScannerService::class.java)

        if (service.selectedPattern == null) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("CodeVigil")
                .createNotification(
                    "No Pattern Selected",
                    "Select an architectural pattern first (Tools > Code Patterns > Select Pattern).",
                    NotificationType.WARNING
                )
                .notify(project)
            return
        }

        val manager = RealTimeAnalysisManager.getInstance(project)
        val nowEnabled = manager.toggle()

        NotificationGroupManager.getInstance()
            .getNotificationGroup("CodeVigil")
            .createNotification(
                "Real-Time Analysis ${if (nowEnabled) "Enabled" else "Disabled"}",
                if (nowEnabled)
                    "Pattern violations will be highlighted as you type. Active pattern: ${service.selectedPattern!!.name}"
                else
                    "Real-time analysis turned off. Use Scan Project for manual analysis.",
                NotificationType.INFORMATION
            )
            .notify(project)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null
        if (project != null) {
            val manager = RealTimeAnalysisManager.getInstance(project)
            e.presentation.text = if (manager.isEnabled)
                "Disable Real-Time Analysis"
            else
                "Enable Real-Time Analysis"
        }
    }
}
