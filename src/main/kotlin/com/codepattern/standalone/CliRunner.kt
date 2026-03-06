package com.codepattern.standalone

import com.codepattern.analysis.*
import com.codepattern.models.*
import com.codepattern.patterns.PatternMatcher
import com.codepattern.report.ReportGenerator
import java.io.File
import java.io.InputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import org.yaml.snakeyaml.Yaml

/**
 * CLI entry point for cross-IDE and CI/CD usage.
 *
 * Usage:
 *   java -jar codevigil.jar [options] <project-path>
 *
 * Options:
 *   --pattern <name>       Pattern to validate (mvc, clean-architecture, solid, ddd, etc.)
 *   --all-patterns         Run all available patterns
 *   --format <format>      Output format: text (default), json, html
 *   --output <file>        Write report to file instead of stdout
 *   --min-severity <sev>   Minimum severity to report: error, warning, info (default: info)
 *   --fail-on <severity>   Exit with code 1 if violations of this severity exist
 *   --custom-pattern <file> Path to a custom pattern YAML file
 *   --exclude <dirs>       Comma-separated directories to exclude
 *   --complexity           Include complexity analysis
 *   --circular-deps        Include circular dependency detection
 *   --coupling             Include coupling analysis
 *   --fitness              Run fitness functions and report pass/fail
 *   --save-baseline        Save current scan as architecture baseline
 *   --drift                Compare current scan against saved baseline
 *   --graph <format>       Generate dependency graph: ascii, mermaid, dot
 *   --call-graph <format>  Generate function call graph: ascii, mermaid, dot
 *   --memory               Detect memory leaks, GC pressure, resource leaks
 *   --threads              Detect thread safety issues, race conditions, deadlocks
 *   --bigo                 Show Big-O time/space complexity for every function
 *   --review               Full automated code review (replaces manual review)
 *   --incremental          Only scan files changed since last commit
 *   --security             Detect security vulnerabilities (OWASP Top 10, hardcoded secrets)
 *   --dead-code            Detect unused imports, unreachable code, empty catch blocks
 *   --duplicates           Detect copy-pasted / duplicate code blocks
 *   --naming               Check naming conventions (PascalCase, camelCase, snake_case)
 *   --api                  Validate REST API contracts (auth, validation, error handling)
 *   --watch                Continuous watch mode — re-analyze on file changes
 *   --init                 Initialize project: generates .codevigil.md, pre-commit hook, GitHub Action
 *   --diff                 Review only files changed in git (AI agent fast-check mode)
 *   --install-hook         Install pre-commit hook to .git/hooks/
 *   --help                 Show help
 */
fun main(args: Array<String>) {
    val cli = CliRunner()
    val exitCode = cli.run(args)
    if (exitCode != 0) {
        System.exit(exitCode)
    }
}

class CliRunner {

