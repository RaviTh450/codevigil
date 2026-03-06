package com.codepattern.analysis

import com.codepattern.models.Violation
import com.codepattern.models.ViolationCategory
import com.codepattern.models.ViolationSeverity
import com.codepattern.report.ReportGenerator

/**
 * Architecture Fitness Functions — measurable criteria that verify
 * architectural decisions are being followed over time.
 *
 * Each fitness function defines a threshold. If the current metric
 * exceeds the threshold, the function fails. Designed for CI/CD gates.
 *
 * Inspired by Neal Ford's "Building Evolutionary Architectures".
 */
object FitnessFunction {

    data class FitnessResult(
        val name: String,
        val passed: Boolean,
        val currentValue: Number,
        val threshold: Number,
        val description: String
    ) {
        fun describe(): String {
            val status = if (passed) "PASS" else "FAIL"
            return "[$status] $name: $currentValue (threshold: $threshold) — $description"
        }
    }

    data class FitnessConfig(
        val minHealthScore: Int = 60,
        val maxErrors: Int = 0,
        val maxWarnings: Int = 20,
        val maxCyclomaticComplexity: Int = 15,
        val maxCognitiveComplexity: Int = 20,
        val maxMethodLines: Int = 60,
        val maxFileLines: Int = 500,
        val maxCircularDeps: Int = 0,
        val maxCouplingPerFile: Int = 15,
        val maxGodClasses: Int = 0,
        val maxViolationsPerFile: Int = 10
    )

    /**
     * Evaluate all fitness functions against scan results.
     * Returns list of results. CI/CD should fail if any result.passed == false.
     */
    fun evaluate(
        violations: List<Violation>,
        totalFiles: Int,
        config: FitnessConfig = FitnessConfig()
    ): List<FitnessResult> {
        val results = mutableListOf<FitnessResult>()
        val healthScore = ReportGenerator.computeHealthScore(violations, totalFiles)

        // 1. Health Score
        results += FitnessResult(
            name = "Minimum Health Score",
            passed = healthScore >= config.minHealthScore,
            currentValue = healthScore,
            threshold = config.minHealthScore,
            description = "Overall project health must meet minimum threshold"
        )

        // 2. Zero Errors
        val errorCount = violations.count { it.severity == ViolationSeverity.ERROR }
        results += FitnessResult(
            name = "Maximum Errors",
            passed = errorCount <= config.maxErrors,
            currentValue = errorCount,
            threshold = config.maxErrors,
            description = "Architecture-breaking violations (ERROR severity)"
        )

        // 3. Warning Budget
        val warningCount = violations.count { it.severity == ViolationSeverity.WARNING }
        results += FitnessResult(
            name = "Warning Budget",
            passed = warningCount <= config.maxWarnings,
            currentValue = warningCount,
            threshold = config.maxWarnings,
            description = "Total warnings must stay within budget"
        )

        // 4. Circular Dependencies
        val circularDeps = violations.count { it.category == ViolationCategory.COUPLING && it.message.contains("Circular") }
        results += FitnessResult(
            name = "No Circular Dependencies",
            passed = circularDeps <= config.maxCircularDeps,
            currentValue = circularDeps,
            threshold = config.maxCircularDeps,
            description = "Circular import chains"
        )

        // 5. God Classes
        val godClasses = violations.count { it.message.contains("God Class", ignoreCase = true) }
        results += FitnessResult(
            name = "No God Classes",
            passed = godClasses <= config.maxGodClasses,
            currentValue = godClasses,
            threshold = config.maxGodClasses,
            description = "Classes that are too large with too many responsibilities"
        )

        // 6. Max Violations Per File
        val maxPerFile = violations.groupBy { it.filePath }.values.maxOfOrNull { it.size } ?: 0
        results += FitnessResult(
            name = "Max Violations Per File",
            passed = maxPerFile <= config.maxViolationsPerFile,
            currentValue = maxPerFile,
            threshold = config.maxViolationsPerFile,
            description = "No single file should accumulate too many violations"
        )

        // 7. Coupling
        val highCouplingFiles = violations.count {
            it.category == ViolationCategory.COUPLING && it.message.contains("imports")
        }
        results += FitnessResult(
            name = "High Coupling Files",
            passed = highCouplingFiles <= 3,
            currentValue = highCouplingFiles,
            threshold = 3,
            description = "Files with excessive import dependencies"
        )

        return results
    }

    /**
     * Generate a fitness report string.
     */
    fun generateReport(results: List<FitnessResult>): String {
        val sb = StringBuilder()
        sb.appendLine("=== Architecture Fitness Report ===")
        sb.appendLine()

        val passed = results.count { it.passed }
        val failed = results.count { !it.passed }
        val allPassed = failed == 0

        sb.appendLine("Result: ${if (allPassed) "ALL PASSED" else "FAILED ($failed/${results.size})"}")
        sb.appendLine()

        for (result in results) {
            sb.appendLine(result.describe())
        }

        sb.appendLine()
        sb.appendLine("$passed/${results.size} fitness functions passed")

        return sb.toString()
    }

    /**
     * Parse fitness config from a map (loaded from .codepattern.yml or CLI args).
     */
    fun parseConfig(map: Map<String, Any>): FitnessConfig {
        return FitnessConfig(
            minHealthScore = (map["min_health_score"] as? Number)?.toInt() ?: 60,
            maxErrors = (map["max_errors"] as? Number)?.toInt() ?: 0,
            maxWarnings = (map["max_warnings"] as? Number)?.toInt() ?: 20,
            maxCyclomaticComplexity = (map["max_cyclomatic_complexity"] as? Number)?.toInt() ?: 15,
            maxCognitiveComplexity = (map["max_cognitive_complexity"] as? Number)?.toInt() ?: 20,
            maxMethodLines = (map["max_method_lines"] as? Number)?.toInt() ?: 60,
            maxFileLines = (map["max_file_lines"] as? Number)?.toInt() ?: 500,
            maxCircularDeps = (map["max_circular_deps"] as? Number)?.toInt() ?: 0,
            maxCouplingPerFile = (map["max_coupling_per_file"] as? Number)?.toInt() ?: 15,
            maxGodClasses = (map["max_god_classes"] as? Number)?.toInt() ?: 0,
            maxViolationsPerFile = (map["max_violations_per_file"] as? Number)?.toInt() ?: 10
        )
    }
}
