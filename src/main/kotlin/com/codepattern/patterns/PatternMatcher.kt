package com.codepattern.patterns

import com.codepattern.analysis.CircularDependencyDetector
import com.codepattern.analysis.CodeSmellDetector
import com.codepattern.analysis.ComplexityAnalyzer
import com.codepattern.models.*
import com.codepattern.scanner.ScannedFile
import com.codepattern.scanner.ScannedProject
import java.io.File
import java.nio.file.FileSystems
import java.util.concurrent.ConcurrentHashMap

class PatternMatcher {

    private val regexCache = ConcurrentHashMap<String, Regex>()

    fun analyze(project: ScannedProject, pattern: PatternSpec): List<Violation> {
        val violations = mutableListOf<Violation>()

        for (rule in pattern.rules) {
            when (rule.type) {
                RuleType.LAYER_DEPENDENCY -> violations += checkLayerDependencies(project, pattern, rule)
                RuleType.NAMING_CONVENTION -> violations += checkNamingConventions(project, pattern, rule)
                RuleType.FILE_ORGANIZATION -> violations += checkFileOrganization(project, pattern, rule)
                RuleType.SINGLE_RESPONSIBILITY -> violations += checkSingleResponsibility(project, rule)
                RuleType.INTERFACE_SEGREGATION -> violations += checkInterfaceSegregation(project, rule)
                RuleType.DEPENDENCY_INVERSION -> violations += checkDependencyInversion(project, rule)
                RuleType.CYCLOMATIC_COMPLEXITY -> violations += checkCyclomaticComplexity(project, pattern, rule)
                RuleType.COGNITIVE_COMPLEXITY -> violations += checkCognitiveComplexity(project, pattern, rule)
                RuleType.CODE_SMELL -> violations += checkCodeSmells(project, pattern, rule)
                RuleType.CIRCULAR_DEPENDENCY -> {
                    val cycles = CircularDependencyDetector.detectCycles(project)
                    violations += CircularDependencyDetector.toViolations(
                        cycles, pattern.name, rule.name, rule.id, rule.severity
                    )
                }
                RuleType.DEAD_CODE -> violations += checkDeadCode(project, pattern, rule)
                RuleType.METHOD_LENGTH -> violations += checkMethodLength(project, pattern, rule)
                RuleType.PARAMETER_COUNT -> violations += checkParameterCount(project, pattern, rule)
                RuleType.COUPLING -> violations += checkCoupling(project, pattern, rule)
            }
        }

        return violations
    }

    /**
     * Analyze a single file against a pattern. Used for real-time highlighting.
     */
    fun analyzeFile(file: ScannedFile, pattern: PatternSpec): List<Violation> {
        val violations = mutableListOf<Violation>()

        for (rule in pattern.rules) {
            when (rule.type) {
                RuleType.LAYER_DEPENDENCY -> violations += checkLayerDependenciesForFile(file, pattern, rule)
                RuleType.NAMING_CONVENTION -> violations += checkNamingConventionForFile(file, pattern, rule)
                RuleType.FILE_ORGANIZATION -> violations += checkFileOrganizationForFile(file, pattern, rule)
                RuleType.SINGLE_RESPONSIBILITY -> violations += checkSingleResponsibilityForFile(file, rule)
                RuleType.INTERFACE_SEGREGATION -> violations += checkInterfaceSegregationForFile(file, rule)
                RuleType.DEPENDENCY_INVERSION -> violations += checkDependencyInversionForFile(file, rule)
                RuleType.CYCLOMATIC_COMPLEXITY -> violations += checkComplexityForFile(file, pattern, rule, "cyclomatic")
                RuleType.COGNITIVE_COMPLEXITY -> violations += checkComplexityForFile(file, pattern, rule, "cognitive")
                RuleType.METHOD_LENGTH -> violations += checkMethodLengthForFile(file, pattern, rule)
                RuleType.PARAMETER_COUNT -> violations += checkParameterCountForFile(file, pattern, rule)
                RuleType.COUPLING -> violations += checkCouplingForFile(file, pattern, rule)
                RuleType.CODE_SMELL -> violations += checkCodeSmellForFile(file, pattern, rule)
                RuleType.CIRCULAR_DEPENDENCY -> {} // requires full project scan
                RuleType.DEAD_CODE -> {} // requires full project scan
            }
        }

        return violations
    }

