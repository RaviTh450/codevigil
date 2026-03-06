package com.codepattern.report

import com.codepattern.models.*
import com.codepattern.scanner.ScannedFile

/**
 * Generates analysis reports in JSON and HTML formats.
 */
object ReportGenerator {

    data class ReportData(
        val patternName: String,
        val patternDescription: String,
        val scanTimestamp: String,
        val totalFiles: Int,
        val totalViolations: Int,
        val errorCount: Int,
        val warningCount: Int,
        val infoCount: Int,
        val healthScore: Int,
        val violations: List<Violation>,
        val layerClassification: Map<String, List<ScannedFile>>,
        val metrics: Map<String, Any>? = null
    )

    fun generateJson(data: ReportData): String {
        val sb = StringBuilder()
        sb.appendLine("{")
        sb.appendLine("""  "report": {""")
        sb.appendLine("""    "pattern": "${escapeJson(data.patternName)}",""")
        sb.appendLine("""    "description": "${escapeJson(data.patternDescription)}",""")
        sb.appendLine("""    "timestamp": "${escapeJson(data.scanTimestamp)}",""")
        sb.appendLine("""    "summary": {""")
        sb.appendLine("""      "totalFiles": ${data.totalFiles},""")
        sb.appendLine("""      "totalViolations": ${data.totalViolations},""")
        sb.appendLine("""      "errors": ${data.errorCount},""")
        sb.appendLine("""      "warnings": ${data.warningCount},""")
        sb.appendLine("""      "info": ${data.infoCount},""")
        sb.appendLine("""      "healthScore": ${data.healthScore}""")
        sb.appendLine("    },")

        // Violations
        sb.appendLine("""    "violations": [""")
        data.violations.forEachIndexed { index, v ->
            sb.appendLine("      {")
            sb.appendLine("""        "ruleId": "${escapeJson(v.ruleId)}",""")
            sb.appendLine("""        "ruleName": "${escapeJson(v.ruleName)}",""")
            sb.appendLine("""        "pattern": "${escapeJson(v.patternName)}",""")
            sb.appendLine("""        "severity": "${v.severity}",""")
            sb.appendLine("""        "category": "${v.category}",""")
            sb.appendLine("""        "confidence": ${v.confidence},""")
            sb.appendLine("""        "file": "${escapeJson(v.filePath)}",""")
            sb.appendLine("""        "line": ${v.lineNumber},""")
            sb.appendLine("""        "message": "${escapeJson(v.message)}",""")
            sb.appendLine("""        "suggestedFix": ${if (v.suggestedFix != null) "\"${escapeJson(v.suggestedFix)}\"" else "null"}""")
            sb.append("      }")
            if (index < data.violations.size - 1) sb.append(",")
            sb.appendLine()
        }
        sb.appendLine("    ],")

        // Layer classification
        sb.appendLine("""    "layers": {""")
        val layerEntries = data.layerClassification.entries.toList()
        layerEntries.forEachIndexed { index, (layer, files) ->
            sb.appendLine("""      "${escapeJson(layer)}": {""")
            sb.appendLine("""        "fileCount": ${files.size},""")
            sb.appendLine("""        "files": [${files.joinToString(", ") { "\"${escapeJson(it.relativePath)}\"" }}]""")
            sb.append("      }")
            if (index < layerEntries.size - 1) sb.append(",")
            sb.appendLine()
        }
        sb.appendLine("    }")

        sb.appendLine("  }")
        sb.appendLine("}")
        return sb.toString()
    }

