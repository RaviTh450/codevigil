package com.codepattern.report

import com.codepattern.models.Violation
import com.codepattern.models.ViolationCategory
import com.codepattern.models.ViolationSeverity
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReportGeneratorTest {

    private fun sampleViolations() = listOf(
        Violation(
            ruleName = "Layer Dependency",
            patternName = "Clean Architecture",
            message = "Entity depends on infrastructure",
            severity = ViolationSeverity.ERROR,
            filePath = "src/domain/Order.kt",
            lineNumber = 15,
            suggestedFix = "Remove infrastructure import",
            category = ViolationCategory.ARCHITECTURE,
            confidence = 0.95,
            ruleId = "clean-layer-dep"
        ),
        Violation(
            ruleName = "SRP",
            patternName = "SOLID",
            message = "Class has 25 methods",
            severity = ViolationSeverity.WARNING,
            filePath = "src/service/OrderService.kt",
            lineNumber = 1,
            category = ViolationCategory.SOLID,
            confidence = 0.8,
            ruleId = "solid-srp"
        )
    )

    @Test
    fun `health score is 100 with no violations`() {
        val score = ReportGenerator.computeHealthScore(emptyList(), 10)
        assertEquals(100, score)
    }

    @Test
    fun `health score decreases with violations`() {
        val violations = sampleViolations()
        val score = ReportGenerator.computeHealthScore(violations, 10)
        assertTrue(score < 100, "Score should be less than 100 with violations")
        assertTrue(score >= 0, "Score should not be negative")
    }

    @Test
    fun `health score never below 0`() {
        val manyErrors = (1..100).map {
            Violation("rule", "pattern", "msg", ViolationSeverity.ERROR, "file.kt", 1, ruleId = "r")
        }
        val score = ReportGenerator.computeHealthScore(manyErrors, 5)
        assertEquals(0, score)
    }

    @Test
    fun `json report contains all required fields`() {
        val data = ReportGenerator.ReportData(
            patternName = "Clean Architecture",
            patternDescription = "Uncle Bob's",
            scanTimestamp = "2024-01-01T00:00:00",
            totalFiles = 50,
            totalViolations = 2,
            errorCount = 1,
            warningCount = 1,
            infoCount = 0,
            healthScore = 85,
            violations = sampleViolations(),
            layerClassification = emptyMap()
        )
        val json = ReportGenerator.generateJson(data)
        assertTrue(json.contains("\"pattern\": \"Clean Architecture\""))
        assertTrue(json.contains("\"totalViolations\": 2"))
        assertTrue(json.contains("\"healthScore\": 85"))
        assertTrue(json.contains("\"severity\": \"ERROR\""))
        assertTrue(json.contains("\"confidence\": 0.95"))
    }

    @Test
    fun `html report contains key sections`() {
        val data = ReportGenerator.ReportData(
            patternName = "SOLID",
            patternDescription = "SOLID principles",
            scanTimestamp = "2024-01-01T00:00:00",
            totalFiles = 20,
            totalViolations = 1,
            errorCount = 0,
            warningCount = 1,
            infoCount = 0,
            healthScore = 90,
            violations = sampleViolations().take(1),
            layerClassification = emptyMap()
        )
        val html = ReportGenerator.generateHtml(data)
        assertTrue(html.contains("<!DOCTYPE html>"))
        assertTrue(html.contains("Health Score"))
        assertTrue(html.contains("SOLID"))
        assertTrue(html.contains("Violations"))
        assertTrue(html.contains("Architecture Structure"))
    }
}
