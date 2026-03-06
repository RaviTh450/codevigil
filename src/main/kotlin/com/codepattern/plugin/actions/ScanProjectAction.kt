package com.codepattern.plugin.actions

import com.codepattern.scanner.ProjectScannerService
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task

class ScanProjectAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = project.getService(ProjectScannerService::class.java)

        if (service.selectedPattern == null) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Code Pattern Analyzer")
                .createNotification(
                    "No Pattern Selected",
                    "Please select a pattern first via Tools > Code Patterns > Select Pattern.",
                    NotificationType.WARNING
                )
                .notify(project)
            return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Scanning for Pattern Violations") {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Scanning project files..."
                indicator.fraction = 0.1

                // ReadAction is handled inside ProjectScanner.scan()
                val violations = service.scanAndAnalyze()

                indicator.fraction = 1.0

                NotificationGroupManager.getInstance()
                    .getNotificationGroup("Code Pattern Analyzer")
                    .createNotification(
                        "Scan Complete",
                        "Found ${violations.size} violation(s) against '${service.selectedPattern?.name}' pattern. " +
                                "Check the Code Patterns tool window for details.",
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
