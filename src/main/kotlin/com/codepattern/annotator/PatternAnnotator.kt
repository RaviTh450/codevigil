package com.codepattern.annotator

import com.codepattern.models.Violation
import com.codepattern.models.ViolationSeverity
import com.codepattern.plugin.PatternSettingsState
import com.codepattern.scanner.ProjectScannerService
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile

/**
 * ExternalAnnotator that provides real-time highlighting of pattern violations.
 * Runs per-file analysis in the background and applies annotations in the editor.
 *
 * When real-time analysis is enabled, this annotator runs on every file open
 * and after document changes, providing immediate feedback on violations.
 */
class PatternAnnotator : ExternalAnnotator<PatternAnnotator.FileInfo, List<Violation>>() {

    data class FileInfo(
        val psiFile: PsiFile,
        val lineCount: Int
    )

    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): FileInfo? {
        val project = file.project
        val service = project.getService(ProjectScannerService::class.java) ?: return null
        if (service.selectedPattern == null) return null
        if (file.virtualFile == null) return null

        val document = editor.document
        return FileInfo(psiFile = file, lineCount = document.lineCount)
    }

    override fun doAnnotate(info: FileInfo): List<Violation> {
        val project = info.psiFile.project
        val service = project.getService(ProjectScannerService::class.java) ?: return emptyList()
        val settings = PatternSettingsState.getInstance()

        val violations = service.analyzeFile(info.psiFile)

        // Filter by confidence threshold
        return violations.filter { it.confidence >= settings.state.minConfidence }
    }

    override fun apply(file: PsiFile, violations: List<Violation>, holder: AnnotationHolder) {
        val document = file.viewProvider.document ?: return
        val settings = PatternSettingsState.getInstance()
        val showConfidence = settings.state.showConfidenceScores

        for (violation in violations) {
            val lineIndex = violation.lineNumber - 1
            if (lineIndex < 0 || lineIndex >= document.lineCount) continue

            val lineStart = document.getLineStartOffset(lineIndex)
            val lineEnd = document.getLineEndOffset(lineIndex)

            // Find the first non-whitespace content on the line for a tighter highlight
            val lineText = document.getText(TextRange(lineStart, lineEnd))
            val contentStart = lineText.indexOfFirst { !it.isWhitespace() }
            val highlightStart = if (contentStart >= 0) lineStart + contentStart else lineStart
            val highlightEnd = lineEnd.coerceAtLeast(highlightStart + 1)

            val severity = when (violation.severity) {
                ViolationSeverity.ERROR -> HighlightSeverity.ERROR
                ViolationSeverity.WARNING -> HighlightSeverity.WARNING
                ViolationSeverity.INFO -> HighlightSeverity.WEAK_WARNING
            }

            val confidenceStr = if (showConfidence) " (${(violation.confidence * 100).toInt()}% confidence)" else ""
            val message = "[${violation.patternName}] ${violation.message}$confidenceStr"

            val tooltip = buildString {
                append("<b>[${violation.patternName}]</b> ${violation.message}")
                if (showConfidence) {
                    append("<br/><b>Confidence:</b> ${(violation.confidence * 100).toInt()}%")
                }
                append("<br/><b>Category:</b> ${violation.category}")
                append("<br/><b>Rule:</b> ${violation.ruleId}")
                if (violation.suggestedFix != null) {
                    append("<br/><br/><i>Fix: ${violation.suggestedFix}</i>")
                }
            }

            holder.newAnnotation(severity, message)
                .range(TextRange(highlightStart, highlightEnd))
                .tooltip(tooltip)
                .create()
        }
    }
}