    fun run(args: Array<String>): Int {
        if (args.isEmpty() || "--help" in args || "-h" in args) {
            printHelp()
            return 0
        }

        val config = parseArgs(args)

        if (config.projectPath == null) {
            System.err.println("Error: No project path specified.")
            printHelp()
            return 2
        }

        val projectDir = File(config.projectPath)
        if (!projectDir.isDirectory) {
            System.err.println("Error: '${config.projectPath}' is not a valid directory.")
            return 2
        }

        // --watch: continuous file watching mode
        if (config.watch) {
            return runWatchMode(config)
        }

        // --init: generate all config files
        if (config.init) {
            val patterns = loadPatterns(config)
            val files = AIRuleGenerator.initProject(config.projectPath, patterns)
            for ((relativePath, content) in files) {
                val file = File(config.projectPath, relativePath)
                file.parentFile?.mkdirs()
                file.writeText(content)
                println("Created: $relativePath")
            }
            println()
            println("Project initialized! Files created:")
            println("  .codevigil.md     — AI agents read this for architecture rules")
            println("  .codevigil.yml    — Configuration file")
            println("  .codevigil/hooks/ — Pre-commit hook (run --install-hook to activate)")
            println("  .github/workflows/  — GitHub Action for PR reviews")
            println()
            println("Next steps:")
            println("  1. Commit .codevigil.md to git (AI agents will read it automatically)")
            println("  2. Run --install-hook to enable pre-commit checks")
            println("  3. Push .github/workflows/ for automated PR reviews")
            return 0
        }

        // --install-hook: copy pre-commit hook to .git/hooks/
        if (config.installHook) {
            val gitHooksDir = File(config.projectPath, ".git/hooks")
            if (!gitHooksDir.isDirectory) {
                System.err.println("Error: No .git/hooks directory found. Is this a git repository?")
                return 2
            }
            val hookContent = AIRuleGenerator.generatePreCommitHook()
            val hookFile = File(gitHooksDir, "pre-commit")
            if (hookFile.exists()) {
                System.err.println("Warning: .git/hooks/pre-commit already exists. Backing up to pre-commit.bak")
                hookFile.copyTo(File(gitHooksDir, "pre-commit.bak"), overwrite = true)
            }
            hookFile.writeText(hookContent)
            hookFile.setExecutable(true)
            println("Pre-commit hook installed to .git/hooks/pre-commit")
            return 0
        }

        // --diff: review only changed files
        if (config.diff) {
            val patterns = loadPatterns(config).ifEmpty { loadAllBuiltinPatterns() }
            val result = AIRuleGenerator.analyzeDiff(config.projectPath, patterns)
            when (config.format) {
                "json" -> writeOutput(result.toJson(), config.outputFile)
                else -> writeOutput(result.toText(), config.outputFile)
            }
            return if (result.overallVerdict == CodeReviewGate.Verdict.FAIL) 1 else 0
        }

        // Load patterns
        val patterns = loadPatterns(config)

        // --review mode: runs all checks, doesn't require pattern selection
        if (config.review) {
            val reviewPatterns = patterns.ifEmpty { loadAllBuiltinPatterns() }
            val result = CodeReviewGate.review(config.projectPath, reviewPatterns)
            when (config.format) {
                "json" -> writeOutput(result.toJson(), config.outputFile)
                else -> writeOutput(result.toText(), config.outputFile)
            }
            return if (result.overallVerdict == CodeReviewGate.Verdict.FAIL) 1 else 0
        }

        if (patterns.isEmpty()) {
            System.err.println("Error: No patterns found. Use --pattern or --all-patterns.")
            return 2
        }

        // Scan project
        val excludeDirs = config.excludeDirs ?: emptySet()
        val scanner = StandaloneScanner(
            excludedDirs = StandaloneScanner.DEFAULT_EXCLUDED_DIRS + excludeDirs
        )

        // Incremental mode: only scan changed files
        val scannedProject = if (config.incremental) {
            val changes = IncrementalScanner.getChangedFilesSinceLastCommit(config.projectPath)
            if (changes.isFullScan) {
                System.err.println("Warning: Could not determine changed files, falling back to full scan.")
                scanner.scan(config.projectPath)
            } else {
                System.err.println("Incremental scan: ${changes.allChanged.size} changed file(s)")
                scanner.scanFiles(config.projectPath, changes.allChanged)
            }
        } else {
            scanner.scan(config.projectPath)
        }

        val matcher = PatternMatcher()
        val allViolations = mutableListOf<Violation>()

        for (pattern in patterns) {
            allViolations += matcher.analyze(scannedProject, pattern)
        }

        // Additional analyses
        if (config.includeComplexity) {
            allViolations += runComplexityAnalysis(scannedProject, config.projectPath)
        }
        if (config.includeCircularDeps) {
            val cycles = CircularDependencyDetector.detectCycles(scannedProject)
            allViolations += CircularDependencyDetector.toViolations(
                cycles, "Circular Dependencies", "Circular Dependency", "circular-dep",
                ViolationSeverity.WARNING
            )
        }
        if (config.includeCoupling) {
            val couplingMetrics = CouplingAnalyzer.analyze(scannedProject)
            for ((_, metrics) in couplingMetrics) {
                if (metrics.efferentCoupling > 15) {
                    allViolations += Violation(
                        ruleName = "High Efferent Coupling",
                        patternName = "Code Quality",
                        message = "File has ${metrics.efferentCoupling} outgoing imports (instability: ${"%.2f".format(metrics.instability)}).",
                        severity = if (metrics.efferentCoupling > 25) ViolationSeverity.ERROR else ViolationSeverity.WARNING,
                        filePath = metrics.filePath,
                        lineNumber = 1,
                        suggestedFix = "Reduce dependencies by applying Dependency Inversion or extracting interfaces.",
                        category = ViolationCategory.COUPLING,
                        confidence = 0.90,
                        ruleId = "cli-coupling"
                    )
                }
            }
        }

        // Memory analysis
        if (config.memory) {
            val memoryIssues = MemoryLeakDetector.analyze(scannedProject, config.projectPath)
            allViolations += MemoryLeakDetector.toViolations(memoryIssues)
        }

        // Thread safety analysis
        if (config.threads) {
            val threadIssues = ThreadSafetyAnalyzer.analyze(scannedProject, config.projectPath)
            allViolations += ThreadSafetyAnalyzer.toViolations(threadIssues)
        }

        // Security analysis
        if (config.security) {
            val securityIssues = SecurityScanner.analyze(scannedProject, config.projectPath)
            allViolations += SecurityScanner.toViolations(securityIssues)
            if (securityIssues.isNotEmpty()) {
                println(SecurityScanner.generateReport(securityIssues))
                println()
            }
        }

        // Dead code analysis
        if (config.deadCode) {
            val deadCodeIssues = DeadCodeDetector.analyze(scannedProject, config.projectPath)
            allViolations += DeadCodeDetector.toViolations(deadCodeIssues)
            if (deadCodeIssues.isNotEmpty()) {
                println(DeadCodeDetector.generateReport(deadCodeIssues))
                println()
            }
        }

        // Duplicate code analysis
        if (config.duplicates) {
            val dupReport = DuplicateCodeDetector.analyze(scannedProject, config.projectPath)
            allViolations += DuplicateCodeDetector.toViolations(dupReport)
            if (dupReport.totalDuplicateBlocks > 0) {
                println(DuplicateCodeDetector.generateReport(dupReport))
                println()
            }
        }

        // Naming conventions
        if (config.naming) {
            val namingIssues = NamingConventionChecker.analyze(scannedProject, config.projectPath)
            allViolations += NamingConventionChecker.toViolations(namingIssues)
            if (namingIssues.isNotEmpty()) {
                println(NamingConventionChecker.generateReport(namingIssues))
                println()
            }
        }

        // API contract validation
        if (config.api) {
            val apiIssues = ApiContractValidator.analyze(scannedProject, config.projectPath)
            allViolations += ApiContractValidator.toViolations(apiIssues)
            if (apiIssues.isNotEmpty()) {
                println(ApiContractValidator.generateReport(apiIssues))
                println()
            }
        }

        // Big-O complexity analysis
        if (config.bigo) {
            val complexities = BigOEstimator.analyze(scannedProject, config.projectPath)
            allViolations += BigOEstimator.toViolations(complexities)
            println(BigOEstimator.generateReport(complexities))
            println()
        }

        // Call graph analysis
        if (config.callGraphFormat != null) {
            val callGraph = CallGraphAnalyzer.buildCallGraph(scannedProject, config.projectPath)
            val stats = CallGraphAnalyzer.analyzeGraph(callGraph)
            allViolations += CallGraphAnalyzer.toViolations(callGraph, stats)

            val graphOutput = when (config.callGraphFormat) {
                "mermaid" -> CallGraphAnalyzer.generateMermaid(callGraph, stats)
                "dot" -> CallGraphAnalyzer.generateDot(callGraph, stats)
                else -> CallGraphAnalyzer.generateAscii(callGraph, stats)
            }
            println(graphOutput)
            println()
        }

        // Filter by severity
        val filtered = allViolations.filter { it.severity.isAtLeast(config.minSeverity) }

        // Generate output
        val patternName = patterns.joinToString(", ") { it.name }
        val totalFiles = scannedProject.files.size
        val healthScore = ReportGenerator.computeHealthScore(filtered, totalFiles)

        // Dependency graph
        if (config.graphFormat != null && patterns.size == 1) {
            val edges = DependencyGraphGenerator.buildActualGraph(scannedProject, patterns[0], matcher)
            val graphOutput = when (config.graphFormat) {
                "mermaid" -> DependencyGraphGenerator.generateMermaid(patterns[0], edges)
                "dot" -> DependencyGraphGenerator.generateDot(patterns[0], edges)
                else -> DependencyGraphGenerator.generateAscii(patterns[0], edges)
            }
            writeOutput(graphOutput, config.outputFile?.let { "${it}.graph.${config.graphFormat}" })
            if (config.outputFile == null) println() // separator before main report
        }

        // Save baseline
        if (config.saveBaseline && patterns.isNotEmpty()) {
            val file = ArchitectureDriftTracker.saveBaseline(
                config.projectPath, patternName, healthScore, filtered
            )
            System.err.println("Baseline saved to: ${file.absolutePath}")
        }

        // Drift detection
        if (config.drift && patterns.isNotEmpty()) {
            val baseline = ArchitectureDriftTracker.loadBaseline(config.projectPath, patternName)
            if (baseline != null) {
                val driftReport = ArchitectureDriftTracker.detectDrift(baseline, healthScore, filtered)
                println(driftReport.summary)
                println()
            } else {
                System.err.println("No baseline found for '$patternName'. Use --save-baseline first.")
            }
        }

        // Fitness functions
        if (config.fitness) {
            val fitnessResults = FitnessFunction.evaluate(filtered, totalFiles)
            println(FitnessFunction.generateReport(fitnessResults))
            println()
            val anyFailed = fitnessResults.any { !it.passed }
            if (anyFailed && config.failOnSeverity == null) {
                // Fitness failure acts as an implicit --fail-on
                return 1
            }
        }

        val classification = if (patterns.size == 1) matcher.classifyFiles(scannedProject, patterns[0]) else emptyMap()
        val reportData = ReportGenerator.ReportData(
            patternName = patternName,
            patternDescription = patterns.joinToString("; ") { it.description },
            scanTimestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            totalFiles = totalFiles,
            totalViolations = filtered.size,
            errorCount = filtered.count { it.severity == ViolationSeverity.ERROR },
            warningCount = filtered.count { it.severity == ViolationSeverity.WARNING },
            infoCount = filtered.count { it.severity == ViolationSeverity.INFO },
            healthScore = healthScore,
            violations = filtered,
            layerClassification = classification
        )

        when (config.format) {
            "json" -> writeOutput(ReportGenerator.generateJson(reportData), config.outputFile)
            "html" -> writeOutput(ReportGenerator.generateHtml(reportData), config.outputFile)
            "sarif" -> writeOutput(ReportGenerator.generateSarif(reportData), config.outputFile)
            else -> printTextReport(filtered, patternName, totalFiles, healthScore, config.outputFile)
        }

        // Exit code
        if (config.failOnSeverity != null) {
            val hasFailures = filtered.any { it.severity.isAtLeast(config.failOnSeverity) }
            return if (hasFailures) 1 else 0
        }
        return 0
    }