    /**
     * Classify files into layers. Returns layer name -> files mapping, plus "Unassigned" for unmatched files.
     */
    fun classifyFiles(project: ScannedProject, pattern: PatternSpec): Map<String, List<ScannedFile>> {
        val result = mutableMapOf<String, MutableList<ScannedFile>>()
        for (layer in pattern.layers) {
            result[layer.name] = mutableListOf()
        }
        result["Unassigned"] = mutableListOf()

        for (file in project.files) {
            val layer = identifyLayer(file, pattern.layers)
            if (layer != null) {
                result.getOrPut(layer.name) { mutableListOf() }.add(file)
            } else {
                result["Unassigned"]!!.add(file)
            }
        }

        return result
    }

    // -- Full project checks --

    private fun checkLayerDependencies(
        project: ScannedProject, pattern: PatternSpec, rule: PatternRule
    ): List<Violation> {
        return project.files.flatMap { file -> checkLayerDependenciesForFile(file, pattern, rule) }
    }

    private fun checkNamingConventions(
        project: ScannedProject, pattern: PatternSpec, rule: PatternRule
    ): List<Violation> {
        return project.files.flatMap { file -> checkNamingConventionForFile(file, pattern, rule) }
    }

    private fun checkFileOrganization(
        project: ScannedProject, pattern: PatternSpec, rule: PatternRule
    ): List<Violation> {
        return project.files.flatMap { file -> checkFileOrganizationForFile(file, pattern, rule) }
    }

    private fun checkSingleResponsibility(project: ScannedProject, rule: PatternRule): List<Violation> {
        return project.files.flatMap { file -> checkSingleResponsibilityForFile(file, rule) }
    }

    private fun checkInterfaceSegregation(project: ScannedProject, rule: PatternRule): List<Violation> {
        return project.files.flatMap { file -> checkInterfaceSegregationForFile(file, rule) }
    }

    private fun checkDependencyInversion(project: ScannedProject, rule: PatternRule): List<Violation> {
        return project.files.flatMap { file -> checkDependencyInversionForFile(file, rule) }
    }

    // -- Per-file checks --

    private fun checkLayerDependenciesForFile(
        file: ScannedFile, pattern: PatternSpec, rule: PatternRule
    ): List<Violation> {
        val violations = mutableListOf<Violation>()
        val fileLayer = identifyLayer(file, pattern.layers) ?: return emptyList()

        for (dep in file.imports) {
            val depLayer = identifyLayerByImport(dep, pattern.layers)
            if (depLayer != null && depLayer.name != fileLayer.name
                && depLayer.name !in fileLayer.allowedDependencies
            ) {
                violations += Violation(
                    ruleName = rule.name,
                    patternName = pattern.name,
                    message = "Layer '${fileLayer.name}' should not depend on '${depLayer.name}'. " +
                            "Import '$dep' violates dependency direction.",
                    severity = rule.severity,
                    filePath = file.relativePath,
                    lineNumber = file.importLineNumbers[dep] ?: 1,
                    suggestedFix = "Move this dependency behind an abstraction or restructure to respect layer boundaries."
                )
            }
        }

        return violations
    }

    private fun checkNamingConventionForFile(
        file: ScannedFile, pattern: PatternSpec, rule: PatternRule
    ): List<Violation> {
        val layer = identifyLayer(file, pattern.layers) ?: return emptyList()
        if (layer.namingConventions.isEmpty()) return emptyList()

        val fileName = file.relativePath.substringAfterLast("/").substringBeforeLast(".")
        val matchesAny = layer.namingConventions.any { convention ->
            getCachedRegex(convention)?.matches(fileName) == true
        }

        if (!matchesAny) {
            return listOf(
                Violation(
                    ruleName = rule.name,
                    patternName = pattern.name,
                    message = "File '$fileName' in layer '${layer.name}' does not match naming convention: " +
                            layer.namingConventions.joinToString(" or "),
                    severity = rule.severity,
                    filePath = file.relativePath,
                    lineNumber = 1,
                    suggestedFix = "Rename to match one of: ${layer.namingConventions.joinToString(", ")}"
                )
            )
        }
        return emptyList()
    }

