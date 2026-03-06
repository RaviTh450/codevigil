package com.codepattern.inspections

import com.codepattern.models.Violation
import com.codepattern.models.ViolationSeverity
import com.codepattern.scanner.ProjectScannerService
import com.intellij.codeInspection.*
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * Inspection that reports pattern violations for batch analysis (Inspect Code).
 * For real-time highlighting, see PatternAnnotator.
 */
class PatternViolationInspection : LocalInspectionTool() {

    override fun getDisplayName(): String = "Code Pattern Violation"

    override fun getGroupDisplayName(): String = "Code Pattern Analyzer"

    override fun getShortName(): String = "PatternViolation"

    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        val project = file.project
        val service = project.getService(ProjectScannerService::class.java) ?: return null
        if (service.selectedPattern == null) return null

        // For on-the-fly checks, analyze the single file for fresh results
        val violations = if (isOnTheFly) {
            service.analyzeFile(file)
        } else {
            // For batch inspections, use cached results from last scan
            val filePath = file.virtualFile?.path ?: return null
            service.getViolationsForFile(filePath)
        }

        if (violations.isEmpty()) return null

        val problems = mutableListOf<ProblemDescriptor>()

        for (violation in violations) {
            val element = findElementAtLine(file, violation.lineNumber) ?: continue
            val severity = when (violation.severity) {
                ViolationSeverity.ERROR -> ProblemHighlightType.GENERIC_ERROR
                ViolationSeverity.WARNING -> ProblemHighlightType.GENERIC_ERROR_OR_WARNING
                ViolationSeverity.INFO -> ProblemHighlightType.WEAK_WARNING
            }

            val fixes = mutableListOf<LocalQuickFix>()
            if (violation.suggestedFix != null) {
                fixes += PatternQuickFix(violation)
            }

            problems += manager.createProblemDescriptor(
                element,
                "[${violation.patternName}] ${violation.message}",
                isOnTheFly,
                fixes.toTypedArray(),
                severity
            )
        }

        return problems.toTypedArray()
    }

    private fun findElementAtLine(file: PsiFile, lineNumber: Int): PsiElement? {
        val document = file.viewProvider.document ?: return null
        if (lineNumber < 1 || lineNumber > document.lineCount) return null

        val lineStartOffset = document.getLineStartOffset(lineNumber - 1)
        val lineEndOffset = document.getLineEndOffset(lineNumber - 1)

        var offset = lineStartOffset
        while (offset < lineEndOffset) {
            val element = file.findElementAt(offset)
            if (element != null && element.text.isNotBlank()) {
                return element
            }
            offset++
        }

        return file.findElementAt(lineStartOffset)
    }
}