    private fun runWatchMode(config: CliConfig): Int {
        val patterns = loadPatterns(config).ifEmpty { loadAllBuiltinPatterns() }
        println("Watching ${config.projectPath} for changes... (Ctrl+C to stop)")
        println()

        val projectDir = File(config.projectPath!!)
        var lastModified = scanLastModified(projectDir)
        var scanCount = 0

        while (true) {
            Thread.sleep(1500) // poll every 1.5 seconds
            val currentModified = scanLastModified(projectDir)
            if (currentModified > lastModified) {
                lastModified = currentModified
                scanCount++
                val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                System.err.println("[$timestamp] Change detected (scan #$scanCount)...")

                val result = CodeReviewGate.review(config.projectPath, patterns)
                val icon = when (result.overallVerdict) {
                    CodeReviewGate.Verdict.PASS -> "PASS"
                    CodeReviewGate.Verdict.WARN -> "WARN"
                    CodeReviewGate.Verdict.FAIL -> "FAIL"
                }
                println("[$timestamp] $icon — Score: ${result.overallScore}/100 | Errors: ${result.errorCount} | Warnings: ${result.warningCount}")

                if (result.blockers.isNotEmpty()) {
                    for (b in result.blockers.take(5)) {
                        println("  BLOCK: $b")
                    }
                }
                println()
            }
        }
    }