    private fun checkFileOrganizationForFile(
        file: ScannedFile, pattern: PatternSpec, rule: PatternRule
    ): List<Violation> {
        val violations = mutableListOf<Violation>()
        val matchedLayers = pattern.layers.filter { layer ->
            layer.filePatterns.any { globPattern -> matchGlob(globPattern, file.relativePath) }
        }

        if (matchedLayers.isEmpty()) {
            for (layer in pattern.layers) {
                if (layer.namingConventions.any { conv ->
                        val fileName = file.relativePath.substringAfterLast("/").substringBeforeLast(".")
                        getCachedRegex(conv)?.matches(fileName) == true
                    }) {
                    violations += Violation(
                        ruleName = rule.name,
                        patternName = pattern.name,
                        message = "File '${file.relativePath}' matches naming for layer '${layer.name}' " +
                                "but is not in the expected directory.",
                        severity = ViolationSeverity.INFO,
                        filePath = file.relativePath,
                        lineNumber = 1,
                        suggestedFix = "Move this file to a directory matching: ${layer.filePatterns.joinToString(", ")}"
                    )
                }
            }
        }

        return violations
    }

    private fun checkSingleResponsibilityForFile(file: ScannedFile, rule: PatternRule): List<Violation> {
        val violations = mutableListOf<Violation>()
        val maxMethods = (rule.config["max_methods_per_class"] as? Number)?.toInt() ?: 10
        val maxLines = (rule.config["max_lines_per_class"] as? Number)?.toInt() ?: 300

        if (file.methodCount > maxMethods) {
            violations += Violation(
                ruleName = rule.name,
                patternName = "SOLID",
                message = "Class has ${file.methodCount} methods (max: $maxMethods). Consider splitting responsibilities.",
                severity = rule.severity,
                filePath = file.relativePath,
                lineNumber = 1,
                suggestedFix = "Extract related methods into separate classes with focused responsibilities."
            )
        }
        if (file.lineCount > maxLines) {
            violations += Violation(
                ruleName = rule.name,
                patternName = "SOLID",
                message = "File has ${file.lineCount} lines (max: $maxLines). May have multiple responsibilities.",
                severity = ViolationSeverity.INFO,
                filePath = file.relativePath,
                lineNumber = 1,
                suggestedFix = "Consider breaking this file into smaller, focused modules."
            )
        }

        return violations
    }

    private fun checkInterfaceSegregationForFile(file: ScannedFile, rule: PatternRule): List<Violation> {
        val maxInterfaceMethods = (rule.config["max_interface_methods"] as? Number)?.toInt() ?: 5
        if (file.isInterface && file.methodCount > maxInterfaceMethods) {
            return listOf(
                Violation(
                    ruleName = rule.name,
                    patternName = "SOLID",
                    message = "Interface has ${file.methodCount} methods (max: $maxInterfaceMethods). " +
                            "Consider splitting into smaller interfaces.",
                    severity = rule.severity,
                    filePath = file.relativePath,
                    lineNumber = 1,
                    suggestedFix = "Split this interface into smaller, client-specific interfaces."
                )
            )
        }
        return emptyList()
    }

    private fun checkDependencyInversionForFile(file: ScannedFile, rule: PatternRule): List<Violation> {
        return file.concreteClassDependencies.map { dep ->
            Violation(
                ruleName = rule.name,
                patternName = "SOLID",
                message = "Direct instantiation of concrete class '$dep'. Depend on abstractions instead.",
                severity = rule.severity,
                filePath = file.relativePath,
                lineNumber = file.importLineNumbers[dep] ?: 1,
                suggestedFix = "Introduce an interface/abstraction for '$dep' and inject it instead of creating directly."
            )
        }
    }

    // -- Complexity checks --

    private fun checkCyclomaticComplexity(project: ScannedProject, pattern: PatternSpec, rule: PatternRule): List<Violation> {
        return project.files.flatMap { checkComplexityForFile(it, pattern, rule, "cyclomatic") }
    }

    private fun checkCognitiveComplexity(project: ScannedProject, pattern: PatternSpec, rule: PatternRule): List<Violation> {
        return project.files.flatMap { checkComplexityForFile(it, pattern, rule, "cognitive") }
    }