    fun generateHtml(data: ReportData): String {
        return buildString {
            appendLine("<!DOCTYPE html>")
            appendLine("<html lang=\"en\">")
            appendLine("<head>")
            appendLine("<meta charset=\"UTF-8\">")
            appendLine("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
            appendLine("<title>CodeVigil Analysis Report - ${escapeHtml(data.patternName)}</title>")
            appendLine("<style>")
            appendLine(CSS_STYLES)
            appendLine("</style>")
            appendLine("</head>")
            appendLine("<body>")

            // Header
            appendLine("<div class=\"header\">")
            appendLine("<h1>CodeVigil Analysis Report</h1>")
            appendLine("<p class=\"subtitle\">${escapeHtml(data.patternName)} - ${escapeHtml(data.patternDescription)}</p>")
            appendLine("<p class=\"timestamp\">Generated: ${escapeHtml(data.scanTimestamp)}</p>")
            appendLine("</div>")

            // Health Score
            val scoreClass = when {
                data.healthScore >= 80 -> "score-good"
                data.healthScore >= 50 -> "score-warn"
                else -> "score-bad"
            }
            appendLine("<div class=\"score-card $scoreClass\">")
            appendLine("<div class=\"score-value\">${data.healthScore}</div>")
            appendLine("<div class=\"score-label\">Health Score</div>")
            appendLine("</div>")

            // Summary cards
            appendLine("<div class=\"summary-grid\">")
            appendLine(summaryCard("Total Files", data.totalFiles.toString(), "file-icon"))
            appendLine(summaryCard("Violations", data.totalViolations.toString(), "violation-icon"))
            appendLine(summaryCard("Errors", data.errorCount.toString(), "error-icon"))
            appendLine(summaryCard("Warnings", data.warningCount.toString(), "warning-icon"))
            appendLine("</div>")

            // Violations table
            appendLine("<h2>Violations</h2>")
            if (data.violations.isEmpty()) {
                appendLine("<p class=\"no-violations\">No violations found! Your code follows the ${escapeHtml(data.patternName)} pattern.</p>")
            } else {
                appendLine("<table class=\"violations-table\">")
                appendLine("<thead><tr>")
                appendLine("<th>Severity</th><th>Category</th><th>Rule</th><th>File</th><th>Line</th><th>Message</th><th>Confidence</th>")
                appendLine("</tr></thead><tbody>")
                for (v in data.violations) {
                    val severityClass = "severity-${v.severity.name.lowercase()}"
                    appendLine("<tr class=\"$severityClass\">")
                    appendLine("<td><span class=\"badge $severityClass\">${v.severity}</span></td>")
                    appendLine("<td>${v.category}</td>")
                    appendLine("<td>${escapeHtml(v.ruleName)}</td>")
                    appendLine("<td class=\"file-path\">${escapeHtml(v.filePath)}</td>")
                    appendLine("<td>${v.lineNumber}</td>")
                    appendLine("<td>${escapeHtml(v.message)}")
                    if (v.suggestedFix != null) {
                        appendLine("<br><em class=\"suggestion\">Suggestion: ${escapeHtml(v.suggestedFix)}</em>")
                    }
                    appendLine("</td>")
                    appendLine("<td>${"%.0f".format(v.confidence * 100)}%</td>")
                    appendLine("</tr>")
                }
                appendLine("</tbody></table>")
            }

            // Layer structure
            appendLine("<h2>Architecture Structure</h2>")
            appendLine("<div class=\"layers-grid\">")
            for ((layer, files) in data.layerClassification) {
                appendLine("<div class=\"layer-card\">")
                appendLine("<h3>$layer <span class=\"file-count\">(${files.size} files)</span></h3>")
                appendLine("<ul>")
                for (file in files.take(20)) {
                    appendLine("<li>${escapeHtml(file.relativePath.substringAfterLast("/"))}</li>")
                }
                if (files.size > 20) {
                    appendLine("<li class=\"more\">... and ${files.size - 20} more</li>")
                }
                appendLine("</ul></div>")
            }
            appendLine("</div>")

            // Footer
            appendLine("<div class=\"footer\">")
            appendLine("<p>Generated by <strong>CodeVigil</strong></p>")
            appendLine("</div>")
            appendLine("</body></html>")
        }
    }

    /**
     * Generate SARIF (Static Analysis Results Interchange Format) v2.1.0 output.
     * This is the industry standard for GitHub Code Scanning, GitLab SAST, and Azure DevOps.
     * Upload to GitHub via: gh api repos/{owner}/{repo}/code-scanning/sarifs -X POST
     */
    fun generateSarif(data: ReportData): String {
        val rules = data.violations.map { it.ruleId }.distinct()
        val ruleIndex = rules.withIndex().associate { (i, id) -> id to i }

        val sb = StringBuilder()
        sb.appendLine("{")
        sb.appendLine("""  "${"$"}schema": "https://raw.githubusercontent.com/oasis-tcs/sarif-spec/main/sarif-2.1/schema/sarif-schema-2.1.0.json",""")
        sb.appendLine("""  "version": "2.1.0",""")
        sb.appendLine("""  "runs": [{""")

        // Tool info
        sb.appendLine("""    "tool": {""")
        sb.appendLine("""      "driver": {""")
        sb.appendLine("""        "name": "CodeVigil",""")
        sb.appendLine("""        "informationUri": "https://github.com/codevigil/codevigil",""")
        sb.appendLine("""        "version": "1.0.0",""")
        sb.appendLine("""        "rules": [""")

        // Rule definitions
        rules.forEachIndexed { index, ruleId ->
            val sample = data.violations.first { it.ruleId == ruleId }
            val sarifLevel = when (sample.severity) {
                ViolationSeverity.ERROR -> "error"
                ViolationSeverity.WARNING -> "warning"
                ViolationSeverity.INFO -> "note"
            }
            sb.appendLine("          {")
            sb.appendLine("""            "id": "${escapeJson(ruleId.ifBlank { sample.ruleName })}",""")
            sb.appendLine("""            "name": "${escapeJson(sample.ruleName)}",""")
            sb.appendLine("""            "shortDescription": { "text": "${escapeJson(sample.ruleName)}" },""")
            sb.appendLine("""            "defaultConfiguration": { "level": "$sarifLevel" },""")
            sb.appendLine("""            "properties": { "category": "${sample.category}" }""")
            sb.append("          }")
            if (index < rules.size - 1) sb.append(",")
            sb.appendLine()
        }

        sb.appendLine("        ]")
        sb.appendLine("      }")
        sb.appendLine("    },")

        // Results
        sb.appendLine("""    "results": [""")
        data.violations.forEachIndexed { index, v ->
            val sarifLevel = when (v.severity) {
                ViolationSeverity.ERROR -> "error"
                ViolationSeverity.WARNING -> "warning"
                ViolationSeverity.INFO -> "note"
            }
            val rIdx = ruleIndex[v.ruleId] ?: 0
            sb.appendLine("      {")
            sb.appendLine("""        "ruleId": "${escapeJson(v.ruleId.ifBlank { v.ruleName })}",""")
            sb.appendLine("""        "ruleIndex": $rIdx,""")
            sb.appendLine("""        "level": "$sarifLevel",""")
            sb.appendLine("""        "message": { "text": "${escapeJson(v.message)}${if (v.suggestedFix != null) " Fix: ${escapeJson(v.suggestedFix)}" else ""}" },""")
            sb.appendLine("""        "locations": [{""")
            sb.appendLine("""          "physicalLocation": {""")
            sb.appendLine("""            "artifactLocation": { "uri": "${escapeJson(v.filePath)}" },""")
            sb.appendLine("""            "region": { "startLine": ${v.lineNumber.coerceAtLeast(1)} }""")
            sb.appendLine("          }")
            sb.appendLine("        }],")
            sb.appendLine("""        "properties": {""")
            sb.appendLine("""          "confidence": ${v.confidence},""")
            sb.appendLine("""          "category": "${v.category}",""")
            sb.appendLine("""          "patternName": "${escapeJson(v.patternName)}"${if (v.suggestedFix != null) """,
          "suggestedFix": "${escapeJson(v.suggestedFix)}"""" else ""}""")
            sb.appendLine("        }")
            sb.append("      }")
            if (index < data.violations.size - 1) sb.append(",")
            sb.appendLine()
        }
        sb.appendLine("    ],")

        // Invocation
        sb.appendLine("""    "invocations": [{""")
        sb.appendLine("""      "executionSuccessful": true,""")
        sb.appendLine("""      "endTimeUtc": "${escapeJson(data.scanTimestamp)}"  """)
        sb.appendLine("    }]")

        sb.appendLine("  }]")
        sb.appendLine("}")
        return sb.toString()
    }

    fun computeHealthScore(violations: List<Violation>, totalFiles: Int): Int {
        if (totalFiles == 0) return 100
        val errorPenalty = violations.count { it.severity == ViolationSeverity.ERROR } * 5
        val warningPenalty = violations.count { it.severity == ViolationSeverity.WARNING } * 2
        val infoPenalty = violations.count { it.severity == ViolationSeverity.INFO } * 1
        val totalPenalty = errorPenalty + warningPenalty + infoPenalty
        val maxPenalty = totalFiles * 5  // normalize against project size
        val score = 100 - ((totalPenalty.toDouble() / maxPenalty.coerceAtLeast(1)) * 100).toInt()
        return score.coerceIn(0, 100)
    }

    private fun summaryCard(label: String, value: String, iconClass: String): String {
        return """<div class="summary-card"><div class="card-value">$value</div><div class="card-label">$label</div></div>"""
    }

    private fun escapeJson(s: String): String {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
    }

    private fun escapeHtml(s: String): String {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    }

    private val CSS_STYLES = """
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #f5f6fa; color: #2d3436; padding: 2rem; }
        .header { text-align: center; margin-bottom: 2rem; }
        .header h1 { font-size: 2rem; color: #2d3436; }
        .subtitle { color: #636e72; font-size: 1.1rem; margin-top: 0.5rem; }
        .timestamp { color: #b2bec3; font-size: 0.9rem; margin-top: 0.25rem; }
        .score-card { width: 150px; height: 150px; border-radius: 50%; display: flex; flex-direction: column; align-items: center; justify-content: center; margin: 1.5rem auto; box-shadow: 0 4px 15px rgba(0,0,0,0.1); }
        .score-value { font-size: 3rem; font-weight: bold; }
        .score-label { font-size: 0.9rem; color: #636e72; }
        .score-good { background: linear-gradient(135deg, #00b894, #55efc4); color: white; }
        .score-warn { background: linear-gradient(135deg, #fdcb6e, #ffeaa7); color: #2d3436; }
        .score-bad { background: linear-gradient(135deg, #d63031, #ff7675); color: white; }
        .summary-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 1rem; margin: 1.5rem 0; }
        .summary-card { background: white; border-radius: 12px; padding: 1.5rem; text-align: center; box-shadow: 0 2px 8px rgba(0,0,0,0.06); }
        .card-value { font-size: 2rem; font-weight: bold; color: #2d3436; }
        .card-label { font-size: 0.85rem; color: #636e72; margin-top: 0.25rem; }
        h2 { margin: 2rem 0 1rem; color: #2d3436; border-bottom: 2px solid #dfe6e9; padding-bottom: 0.5rem; }
        .no-violations { background: #00b894; color: white; padding: 1.5rem; border-radius: 8px; text-align: center; font-size: 1.1rem; }
        .violations-table { width: 100%; border-collapse: collapse; background: white; border-radius: 8px; overflow: hidden; box-shadow: 0 2px 8px rgba(0,0,0,0.06); }
        .violations-table th { background: #2d3436; color: white; padding: 0.75rem 1rem; text-align: left; font-weight: 600; }
        .violations-table td { padding: 0.75rem 1rem; border-bottom: 1px solid #f1f2f6; vertical-align: top; }
        .violations-table tr:hover { background: #f8f9fa; }
        .file-path { font-family: 'Fira Code', monospace; font-size: 0.85rem; color: #6c5ce7; }
        .badge { padding: 0.25rem 0.5rem; border-radius: 4px; font-size: 0.75rem; font-weight: bold; }
        .severity-error .badge, .badge.severity-error { background: #ff7675; color: white; }
        .severity-warning .badge, .badge.severity-warning { background: #fdcb6e; color: #2d3436; }
        .severity-info .badge, .badge.severity-info { background: #74b9ff; color: white; }
        .suggestion { color: #00b894; font-size: 0.85rem; }
        .layers-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(250px, 1fr)); gap: 1rem; }
        .layer-card { background: white; border-radius: 8px; padding: 1rem; box-shadow: 0 2px 8px rgba(0,0,0,0.06); }
        .layer-card h3 { color: #6c5ce7; margin-bottom: 0.5rem; }
        .file-count { color: #b2bec3; font-weight: normal; }
        .layer-card ul { list-style: none; }
        .layer-card li { padding: 0.25rem 0; font-size: 0.85rem; color: #636e72; border-bottom: 1px solid #f8f9fa; }
        .more { color: #b2bec3; font-style: italic; }
        .footer { text-align: center; margin-top: 3rem; padding: 1rem; color: #b2bec3; font-size: 0.85rem; }
    """.trimIndent()
}
