package com.codepattern.plugin.actions

import com.codepattern.plugin.PatternSettingsState
import com.codepattern.scanner.ProjectScannerService
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep

class SelectPatternAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = project.getService(ProjectScannerService::class.java)

        if (service.availablePatterns.isEmpty()) {
            service.loadPatterns()
        }

        val patterns = service.availablePatterns
        if (patterns.isEmpty()) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("CodeVigil")
                .createNotification("No Patterns", "No pattern definitions found.", NotificationType.ERROR)
                .notify(project)
            return
        }

        val step = object : BaseListPopupStep<String>(
            "Select Pattern",
            patterns.map { "${it.name} — ${it.description}" }
        ) {
            override fun onChosen(selectedValue: String, finalChoice: Boolean): PopupStep<*>? {
                val index = patterns.indexOfFirst { "${it.name} — ${it.description}" == selectedValue }
                if (index >= 0) {
                    service.selectedPattern = patterns[index]
                    PatternSettingsState.getInstance().state.selectedPatternName = patterns[index].name

                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("CodeVigil")
                        .createNotification(
                            "Pattern Selected",
                            "Now using '${patterns[index].name}'. Run scan from Tools > Code Patterns > Scan Project.",
                            NotificationType.INFORMATION
                        )
                        .notify(project)
                }
                return FINAL_CHOICE
            }
        }

        JBPopupFactory.getInstance().createListPopup(step).showCenteredInCurrentWindow(project)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