    private fun checkComplexityForFile(file: ScannedFile, pattern: PatternSpec, rule: PatternRule, type: String): List<Violation> {
        val lines = readFileLines(file.absolutePath) ?: return emptyList()
        val methods = ComplexityAnalyzer.extractMethods(lines, file.language)
        val violations = mutableListOf<Violation>()

        val maxCyclomatic = (rule.config["max_cyclomatic_complexity"] as? Number)?.toInt() ?: 10
        val maxCognitive = (rule.config["max_cognitive_complexity"] as? Number)?.toInt() ?: 15

        for (method in methods) {
            when (type) {
                "cyclomatic" -> {
                    if (method.cyclomaticComplexity > maxCyclomatic) {
                        val confidence = (method.cyclomaticComplexity.toDouble() / (maxCyclomatic * 3)).coerceIn(0.7, 0.99)
                        violations += Violation(
                            ruleName = rule.name,
                            patternName = pattern.name,
                            message = "Method '${method.name}' has cyclomatic complexity ${method.cyclomaticComplexity} (max: $maxCyclomatic).",
                            severity = if (method.cyclomaticComplexity > maxCyclomatic * 2) ViolationSeverity.ERROR else rule.severity,
                            filePath = file.relativePath,
                            lineNumber = method.startLine,
                            suggestedFix = "Break this method into smaller methods with fewer decision points.",
                            category = ViolationCategory.COMPLEXITY,
                            confidence = confidence,
                            ruleId = rule.id
                        )
                    }
                }
                "cognitive" -> {
                    if (method.cognitiveComplexity > maxCognitive) {
                        val confidence = (method.cognitiveComplexity.toDouble() / (maxCognitive * 3)).coerceIn(0.7, 0.99)
                        violations += Violation(
                            ruleName = rule.name,
                            patternName = pattern.name,
                            message = "Method '${method.name}' has cognitive complexity ${method.cognitiveComplexity} (max: $maxCognitive).",
                            severity = rule.severity,
                            filePath = file.relativePath,
                            lineNumber = method.startLine,
                            suggestedFix = "Reduce nesting depth, extract helper methods, and simplify control flow.",
                            category = ViolationCategory.COMPLEXITY,
                            confidence = confidence,
                            ruleId = rule.id
                        )
                    }
                }
            }
        }
        return violations
    }

    private fun checkMethodLength(project: ScannedProject, pattern: PatternSpec, rule: PatternRule): List<Violation> {
        return project.files.flatMap { checkMethodLengthForFile(it, pattern, rule) }
    }

    private fun checkMethodLengthForFile(file: ScannedFile, pattern: PatternSpec, rule: PatternRule): List<Violation> {
        val lines = readFileLines(file.absolutePath) ?: return emptyList()
        val maxLines = (rule.config["max_method_lines"] as? Number)?.toInt() ?: 50
        val methods = ComplexityAnalyzer.extractMethods(lines, file.language)

        return methods.filter { it.lineCount > maxLines }.map { method ->
            Violation(
                ruleName = rule.name,
                patternName = pattern.name,
                message = "Method '${method.name}' has ${method.lineCount} lines (max: $maxLines).",
                severity = rule.severity,
                filePath = file.relativePath,
                lineNumber = method.startLine,
                suggestedFix = "Extract parts of this method into smaller, well-named helper methods.",
                category = ViolationCategory.COMPLEXITY,
                confidence = 0.95,
                ruleId = rule.id
            )
        }
    }

    private fun checkParameterCount(project: ScannedProject, pattern: PatternSpec, rule: PatternRule): List<Violation> {
        return project.files.flatMap { checkParameterCountForFile(it, pattern, rule) }
    }

    private fun checkParameterCountForFile(file: ScannedFile, pattern: PatternSpec, rule: PatternRule): List<Violation> {
        val lines = readFileLines(file.absolutePath) ?: return emptyList()
        val maxParams = (rule.config["max_parameters"] as? Number)?.toInt() ?: 5
        val methods = ComplexityAnalyzer.extractMethods(lines, file.language)

        return methods.filter { it.parameterCount > maxParams }.map { method ->
            Violation(
                ruleName = rule.name,
                patternName = pattern.name,
                message = "Method '${method.name}' has ${method.parameterCount} parameters (max: $maxParams).",
                severity = rule.severity,
                filePath = file.relativePath,
                lineNumber = method.startLine,
                suggestedFix = "Group related parameters into a parameter object / data class.",
                category = ViolationCategory.COMPLEXITY,
                confidence = 0.95,
                ruleId = rule.id
            )
        }
    }

    // -- Code smell checks --

    private fun checkCodeSmells(project: ScannedProject, pattern: PatternSpec, rule: PatternRule): List<Violation> {
        return project.files.flatMap { checkCodeSmellForFile(it, pattern, rule) }
    }

    private fun checkCodeSmellForFile(file: ScannedFile, pattern: PatternSpec, rule: PatternRule): List<Violation> {
        val lines = readFileLines(file.absolutePath) ?: return emptyList()
        return CodeSmellDetector.detect(file, lines, rule.config, pattern.name, rule.name, rule.id)
    }

    // -- Coupling checks --

    private fun checkCoupling(project: ScannedProject, pattern: PatternSpec, rule: PatternRule): List<Violation> {
        return project.files.flatMap { checkCouplingForFile(it, pattern, rule) }
    }

