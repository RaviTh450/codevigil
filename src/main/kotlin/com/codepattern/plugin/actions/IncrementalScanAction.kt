package com.codepattern.plugin.actions

import com.codepattern.scanner.ProjectScannerService
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task

class IncrementalScanAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = project.getService(ProjectScannerService::class.java)

        if (service.selectedPattern == null) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("CodeVigil")
                .createNotification("No Pattern Selected", "Select a pattern first.", NotificationType.WARNING)
                .notify(project)
            return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Incremental Scan (changed files only)") {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Detecting changed files..."
                indicator.fraction = 0.2
                val violations = service.scanIncremental()
                indicator.fraction = 1.0

                NotificationGroupManager.getInstance()
                    .getNotificationGroup("CodeVigil")
                    .createNotification(
                        "Incremental Scan Complete",
                        "Found ${violations.size} violation(s). Only changed files were re-analyzed.",
                        if (violations.isEmpty()) NotificationType.INFORMATION else NotificationType.WARNING
                    )
                    .notify(project)
            }
        })
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
