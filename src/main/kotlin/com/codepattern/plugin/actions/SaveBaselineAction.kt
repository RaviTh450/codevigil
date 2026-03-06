package com.codepattern.plugin.actions

import com.codepattern.scanner.ProjectScannerService
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

class SaveBaselineAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = project.getService(ProjectScannerService::class.java)

        if (service.selectedPattern == null || service.lastScanResult == null) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Code Pattern Analyzer")
                .createNotification("No Data", "Run a scan first, then save a baseline.", NotificationType.WARNING)
                .notify(project)
            return
        }

        service.saveBaseline()

        NotificationGroupManager.getInstance()
            .getNotificationGroup("Code Pattern Analyzer")
            .createNotification(
                "Baseline Saved",
                "Architecture baseline saved for '${service.selectedPattern!!.name}'. " +
                        "Future scans will detect drift from this point.",
                NotificationType.INFORMATION
            )
            .notify(project)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
