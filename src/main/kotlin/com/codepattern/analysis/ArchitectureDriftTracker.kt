package com.codepattern.analysis

import com.codepattern.models.Violation
import com.codepattern.models.ViolationSeverity
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Tracks architecture drift by saving scan baselines and comparing against them.
 *
 * Baselines are stored as JSON in `.codepattern/baselines/` in the project root.
 * Each baseline captures: pattern name, timestamp, violation count by severity,
 * health score, and violation fingerprints.
 *
 * Drift is detected when:
 * - New violations appear that weren't in the baseline
 * - Violation count increases beyond a threshold
 * - Health score drops below a threshold
 */
object ArchitectureDriftTracker {

    data class Baseline(
        val patternName: String,
        val timestamp: String,
        val healthScore: Int,
        val errorCount: Int,
        val warningCount: Int,
        val infoCount: Int,
        val totalViolations: Int,
        val violationFingerprints: Set<String>  // unique violation identifiers
    )

    data class DriftReport(
        val baselineTimestamp: String,
        val currentTimestamp: String,
        val healthScoreDelta: Int,        // negative = degradation
        val newViolations: List<Violation>,
        val resolvedViolations: Int,
        val totalDelta: Int,              // positive = more violations
        val isDrifting: Boolean,          // true if architecture is degrading
        val summary: String
    )

    /**
     * Create a fingerprint for a violation (used for diffing baselines).
     * Uses rule + file + line for stable identity.
     */
    fun fingerprint(v: Violation): String {
        return "${v.ruleId}:${v.filePath}:${v.lineNumber}"
    }

    /**
     * Save current scan results as a baseline.
     */
    fun saveBaseline(
        projectPath: String,
        patternName: String,
        healthScore: Int,
        violations: List<Violation>
    ): File {
        val baseDir = File(projectPath, ".codepattern/baselines")
        baseDir.mkdirs()

        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        val fingerprints = violations.map { fingerprint(it) }.toSet()

        val baseline = Baseline(
            patternName = patternName,
            timestamp = timestamp,
            healthScore = healthScore,
            errorCount = violations.count { it.severity == ViolationSeverity.ERROR },
            warningCount = violations.count { it.severity == ViolationSeverity.WARNING },
            infoCount = violations.count { it.severity == ViolationSeverity.INFO },
            totalViolations = violations.size,
            violationFingerprints = fingerprints
        )

        val fileName = "baseline-${patternName.lowercase().replace(" ", "-")}.json"
        val file = File(baseDir, fileName)
        file.writeText(serializeBaseline(baseline))
        return file
    }

    /**
     * Load the most recent baseline for a pattern.
     */
    fun loadBaseline(projectPath: String, patternName: String): Baseline? {
        val fileName = "baseline-${patternName.lowercase().replace(" ", "-")}.json"
        val file = File(projectPath, ".codepattern/baselines/$fileName")
        if (!file.exists()) return null
        return try {
            deserializeBaseline(file.readText())
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Compare current violations against the baseline.
     */
    fun detectDrift(
        baseline: Baseline,
        currentHealthScore: Int,
        currentViolations: List<Violation>
    ): DriftReport {
        val currentFingerprints = currentViolations.map { fingerprint(it) }.toSet()
        val newFingerprints = currentFingerprints - baseline.violationFingerprints
        val resolvedFingerprints = baseline.violationFingerprints - currentFingerprints

        val newViolations = currentViolations.filter { fingerprint(it) in newFingerprints }
        val healthDelta = currentHealthScore - baseline.healthScore
        val totalDelta = currentViolations.size - baseline.totalViolations

        val isDrifting = healthDelta < -5 || newViolations.count { it.severity == ViolationSeverity.ERROR } > 0

        val summary = buildString {
            appendLine("Architecture Drift Report")
            appendLine("Baseline: ${baseline.timestamp}")
            appendLine("Current:  ${LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)}")
            appendLine()
            appendLine("Health Score: ${baseline.healthScore} -> $currentHealthScore (${if (healthDelta >= 0) "+$healthDelta" else "$healthDelta"})")
            appendLine("Violations:   ${baseline.totalViolations} -> ${currentViolations.size} (${if (totalDelta >= 0) "+$totalDelta" else "$totalDelta"})")
            appendLine()
            appendLine("New violations:      ${newViolations.size}")
            appendLine("Resolved violations: ${resolvedFingerprints.size}")
            appendLine()

            if (isDrifting) {
                appendLine("!! ARCHITECTURE DRIFT DETECTED !!")
                if (healthDelta < -5) {
                    appendLine("   Health score dropped by ${-healthDelta} points")
                }
                val newErrors = newViolations.count { it.severity == ViolationSeverity.ERROR }
                if (newErrors > 0) {
                    appendLine("   $newErrors new ERROR-level violations introduced")
                }
            } else if (healthDelta > 0) {
                appendLine("Architecture is IMPROVING (+$healthDelta health score)")
            } else {
                appendLine("Architecture is STABLE")
            }

            if (newViolations.isNotEmpty()) {
                appendLine()
                appendLine("New Violations:")
                for (v in newViolations.take(15)) {
                    appendLine("  [${v.severity}] ${v.filePath}:${v.lineNumber} — ${v.message}")
                }
                if (newViolations.size > 15) {
                    appendLine("  ... and ${newViolations.size - 15} more")
                }
            }
        }

        return DriftReport(
            baselineTimestamp = baseline.timestamp,
            currentTimestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            healthScoreDelta = healthDelta,
            newViolations = newViolations,
            resolvedViolations = resolvedFingerprints.size,
            totalDelta = totalDelta,
            isDrifting = isDrifting,
            summary = summary
        )
    }

    // Simple JSON serialization (avoids adding a JSON library dependency)
    private fun serializeBaseline(b: Baseline): String {
        val fps = b.violationFingerprints.joinToString(",\n    ") { "\"${escapeJson(it)}\"" }
        return """{
  "patternName": "${escapeJson(b.patternName)}",
  "timestamp": "${escapeJson(b.timestamp)}",
  "healthScore": ${b.healthScore},
  "errorCount": ${b.errorCount},
  "warningCount": ${b.warningCount},
  "infoCount": ${b.infoCount},
  "totalViolations": ${b.totalViolations},
  "violationFingerprints": [
    $fps
  ]
}"""
    }

    private fun deserializeBaseline(json: String): Baseline {
        fun extractString(key: String): String {
            val pattern = Regex(""""$key"\s*:\s*"([^"]*)"""")
            return pattern.find(json)?.groupValues?.get(1) ?: ""
        }

        fun extractInt(key: String): Int {
            val pattern = Regex(""""$key"\s*:\s*(\d+)""")
            return pattern.find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }

        val fpPattern = Regex(""""([^"]+)"""")
        val fpSection = json.substringAfter("\"violationFingerprints\"").substringAfter("[").substringBefore("]")
        val fingerprints = fpPattern.findAll(fpSection).map { it.groupValues[1] }.toSet()

        return Baseline(
            patternName = extractString("patternName"),
            timestamp = extractString("timestamp"),
            healthScore = extractInt("healthScore"),
            errorCount = extractInt("errorCount"),
            warningCount = extractInt("warningCount"),
            infoCount = extractInt("infoCount"),
            totalViolations = extractInt("totalViolations"),
            violationFingerprints = fingerprints
        )
    }

    private fun escapeJson(s: String): String {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}
