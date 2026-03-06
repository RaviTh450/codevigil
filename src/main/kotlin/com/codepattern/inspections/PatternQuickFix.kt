package com.codepattern.inspections

import com.codepattern.models.Violation
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

/**
 * Shows a pattern violation suggestion notification.
 * This is an informational quick-fix — it surfaces the recommended refactoring
 * rather than performing an automatic code change.
 */
class PatternQuickFix(private val violation: Violation) : LocalQuickFix {

    override fun getName(): String = "View suggestion: ${violation.suggestedFix?.take(50) ?: "View details"}"

    override fun getFamilyName(): String = "Code Pattern Analyzer Suggestions"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Code Pattern Analyzer")
            .createNotification(
                "Pattern Fix Suggestion",
                buildString {
                    append("<b>Rule:</b> ${violation.ruleName}<br/>")
                    append("<b>Pattern:</b> ${violation.patternName}<br/>")
                    append("<b>File:</b> ${violation.filePath}:${violation.lineNumber}<br/>")
                    append("<b>Suggestion:</b> ${violation.suggestedFix}<br/>")
                },
                NotificationType.INFORMATION
            )
            .notify(project)
    }
}
