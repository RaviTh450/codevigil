package com.codepattern.analysis

import com.codepattern.models.*
import com.codepattern.patterns.PatternMatcher
import com.codepattern.report.ReportGenerator
import com.codepattern.scanner.ScannedProject
import com.codepattern.standalone.StandaloneScanner

/**
 * Automated Code Review Gate — the single authority for code quality.
 *
 * Designed to be used by:
 * - AI agents (Claude, Copilot, etc.) as their immediate feedback loop
 * - CI/CD pipelines as a quality gate
 * - Human reviewers as a pre-review checklist
 *
 * Runs ALL analyzers in a single pass and produces a structured verdict:
 * PASS, WARN, or FAIL with detailed reasons per category.
 *
 * Usage for AI agents:
 *   The agent writes code → runs CodeReviewGate.review() → gets JSON verdict
 *   If FAIL: agent reads the violations and fixes before proceeding
 *   If WARN: agent decides whether to fix or accept
 *   If PASS: code is clean, proceed
 *
 * This replaces manual code review for architectural, complexity, memory,
 * thread safety, and code quality checks.
 */
object CodeReviewGate {

    enum class Verdict { PASS, WARN, FAIL }

    data class CategoryResult(
        val category: String,
        val verdict: Verdict,
        val violations: List<Violation>,
        val score: Int,           // 0-100 per category
        val summary: String
    )

    data class ReviewResult(
        val overallVerdict: Verdict,
        val overallScore: Int,          // 0-100
        val categories: List<CategoryResult>,
        val totalViolations: Int,
        val errorCount: Int,
        val warningCount: Int,
        val infoCount: Int,
        val blockers: List<String>,     // human-readable list of must-fix items
        val suggestions: List<String>,  // nice-to-have improvements
        val timestamp: String
    ) {
        fun toJson(): String {
            val blockersJson = blockers.joinToString(",\n    ") { "\"${escapeJson(it)}\"" }
            val suggestionsJson = suggestions.joinToString(",\n    ") { "\"${escapeJson(it)}\"" }
            val categoriesJson = categories.joinToString(",\n  ") { cat ->
                val violationsJson = cat.violations.take(10).joinToString(",\n      ") { v ->
                    """{
        "severity": "${v.severity}",
        "file": "${escapeJson(v.filePath)}",
        "line": ${v.lineNumber},
        "rule": "${escapeJson(v.ruleName)}",
        "message": "${escapeJson(v.message)}",
        "fix": ${if (v.suggestedFix != null) "\"${escapeJson(v.suggestedFix)}\"" else "null"},
        "confidence": ${v.confidence},
        "category": "${v.category}"
      }"""
                }
                """{
    "category": "${cat.category}",
    "verdict": "${cat.verdict}",
    "score": ${cat.score},
    "summary": "${escapeJson(cat.summary)}",
    "violations": [
      $violationsJson
    ]
  }"""
            }

            return """{
  "verdict": "$overallVerdict",
  "score": $overallScore,
  "totalViolations": $totalViolations,
  "errors": $errorCount,
  "warnings": $warningCount,
  "info": $infoCount,
  "timestamp": "$timestamp",
  "blockers": [
    $blockersJson
  ],
  "suggestions": [
    $suggestionsJson
  ],
  "categories": [
  $categoriesJson
  ]
}"""
        }

        fun toText(): String {
            val sb = StringBuilder()
            sb.appendLine("╔══════════════════════════════════════════╗")
            sb.appendLine("║       CODE REVIEW GATE — VERDICT        ║")
            sb.appendLine("╠══════════════════════════════════════════╣")
            sb.appendLine("║  ${overallVerdict.name.padEnd(38)}  ║")
            sb.appendLine("║  Score: $overallScore/100${" ".repeat(29 - overallScore.toString().length)}  ║")
            sb.appendLine("╚══════════════════════════════════════════╝")
            sb.appendLine()

            sb.appendLine("Violations: $totalViolations total ($errorCount errors, $warningCount warnings, $infoCount info)")
            sb.appendLine()

            if (blockers.isNotEmpty()) {
                sb.appendLine("BLOCKERS (must fix):")
                for (b in blockers) {
                    sb.appendLine("  ✗ $b")
                }
                sb.appendLine()
            }

            for (cat in categories) {
                val icon = when (cat.verdict) {
                    Verdict.PASS -> "✓"
                    Verdict.WARN -> "!"
                    Verdict.FAIL -> "✗"
                }
                sb.appendLine("[$icon] ${cat.category} — ${cat.verdict} (${cat.score}/100)")
                sb.appendLine("    ${cat.summary}")
                if (cat.violations.isNotEmpty()) {
                    for (v in cat.violations.take(5)) {
                        sb.appendLine("    - [${v.severity}] ${v.filePath}:${v.lineNumber} — ${v.message}")
                    }
                    if (cat.violations.size > 5) {
                        sb.appendLine("    ... and ${cat.violations.size - 5} more")
                    }
                }
                sb.appendLine()
            }

            if (suggestions.isNotEmpty()) {
                sb.appendLine("SUGGESTIONS:")
                for (s in suggestions) {
                    sb.appendLine("  → $s")
                }
            }

            return sb.toString()
        }
    }