    private fun scanLastModified(dir: File): Long {
        val extensions = setOf("kt", "java", "py", "ts", "js", "go", "rs", "cs", "rb", "swift", "php")
        var latest = 0L
        dir.walkTopDown()
            .filter { it.isFile && it.extension in extensions }
            .filter { !it.path.contains("/build/") && !it.path.contains("/node_modules/") && !it.path.contains("/.git/") }
            .forEach { if (it.lastModified() > latest) latest = it.lastModified() }
        return latest
    }

    private fun loadPatterns(config: CliConfig): List<PatternSpec> {
        val patterns = mutableListOf<PatternSpec>()
        val yaml = Yaml()

        if (config.customPatternFile != null) {
            val file = File(config.customPatternFile)
            if (file.isFile) {
                file.inputStream().use { stream ->
                    parsePatternYaml(yaml, stream)?.let { patterns += it }
                }
            }
        }

        val patternFiles = mapOf(
            "mvc" to "patterns/mvc.yml",
            "clean-architecture" to "patterns/clean-architecture.yml",
            "solid" to "patterns/solid.yml",
            "ddd" to "patterns/ddd.yml",
            "repository" to "patterns/repository.yml",
            "hexagonal" to "patterns/hexagonal.yml",
            "cqrs" to "patterns/cqrs.yml",
            "microservices" to "patterns/microservices.yml",
            "layered" to "patterns/layered.yml",
            "observer" to "patterns/observer.yml",
            "factory" to "patterns/factory.yml",
            "code-quality" to "patterns/code-quality.yml"
        )

        if (config.allPatterns) {
            for ((_, path) in patternFiles) {
                loadBuiltinPattern(yaml, path)?.let { patterns += it }
            }
        } else if (config.patternName != null) {
            val path = patternFiles[config.patternName.lowercase()]
            if (path != null) {
                loadBuiltinPattern(yaml, path)?.let { patterns += it }
            } else {
                System.err.println("Warning: Unknown pattern '${config.patternName}'. Available: ${patternFiles.keys.joinToString(", ")}")
            }
        }

        return patterns
    }