    private fun checkCouplingForFile(file: ScannedFile, pattern: PatternSpec, rule: PatternRule): List<Violation> {
        val maxEfferent = (rule.config["max_efferent_coupling"] as? Number)?.toInt() ?: 10
        val maxImports = (rule.config["max_import_count"] as? Number)?.toInt() ?: 15

        val violations = mutableListOf<Violation>()
        if (file.imports.size > maxImports) {
            violations += Violation(
                ruleName = rule.name,
                patternName = pattern.name,
                message = "File has ${file.imports.size} imports (max: $maxImports), indicating high coupling.",
                severity = rule.severity,
                filePath = file.relativePath,
                lineNumber = 1,
                suggestedFix = "Reduce dependencies by introducing a facade, mediator, or splitting responsibilities.",
                category = ViolationCategory.COUPLING,
                confidence = 0.85,
                ruleId = rule.id
            )
        }
        return violations
    }

    // -- Dead code checks --

    private fun checkDeadCode(project: ScannedProject, pattern: PatternSpec, rule: PatternRule): List<Violation> {
        // Detect imports that no other file references (potential unused exports)
        val allImportTargets = project.files.flatMap { it.imports }.toSet()
        val allClassNames = project.files.mapNotNull { it.className }.toSet()

        val violations = mutableListOf<Violation>()

        for (file in project.files) {
            val className = file.className ?: continue
            // If this class is never imported by any other file, it might be dead code
            val isReferenced = allImportTargets.any { imp ->
                imp.contains(className) || imp.endsWith(".$className")
            }
            // Exclude entry points, tests, and configs
            val isLikelyEntryPoint = file.relativePath.let { path ->
                path.contains("Main") || path.contains("Application") || path.contains("App.") ||
                        path.contains("test/") || path.contains("Test.") || path.contains("Spec.") ||
                        path.contains("config/") || path.contains("Config.") ||
                        path.contains("index.") || path.contains("__init__")
            }

            if (!isReferenced && !isLikelyEntryPoint && project.files.size > 5) {
                violations += Violation(
                    ruleName = rule.name,
                    patternName = pattern.name,
                    message = "Class '$className' is not imported by any other project file. It may be unused.",
                    severity = ViolationSeverity.INFO,
                    filePath = file.relativePath,
                    lineNumber = 1,
                    suggestedFix = "Verify this class is used (e.g., via reflection, DI, or external consumers). Remove if truly unused.",
                    category = ViolationCategory.CODE_SMELL,
                    confidence = 0.5,  // Low confidence — many valid reasons for unreferenced classes
                    ruleId = rule.id
                )
            }
        }
        return violations
    }

    // -- File reading utility (for standalone file analysis) --

    private fun readFileLines(absolutePath: String): List<String>? {
        return try {
            File(absolutePath).readLines()
        } catch (_: Exception) {
            null
        }
    }

    // -- Utilities --

    fun identifyLayer(file: ScannedFile, layers: List<Layer>): Layer? {
        return layers.firstOrNull { layer ->
            layer.filePatterns.any { pattern -> matchGlob(pattern, file.relativePath) }
        }
    }

    private fun identifyLayerByImport(importPath: String, layers: List<Layer>): Layer? {
        val importLower = importPath.lowercase()
        val importSegments = importLower.replace(".", "/").split("/").toSet()

        return layers.firstOrNull { layer ->
            layer.filePatterns.any { pattern ->
                // Extract meaningful directory segments from the glob pattern
                val segments = pattern.replace("**/", "").replace("/**", "").replace("*", "")
                    .split("/").filter { it.isNotEmpty() }
                // Require an exact segment match to avoid partial matches
                segments.any { segment -> segment.lowercase() in importSegments }
            }
        }
    }

    private fun matchGlob(pattern: String, path: String): Boolean {
        return try {
            val matcher = FileSystems.getDefault().getPathMatcher("glob:$pattern")
            matcher.matches(java.nio.file.Path.of(path))
        } catch (_: Exception) {
            val segments = pattern.replace("*", "").split("/").filter { it.isNotEmpty() }
            segments.any { segment ->
                segment.isNotEmpty() && path.lowercase().contains("/$segment/".lowercase())
            }
        }
    }

    private fun getCachedRegex(pattern: String): Regex? {
        return try {
            regexCache.getOrPut(pattern) { Regex(pattern) }
        } catch (_: Exception) {
            null
        }
    }
}