    /**
     * Run a full code review on the project.
     * This is the main entry point for AI agents and CI/CD.
     */
    fun review(
        projectPath: String,
        patterns: List<PatternSpec>,
        options: ReviewOptions = ReviewOptions()
    ): ReviewResult {
        val scanner = StandaloneScanner()
        val project = scanner.scan(projectPath)
        return reviewProject(project, projectPath, patterns, options)
    }

    /**
     * Review an already-scanned project.
     */
    fun reviewProject(
        project: ScannedProject,
        basePath: String,
        patterns: List<PatternSpec>,
        options: ReviewOptions = ReviewOptions()
    ): ReviewResult {
        val matcher = PatternMatcher()
        val allViolations = mutableListOf<Violation>()
        val categoryResults = mutableListOf<CategoryResult>()

        // 1. Architecture pattern violations
        if (options.checkArchitecture && patterns.isNotEmpty()) {
            val archViolations = mutableListOf<Violation>()
            for (pattern in patterns) {
                archViolations += matcher.analyze(project, pattern)
            }
            allViolations += archViolations
            categoryResults += CategoryResult(
                category = "Architecture",
                verdict = verdictFor(archViolations),
                violations = archViolations,
                score = scoreFor(archViolations, project.files.size),
                summary = if (archViolations.isEmpty()) "No architectural violations found"
                else "${archViolations.size} pattern violation(s) against ${patterns.joinToString(", ") { it.name }}"
            )
        }

        // 2. Complexity analysis
        if (options.checkComplexity) {
            val complexities = BigOEstimator.analyze(project, basePath)
            val complexityViolations = BigOEstimator.toViolations(complexities)

            // Also add cyclomatic/cognitive complexity
            for (file in project.files) {
                try {
                    val lines = java.io.File("$basePath/${file.relativePath}").readLines()
                    val methods = ComplexityAnalyzer.extractMethods(lines, file.language)
                    for (m in methods) {
                        if (m.cyclomaticComplexity > 10) {
                            complexityViolations as MutableList += Violation(
                                ruleName = "Cyclomatic Complexity",
                                patternName = "Complexity Analysis",
                                message = "Method '${m.name}' has cyclomatic complexity ${m.cyclomaticComplexity} (threshold: 10). " +
                                        "Based on McCabe's complexity metric standard.",
                                severity = if (m.cyclomaticComplexity > 20) ViolationSeverity.ERROR else ViolationSeverity.WARNING,
                                filePath = file.relativePath,
                                lineNumber = m.startLine,
                                suggestedFix = "Extract conditional branches into separate methods or use polymorphism.",
                                category = ViolationCategory.COMPLEXITY,
                                confidence = 0.95,
                                ruleId = "review-cyclomatic"
                            )
                        }
                    }
                } catch (_: Exception) {}
            }

            allViolations += complexityViolations
            categoryResults += CategoryResult(
                category = "Complexity",
                verdict = verdictFor(complexityViolations),
                violations = complexityViolations,
                score = scoreFor(complexityViolations, project.files.size),
                summary = if (complexityViolations.isEmpty()) "All functions within acceptable complexity bounds"
                else "${complexityViolations.size} complexity issue(s) found"
            )
        }

        // 3. Memory & GC
        if (options.checkMemory) {
            val memoryIssues = MemoryLeakDetector.analyze(project, basePath)
            val memoryViolations = MemoryLeakDetector.toViolations(memoryIssues)
            allViolations += memoryViolations
            categoryResults += CategoryResult(
                category = "Memory & GC",
                verdict = verdictFor(memoryViolations),
                violations = memoryViolations,
                score = scoreFor(memoryViolations, project.files.size),
                summary = if (memoryViolations.isEmpty()) "No memory leaks or GC pressure issues detected"
                else "${memoryViolations.size} memory issue(s): ${memoryIssues.groupBy { it.type }.entries.joinToString(", ") { "${it.value.size} ${it.key.name.lowercase().replace("_", " ")}" }}"
            )
        }

        // 4. Thread Safety
        if (options.checkThreadSafety) {
            val threadIssues = ThreadSafetyAnalyzer.analyze(project, basePath)
            val threadViolations = ThreadSafetyAnalyzer.toViolations(threadIssues)
            allViolations += threadViolations
            categoryResults += CategoryResult(
                category = "Thread Safety",
                verdict = verdictFor(threadViolations),
                violations = threadViolations,
                score = scoreFor(threadViolations, project.files.size),
                summary = if (threadViolations.isEmpty()) "No thread safety issues detected"
                else "${threadViolations.size} concurrency issue(s): ${threadIssues.groupBy { it.type }.entries.joinToString(", ") { "${it.value.size} ${it.key.name.lowercase().replace("_", " ")}" }}"
            )
        }

        // 5. Circular Dependencies
        if (options.checkCircularDeps) {
            val cycles = CircularDependencyDetector.detectCycles(project)
            val circularViolations = CircularDependencyDetector.toViolations(
                cycles, "Circular Dependencies", "Circular Dependency", "review-circular",
                ViolationSeverity.ERROR
            )
            allViolations += circularViolations
            categoryResults += CategoryResult(
                category = "Circular Dependencies",
                verdict = if (circularViolations.isEmpty()) Verdict.PASS else Verdict.FAIL,
                violations = circularViolations,
                score = if (circularViolations.isEmpty()) 100 else maxOf(0, 100 - circularViolations.size * 20),
                summary = if (circularViolations.isEmpty()) "No circular dependencies"
                else "${cycles.size} circular dependency chain(s) found"
            )
        }

        // 6. Code Smells
        if (options.checkCodeSmells) {
            val smellViolations = mutableListOf<Violation>()
            for (file in project.files) {
                try {
                    val lines = java.io.File("$basePath/${file.relativePath}").readLines()
                    smellViolations += CodeSmellDetector.detect(file, lines,
                        mapOf("smell_type" to "GOD_CLASS"), "Code Smells", "God Class", "review-godclass")
                    smellViolations += CodeSmellDetector.detect(file, lines,
                        mapOf("smell_type" to "FEATURE_ENVY"), "Code Smells", "Feature Envy", "review-featureenvy")
                    smellViolations += CodeSmellDetector.detect(file, lines,
                        mapOf("smell_type" to "LONG_PARAMETER_LIST", "max_parameters" to 5),
                        "Code Smells", "Long Parameter List", "review-longparams")
                } catch (_: Exception) {}
            }
            allViolations += smellViolations
            categoryResults += CategoryResult(
                category = "Code Smells",
                verdict = verdictFor(smellViolations),
                violations = smellViolations,
                score = scoreFor(smellViolations, project.files.size),
                summary = if (smellViolations.isEmpty()) "No code smells detected"
                else "${smellViolations.size} code smell(s) found"
            )
        }

        // 7. Naming Conventions
        if (options.checkNaming) {
            val namingIssues = NamingConventionChecker.analyze(project, basePath)
            val namingViolations = NamingConventionChecker.toViolations(namingIssues)
            allViolations += namingViolations
            categoryResults += CategoryResult(
                category = "Naming Conventions",
                verdict = verdictFor(namingViolations),
                violations = namingViolations,
                score = scoreFor(namingViolations, project.files.size),
                summary = if (namingViolations.isEmpty()) "All names follow conventions"
                else "${namingViolations.size} naming issue(s) found"
            )
        }

        // 8. Security
        if (options.checkSecurity) {
            val securityIssues = SecurityScanner.analyze(project, basePath)
            val securityViolations = SecurityScanner.toViolations(securityIssues)
            allViolations += securityViolations
            categoryResults += CategoryResult(
                category = "Security",
                verdict = verdictFor(securityViolations),
                violations = securityViolations,
                score = scoreFor(securityViolations, project.files.size),
                summary = if (securityViolations.isEmpty()) "No security vulnerabilities detected"
                else "${securityViolations.size} security issue(s): ${securityIssues.groupBy { it.type }.entries.joinToString(", ") { "${it.value.size} ${it.key.name.lowercase().replace("_", " ")}" }}"
            )
        }

        // 8. Dead Code
        if (options.checkDeadCode) {
            val deadCodeIssues = DeadCodeDetector.analyze(project, basePath)
            val deadCodeViolations = DeadCodeDetector.toViolations(deadCodeIssues)
            allViolations += deadCodeViolations
            categoryResults += CategoryResult(
                category = "Dead Code",
                verdict = verdictFor(deadCodeViolations),
                violations = deadCodeViolations,
                score = scoreFor(deadCodeViolations, project.files.size),
                summary = if (deadCodeViolations.isEmpty()) "No dead code detected"
                else "${deadCodeViolations.size} dead code issue(s) found"
            )
        }

        // 9. Duplicate Code
        if (options.checkDuplicates) {
            val dupReport = DuplicateCodeDetector.analyze(project, basePath)
            val dupViolations = DuplicateCodeDetector.toViolations(dupReport)
            allViolations += dupViolations
            categoryResults += CategoryResult(
                category = "Duplicate Code",
                verdict = verdictFor(dupViolations),
                violations = dupViolations,
                score = scoreFor(dupViolations, project.files.size),
                summary = if (dupViolations.isEmpty()) "No significant code duplication"
                else "${dupReport.totalDuplicateBlocks} duplicate block(s), ${dupReport.totalDuplicateLines} lines (${"%.1f".format(dupReport.duplicationPercentage)}% duplication)"
            )
        }

        // Compute overall
        val errorCount = allViolations.count { it.severity == ViolationSeverity.ERROR }
        val warningCount = allViolations.count { it.severity == ViolationSeverity.WARNING }
        val infoCount = allViolations.count { it.severity == ViolationSeverity.INFO }
        val overallScore = ReportGenerator.computeHealthScore(allViolations, project.files.size)

        val overallVerdict = when {
            errorCount > 0 -> Verdict.FAIL
            warningCount > 5 -> Verdict.WARN
            overallScore < 60 -> Verdict.FAIL
            overallScore < 80 -> Verdict.WARN
            else -> Verdict.PASS
        }

        val blockers = allViolations.filter { it.severity == ViolationSeverity.ERROR }.map {
            "${it.filePath}:${it.lineNumber} — ${it.ruleName}: ${it.message}"
        }

        val suggestions = allViolations
            .filter { it.severity == ViolationSeverity.INFO && it.suggestedFix != null }
            .take(10)
            .map { "${it.filePath}:${it.lineNumber} — ${it.suggestedFix}" }

        return ReviewResult(
            overallVerdict = overallVerdict,
            overallScore = overallScore,
            categories = categoryResults,
            totalViolations = allViolations.size,
            errorCount = errorCount,
            warningCount = warningCount,
            infoCount = infoCount,
            blockers = blockers,
            suggestions = suggestions,
            timestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        )
    }

