package com.codepattern.plugin.actions

import com.codepattern.scanner.ProjectScannerService
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep

class MultiPatternScanAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = project.getService(ProjectScannerService::class.java)

        if (service.availablePatterns.isEmpty()) {
            service.loadPatterns()
        }

        val patterns = service.availablePatterns
        if (patterns.isEmpty()) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Code Pattern Analyzer")
                .createNotification("No Patterns", "No pattern definitions found.", NotificationType.ERROR)
                .notify(project)
            return
        }

        // Show multi-select: for simplicity, offer "All Patterns" plus individual patterns
        val options = listOf("All Patterns (combined analysis)") + patterns.map { it.name }

        val step = object : BaseListPopupStep<String>("Select Patterns for Multi-Analysis", options) {
            override fun onChosen(selectedValue: String, finalChoice: Boolean): PopupStep<*>? {
                if (selectedValue.startsWith("All Patterns")) {
                    service.selectedPatterns = patterns
                    service.selectedPattern = patterns.firstOrNull()
                } else {
                    val pattern = patterns.find { it.name == selectedValue }
                    if (pattern != null) {
                        service.selectedPatterns = listOf(pattern)
                        service.selectedPattern = pattern
                    }
                }

                ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Multi-Pattern Analysis") {
                    override fun run(indicator: ProgressIndicator) {
                        indicator.text = "Analyzing against ${service.selectedPatterns.size} pattern(s)..."
                        val violations = service.scanAndAnalyzeMultiPattern()

                        NotificationGroupManager.getInstance()
                            .getNotificationGroup("Code Pattern Analyzer")
                            .createNotification(
                                "Multi-Pattern Scan Complete",
                                "Found ${violations.size} violation(s) across ${service.selectedPatterns.size} patterns. " +
                                        "Check the Code Patterns tool window for details.",
                                if (violations.isEmpty()) NotificationType.INFORMATION else NotificationType.WARNING
                            )
                            .notify(project)
                    }
                })

                return FINAL_CHOICE
            }
        }

        JBPopupFactory.getInstance().createListPopup(step).showCenteredInCurrentWindow(project)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
