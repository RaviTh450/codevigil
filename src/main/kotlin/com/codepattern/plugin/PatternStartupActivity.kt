package com.codepattern.plugin

import com.codepattern.scanner.ProjectScannerService
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class PatternStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val service = project.getService(ProjectScannerService::class.java)
        service.loadPatterns()

        val settings = PatternSettingsState.getInstance()

        // Restore selected pattern from settings
        if (settings.state.selectedPatternName.isNotEmpty()) {
            service.selectedPattern = service.availablePatterns.find {
                it.name == settings.state.selectedPatternName
            }
        }

        // Auto-detect pattern from .codevigil.yml if none selected
        if (service.selectedPattern == null && settings.state.autoDetectPattern) {
            // If project config specified patterns, use the first one
            if (service.selectedPatterns.isNotEmpty()) {
                service.selectedPattern = service.selectedPatterns[0]
            } else {
                // Try to auto-detect from project structure
                service.autoDetectPattern()
            }
        }

        // Auto-start real-time analysis
        if (settings.state.enableRealTimeAnalysis && service.selectedPattern != null) {
            RealTimeAnalysisManager.getInstance(project).start()
        }

        // Notify user
        if (settings.state.showNotifications) {
            val realTimeStatus = if (settings.state.enableRealTimeAnalysis && service.selectedPattern != null)
                " Real-time checking is ON." else ""
            NotificationGroupManager.getInstance()
                .getNotificationGroup("CodeVigil")
                .createNotification(
                    "CodeVigil",
                    "Plugin loaded with ${service.availablePatterns.size} patterns. " +
                            (if (service.selectedPattern != null)
                                "Active pattern: ${service.selectedPattern!!.name}.$realTimeStatus"
                            else
                                "Go to Tools > Code Patterns > Select Pattern to get started."),
                    NotificationType.INFORMATION
                )
                .notify(project)
        }

        // Auto-scan if configured
        if (settings.state.scanOnStartup && service.selectedPattern != null) {
            service.scanAndAnalyze()
        }
    }
}