    /**
     * Quick review of a single file (for real-time AI agent feedback).
     */
    fun reviewFile(
        filePath: String,
        basePath: String,
        patterns: List<PatternSpec>
    ): ReviewResult {
        val scanner = StandaloneScanner()
        val scannedFile = scanner.scanSingleFile(filePath, basePath)
        val project = com.codepattern.scanner.ScannedProject(basePath = basePath, files = listOf(scannedFile))
        return reviewProject(project, basePath, patterns, ReviewOptions())
    }

    data class ReviewOptions(
        val checkArchitecture: Boolean = true,
        val checkComplexity: Boolean = true,
        val checkMemory: Boolean = true,
        val checkThreadSafety: Boolean = true,
        val checkCircularDeps: Boolean = true,
        val checkCodeSmells: Boolean = true,
        val checkNaming: Boolean = true,
        val checkSecurity: Boolean = true,
        val checkDeadCode: Boolean = true,
        val checkDuplicates: Boolean = true
    )

    private fun verdictFor(violations: List<Violation>): Verdict {
        val errors = violations.count { it.severity == ViolationSeverity.ERROR }
        val warnings = violations.count { it.severity == ViolationSeverity.WARNING }
        return when {
            errors > 0 -> Verdict.FAIL
            warnings > 3 -> Verdict.WARN
            else -> Verdict.PASS
        }
    }

    private fun scoreFor(violations: List<Violation>, totalFiles: Int): Int {
        if (violations.isEmpty()) return 100
        val penalty = violations.sumOf { v: Violation -> when (v.severity) {
            ViolationSeverity.ERROR -> 15
            ViolationSeverity.WARNING -> 5
            ViolationSeverity.INFO -> 1
        } as Int}
        return maxOf(0, 100 - penalty)
    }

    private fun escapeJson(s: String): String {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")
    }
}