    private fun loadAllBuiltinPatterns(): List<PatternSpec> {
        val yaml = Yaml()
        val patternFiles = listOf(
            "patterns/mvc.yml", "patterns/clean-architecture.yml", "patterns/solid.yml",
            "patterns/ddd.yml", "patterns/repository.yml", "patterns/hexagonal.yml",
            "patterns/cqrs.yml", "patterns/microservices.yml", "patterns/layered.yml",
            "patterns/observer.yml", "patterns/factory.yml", "patterns/code-quality.yml"
        )
        return patternFiles.mapNotNull { loadBuiltinPattern(yaml, it) }
    }

    private fun loadBuiltinPattern(yaml: Yaml, resourcePath: String): PatternSpec? {
        val stream = javaClass.classLoader.getResourceAsStream(resourcePath) ?: return null
        return parsePatternYaml(yaml, stream)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parsePatternYaml(yaml: Yaml, input: InputStream): PatternSpec? {
        return try {
            val data = yaml.load<Map<String, Any>>(input) ?: return null
            val name = data["name"] as? String ?: return null
            val description = data["description"] as? String ?: ""

            val layersData = data["layers"] as? List<Map<String, Any>> ?: emptyList()
            val layers = layersData.map { ld ->
                Layer(
                    name = ld["name"] as? String ?: "",
                    description = ld["description"] as? String ?: "",
                    filePatterns = (ld["file_patterns"] as? List<String>) ?: emptyList(),
                    namingConventions = (ld["naming_conventions"] as? List<String>) ?: emptyList(),
                    allowedDependencies = (ld["allowed_dependencies"] as? List<String>) ?: emptyList()
                )
            }

            val rulesData = data["rules"] as? List<Map<String, Any>> ?: emptyList()
            val rules = rulesData.map { rd ->
                PatternRule(
                    id = rd["id"] as? String ?: "",
                    name = rd["name"] as? String ?: "",
                    description = rd["description"] as? String ?: "",
                    severity = when ((rd["severity"] as? String)?.uppercase()) {
                        "ERROR" -> ViolationSeverity.ERROR
                        "INFO" -> ViolationSeverity.INFO
                        else -> ViolationSeverity.WARNING
                    },
                    type = parseRuleType(rd["type"] as? String),
                    config = (rd["config"] as? Map<String, Any>) ?: emptyMap()
                )
            }

            PatternSpec(name = name, description = description, layers = layers, rules = rules)
        } catch (e: Exception) {
            System.err.println("Warning: Failed to parse pattern YAML: ${e.message}")
            null
        }
    }

    private fun parseRuleType(value: String?): RuleType {
        return try {
            RuleType.valueOf(value?.uppercase() ?: "FILE_ORGANIZATION")
        } catch (_: Exception) {
            RuleType.FILE_ORGANIZATION
        }
    }

    private fun runComplexityAnalysis(project: com.codepattern.scanner.ScannedProject, basePath: String): List<Violation> {
        val violations = mutableListOf<Violation>()
        for (file in project.files) {
            try {
                val lines = File("$basePath/${file.relativePath}").readLines()
                val methods = ComplexityAnalyzer.extractMethods(lines, file.language)
                for (method in methods) {
                    if (method.cyclomaticComplexity > 10) {
                        violations += Violation(
                            ruleName = "Cyclomatic Complexity",
                            patternName = "Code Quality",
                            message = "Method '${method.name}' has cyclomatic complexity ${method.cyclomaticComplexity} (threshold: 10).",
                            severity = if (method.cyclomaticComplexity > 20) ViolationSeverity.ERROR else ViolationSeverity.WARNING,
                            filePath = file.relativePath,
                            lineNumber = method.startLine,
                            suggestedFix = "Break down this method into smaller, simpler methods.",
                            category = ViolationCategory.COMPLEXITY,
                            confidence = 0.95,
                            ruleId = "cli-cyclomatic"
                        )
                    }
                    if (method.cognitiveComplexity > 15) {
                        violations += Violation(
                            ruleName = "Cognitive Complexity",
                            patternName = "Code Quality",
                            message = "Method '${method.name}' has cognitive complexity ${method.cognitiveComplexity} (threshold: 15).",
                            severity = ViolationSeverity.WARNING,
                            filePath = file.relativePath,
                            lineNumber = method.startLine,
                            suggestedFix = "Reduce nesting depth and simplify control flow.",
                            category = ViolationCategory.COMPLEXITY,
                            confidence = 0.90,
                            ruleId = "cli-cognitive"
                        )
                    }
                    if (method.lineCount > 50) {
                        violations += Violation(
                            ruleName = "Method Length",
                            patternName = "Code Quality",
                            message = "Method '${method.name}' has ${method.lineCount} lines (threshold: 50).",
                            severity = ViolationSeverity.WARNING,
                            filePath = file.relativePath,
                            lineNumber = method.startLine,
                            suggestedFix = "Extract parts of this method into smaller helper methods.",
                            category = ViolationCategory.COMPLEXITY,
                            confidence = 0.95,
                            ruleId = "cli-method-length"
                        )
                    }
                }
            } catch (_: Exception) {
                // Skip files that can't be read
            }
        }
        return violations
    }

    private fun printTextReport(violations: List<Violation>, patternName: String, totalFiles: Int, healthScore: Int, outputFile: String?) {
        val sb = StringBuilder()
        sb.appendLine("=== CodeVigil Analysis Report ===")
        sb.appendLine("Pattern: $patternName")
        sb.appendLine("Files scanned: $totalFiles")
        sb.appendLine("Health score: $healthScore/100")
        sb.appendLine("Violations: ${violations.size}")
        sb.appendLine("  Errors:   ${violations.count { it.severity == ViolationSeverity.ERROR }}")
        sb.appendLine("  Warnings: ${violations.count { it.severity == ViolationSeverity.WARNING }}")
        sb.appendLine("  Info:     ${violations.count { it.severity == ViolationSeverity.INFO }}")
        sb.appendLine()

        if (violations.isEmpty()) {
            sb.appendLine("No violations found!")
        } else {
            for (v in violations) {
                sb.appendLine("[${v.severity}] ${v.filePath}:${v.lineNumber}")
                sb.appendLine("  ${v.ruleName}: ${v.message}")
                if (v.suggestedFix != null) {
                    sb.appendLine("  Fix: ${v.suggestedFix}")
                }
                sb.appendLine()
            }
        }

        writeOutput(sb.toString(), outputFile)
    }

    private fun writeOutput(content: String, outputFile: String?) {
        if (outputFile != null) {
            File(outputFile).writeText(content)
            System.err.println("Report written to: $outputFile")
        } else {
            println(content)
        }
    }

    private fun printHelp() {
        println("""
            CodeVigil - CLI

            Usage: java -jar codevigil.jar [options] <project-path>

            Options:
              --pattern <name>         Pattern to validate against:
                                         mvc, clean-architecture, solid, ddd, repository,
                                         hexagonal, cqrs, microservices, layered, observer,
                                         factory, code-quality
              --all-patterns           Run all available patterns
              --format <format>        Output format: text (default), json, html
              --output <file>          Write report to file (default: stdout)
              --min-severity <sev>     Minimum severity: error, warning, info (default: info)
              --fail-on <severity>     Exit code 1 if violations of this severity+ exist
              --custom-pattern <file>  Path to a custom pattern YAML file
              --exclude <dirs>         Comma-separated directories to exclude
              --complexity             Include cyclomatic/cognitive complexity analysis
              --circular-deps          Include circular dependency detection
              --coupling               Include coupling analysis
              --fitness                Run architecture fitness functions (CI/CD gate)
              --save-baseline          Save current scan as architecture baseline
              --drift                  Compare current scan against saved baseline
              --graph <format>         Generate dependency graph: ascii, mermaid, dot
              --call-graph <format>    Generate function call graph: ascii, mermaid, dot
              --memory                 Detect memory leaks, GC pressure, resource leaks
              --threads                Detect thread safety issues, race conditions, deadlocks
              --security               Detect security vulnerabilities (OWASP Top 10)
              --dead-code              Detect unused imports, unreachable code, empty blocks
              --duplicates             Detect copy-pasted / duplicate code blocks
              --naming                 Check naming conventions (PascalCase, camelCase, etc.)
              --api                    Validate REST API contracts and endpoint conventions
              --incremental            Only scan files changed since last commit
              --watch                  Continuous watch mode — re-analyze on file changes

            Output Formats:
              --format text            Human-readable text (default)
              --format json            JSON for machine consumption
              --format html            HTML report with dashboard
              --format sarif           SARIF v2.1.0 for GitHub Code Scanning / GitLab SAST

            AI Agent & Setup Commands:
              --init                   Initialize project: .codevigil.md, hooks, GitHub Action
              --diff                   Review only git-changed files (fast AI agent check)
              --install-hook           Install pre-commit hook to .git/hooks/
              -h, --help               Show this help

            Examples:
              # Quick start (generates .codevigil.md for AI agents)
              java -jar codevigil.jar --init ./my-project

              # AI agent fast-check: only review what changed
              java -jar codevigil.jar --diff --format json ./my-project

              # Full automated code review
              java -jar codevigil.jar --review ./my-project

              # Standard analysis
              java -jar codevigil.jar --pattern mvc ./my-project
              java -jar codevigil.jar --all-patterns --format html --output report.html ./my-project
              java -jar codevigil.jar --pattern solid --fail-on error ./my-project
              java -jar codevigil.jar --pattern clean-architecture --fitness ./my-project

            AI Agent Integration:
              1. Run --init to generate .codevigil.md (AI reads this automatically)
              2. Run --install-hook for pre-commit quality gate
              3. Use --review or --diff for JSON feedback in AI workflows
              4. Push .github/workflows/ for automated PR reviews

            CI/CD Integration:
              Use --fail-on error to fail the build when architectural violations are found.
              Use --fitness to enforce architecture fitness functions as quality gates.
              Use --format json for machine-readable output.
        """.trimIndent())
    }

    private fun parseArgs(args: Array<String>): CliConfig {
        var patternName: String? = null
        var allPatterns = false
        var format = "text"
        var outputFile: String? = null
        var minSeverity = ViolationSeverity.INFO
        var failOnSeverity: ViolationSeverity? = null
        var customPatternFile: String? = null
        var excludeDirs: Set<String>? = null
        var includeComplexity = false
        var includeCircularDeps = false
        var includeCoupling = false
        var fitness = false
        var saveBaseline = false
        var drift = false
        var graphFormat: String? = null
        var callGraphFormat: String? = null
        var memory = false
        var threads = false
        var bigo = false
        var review = false
        var incremental = false
        var security = false
        var deadCode = false
        var duplicates = false
        var naming = false
        var api = false
        var watch = false
        var init = false
        var diff = false
        var installHook = false
        var projectPath: String? = null

        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--pattern" -> { i++; patternName = args.getOrNull(i) }
                "--all-patterns" -> allPatterns = true
                "--format" -> { i++; format = args.getOrNull(i) ?: "text" }
                "--output" -> { i++; outputFile = args.getOrNull(i) }
                "--min-severity" -> { i++; minSeverity = parseSeverityArg(args.getOrNull(i)) }
                "--fail-on" -> { i++; failOnSeverity = parseSeverityArg(args.getOrNull(i)) }
                "--custom-pattern" -> { i++; customPatternFile = args.getOrNull(i) }
                "--exclude" -> { i++; excludeDirs = args.getOrNull(i)?.split(",")?.map { it.trim() }?.toSet() }
                "--complexity" -> includeComplexity = true
                "--circular-deps" -> includeCircularDeps = true
                "--coupling" -> includeCoupling = true
                "--fitness" -> fitness = true
                "--save-baseline" -> saveBaseline = true
                "--drift" -> drift = true
                "--graph" -> { i++; graphFormat = args.getOrNull(i) ?: "ascii" }
                "--call-graph" -> { i++; callGraphFormat = args.getOrNull(i) ?: "ascii" }
                "--memory" -> memory = true
                "--threads" -> threads = true
                "--bigo" -> bigo = true
                "--review" -> review = true
                "--incremental" -> incremental = true
                "--security" -> security = true
                "--dead-code" -> deadCode = true
                "--duplicates" -> duplicates = true
                "--naming" -> naming = true
                "--api" -> api = true
                "--watch" -> watch = true
                "--init" -> init = true
                "--diff" -> diff = true
                "--install-hook" -> installHook = true
                "-h", "--help" -> {} // handled elsewhere
                else -> {
                    if (!args[i].startsWith("-")) projectPath = args[i]
                }
            }
            i++
        }

