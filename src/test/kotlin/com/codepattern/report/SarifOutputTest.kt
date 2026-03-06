package com.codepattern.report

import com.codepattern.models.*
import org.junit.Assert.*
import org.junit.Test

class SarifOutputTest {

    @Test
    fun `SARIF contains required schema fields`() {
        val data = ReportGenerator.ReportData(
            patternName = "SOLID", patternDescription = "SOLID Principles",
            scanTimestamp = "2026-01-01T00:00:00",
            totalFiles = 5, totalViolations = 1, errorCount = 1, warningCount = 0, infoCount = 0,
            healthScore = 80,
            violations = listOf(
                Violation(
                    ruleName = "SRP", patternName = "SOLID",
                    message = "Class has too many responsibilities",
                    severity = ViolationSeverity.ERROR, filePath = "App.java", lineNumber = 10,
                    suggestedFix = "Split the class", ruleId = "solid-srp"
                )
            ),
            layerClassification = emptyMap()
        )

        val sarif = ReportGenerator.generateSarif(data)
        assertTrue("Should contain SARIF schema", sarif.contains("sarif-schema-2.1.0"))
        assertTrue("Should contain version", sarif.contains("\"version\": \"2.1.0\""))
        assertTrue("Should contain tool name", sarif.contains("Code Pattern Analyzer"))
        assertTrue("Should contain rule", sarif.contains("solid-srp"))
        assertTrue("Should contain file path", sarif.contains("App.java"))
        assertTrue("Should contain message", sarif.contains("too many responsibilities"))
        assertTrue("Should contain severity level", sarif.contains("\"level\": \"error\""))
    }

    @Test
    fun `SARIF handles empty violations`() {
        val data = ReportGenerator.ReportData(
            patternName = "MVC", patternDescription = "Model View Controller",
            scanTimestamp = "2026-01-01T00:00:00",
            totalFiles = 3, totalViolations = 0, errorCount = 0, warningCount = 0, infoCount = 0,
            healthScore = 100, violations = emptyList(), layerClassification = emptyMap()
        )

        val sarif = ReportGenerator.generateSarif(data)
        assertTrue("Should be valid SARIF", sarif.contains("sarif-schema-2.1.0"))
        assertTrue("Should have empty results", sarif.contains("\"results\": ["))
    }

    @Test
    fun `SARIF maps severity correctly`() {
        val violations = listOf(
            Violation(ruleName = "A", patternName = "P", message = "err", severity = ViolationSeverity.ERROR,
                filePath = "a.java", lineNumber = 1, ruleId = "a"),
            Violation(ruleName = "B", patternName = "P", message = "warn", severity = ViolationSeverity.WARNING,
                filePath = "b.java", lineNumber = 1, ruleId = "b"),
            Violation(ruleName = "C", patternName = "P", message = "info", severity = ViolationSeverity.INFO,
                filePath = "c.java", lineNumber = 1, ruleId = "c")
        )
        val data = ReportGenerator.ReportData(
            patternName = "P", patternDescription = "",
            scanTimestamp = "2026-01-01", totalFiles = 3, totalViolations = 3,
            errorCount = 1, warningCount = 1, infoCount = 1, healthScore = 50,
            violations = violations, layerClassification = emptyMap()
        )

        val sarif = ReportGenerator.generateSarif(data)
        assertTrue(sarif.contains("\"level\": \"error\""))
        assertTrue(sarif.contains("\"level\": \"warning\""))
        assertTrue(sarif.contains("\"level\": \"note\""))
    }
}
