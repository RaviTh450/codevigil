package com.codepattern.plugin.actions

import com.codepattern.licensing.LicenseManager
import com.codepattern.report.ReportGenerator
import com.codepattern.scanner.ProjectScannerService
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class ExportReportAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = project.getService(ProjectScannerService::class.java)

        if (service.selectedPattern == null || service.lastViolations.isEmpty()) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Code Pattern Analyzer")
                .createNotification(
                    "No Data",
                    "Run a scan first, then export the report.",
                    NotificationType.WARNING
                )
                .notify(project)
            return
        }

        // Check license for export feature
        val license = LicenseManager.getInstance()
        if (!license.isFeatureAvailable(LicenseManager.FEATURE_EXPORT_HTML) &&
            !license.isFeatureAvailable(LicenseManager.FEATURE_EXPORT_JSON)
        ) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Code Pattern Analyzer")
                .createNotification(
                    "Pro Feature",
                    "Report export requires a Pro or Enterprise license. Upgrade in Settings > Code Pattern Analyzer.",
                    NotificationType.INFORMATION
                )
                .notify(project)
            return
        }

        val descriptor = FileSaverDescriptor("Export Analysis Report", "Choose where to save the report", "html", "json")
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val wrapper = dialog.save(project.basePath) ?: return

        val pattern = service.selectedPattern!!
        val violations = service.lastViolations
        val classified = service.classifyProjectFiles()
        val totalFiles = service.lastScanResult?.files?.size ?: 0
        val healthScore = ReportGenerator.computeHealthScore(violations, totalFiles)

        val reportData = ReportGenerator.ReportData(
            patternName = pattern.name,
            patternDescription = pattern.description,
            scanTimestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            totalFiles = totalFiles,
            totalViolations = violations.size,
            errorCount = violations.count { it.severity == com.codepattern.models.ViolationSeverity.ERROR },
            warningCount = violations.count { it.severity == com.codepattern.models.ViolationSeverity.WARNING },
            infoCount = violations.count { it.severity == com.codepattern.models.ViolationSeverity.INFO },
            healthScore = healthScore,
            violations = violations,
            layerClassification = classified
        )

        val virtualFile = wrapper.getVirtualFile(true)
        val outputPath = virtualFile?.path ?: wrapper.file.absolutePath
        val isHtml = outputPath.endsWith(".html")
        val content = if (isHtml) ReportGenerator.generateHtml(reportData) else ReportGenerator.generateJson(reportData)

        java.io.File(outputPath).writeText(content)

        NotificationGroupManager.getInstance()
            .getNotificationGroup("Code Pattern Analyzer")
            .createNotification(
                "Report Exported",
                "Report saved to: $outputPath",
                NotificationType.INFORMATION
            )
            .notify(project)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