        return CliConfig(
            patternName = patternName,
            allPatterns = allPatterns,
            format = format,
            outputFile = outputFile,
            minSeverity = minSeverity,
            failOnSeverity = failOnSeverity,
            customPatternFile = customPatternFile,
            excludeDirs = excludeDirs,
            includeComplexity = includeComplexity,
            includeCircularDeps = includeCircularDeps,
            includeCoupling = includeCoupling,
            fitness = fitness,
            saveBaseline = saveBaseline,
            drift = drift,
            graphFormat = graphFormat,
            callGraphFormat = callGraphFormat,
            memory = memory,
            threads = threads,
            bigo = bigo,
            review = review,
            incremental = incremental,
            security = security,
            deadCode = deadCode,
            duplicates = duplicates,
            naming = naming,
            api = api,
            watch = watch,
            init = init,
            diff = diff,
            installHook = installHook,
            projectPath = projectPath
        )
    }

    private fun parseSeverityArg(value: String?): ViolationSeverity {
        return when (value?.lowercase()) {
            "error" -> ViolationSeverity.ERROR
            "warning" -> ViolationSeverity.WARNING
            else -> ViolationSeverity.INFO
        }
    }
}

private data class CliConfig(
    val patternName: String?,
    val allPatterns: Boolean,
    val format: String,
    val outputFile: String?,
    val minSeverity: ViolationSeverity,
    val failOnSeverity: ViolationSeverity?,
    val customPatternFile: String?,
    val excludeDirs: Set<String>?,
    val includeComplexity: Boolean,
    val includeCircularDeps: Boolean,
    val includeCoupling: Boolean,
    val fitness: Boolean,
    val saveBaseline: Boolean,
    val drift: Boolean,
    val graphFormat: String?,
    val callGraphFormat: String?,
    val memory: Boolean,
    val threads: Boolean,
    val bigo: Boolean,
    val review: Boolean,
    val incremental: Boolean,
    val security: Boolean,
    val deadCode: Boolean,
    val duplicates: Boolean,
    val naming: Boolean,
    val api: Boolean,
    val watch: Boolean,
    val init: Boolean,
    val diff: Boolean,
    val installHook: Boolean,
    val projectPath: String?
)
