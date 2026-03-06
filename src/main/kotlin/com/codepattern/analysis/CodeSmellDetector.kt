package com.codepattern.analysis

import com.codepattern.models.Violation
import com.codepattern.models.ViolationCategory
import com.codepattern.models.ViolationSeverity
import com.codepattern.scanner.ScannedFile

/**
 * Detects common code smells from scanned file metadata and source lines.
 * Each detector computes a confidence score (0.0-1.0) to reduce false positives.
 */
object CodeSmellDetector {

    data class SmellConfig(
        val smellType: String = "GOD_CLASS",
        val maxMethods: Int = 20,
        val maxLines: Int = 500,
        val maxFields: Int = 15,
        val maxImports: Int = 20
    )

    fun detect(
        file: ScannedFile,
        lines: List<String>,
        config: Map<String, Any>,
        patternName: String,
        ruleName: String,
        ruleId: String
    ): List<Violation> {
        val smellType = (config["smell_type"] as? String)?.uppercase() ?: "GOD_CLASS"
        return when (smellType) {
            "GOD_CLASS" -> detectGodClass(file, lines, config, patternName, ruleName, ruleId)
            "FEATURE_ENVY" -> detectFeatureEnvy(file, lines, patternName, ruleName, ruleId)
            "DATA_CLASS" -> detectDataClass(file, lines, patternName, ruleName, ruleId)
            "LONG_PARAMETER_LIST" -> detectLongParameterList(file, lines, config, patternName, ruleName, ruleId)
            "SHOTGUN_SURGERY" -> detectShotgunSurgery(file, patternName, ruleName, ruleId)
            else -> emptyList()
        }
    }

    private fun detectGodClass(
        file: ScannedFile,
        lines: List<String>,
        config: Map<String, Any>,
        patternName: String,
        ruleName: String,
        ruleId: String
    ): List<Violation> {
        val maxMethods = (config["max_methods"] as? Number)?.toInt() ?: 20
        val maxLines = (config["max_lines"] as? Number)?.toInt() ?: 500
        val maxFields = (config["max_fields"] as? Number)?.toInt() ?: 15

        val violations = mutableListOf<Violation>()
        val fieldCount = countFields(lines, file.language)

        // Compute a multi-factor confidence score
        var factors = 0
        var triggered = 0

        factors++
        if (file.methodCount > maxMethods) triggered++

        factors++
        if (file.lineCount > maxLines) triggered++

        factors++
        if (fieldCount > maxFields) triggered++

        factors++
        if (file.imports.size > 15) triggered++

        if (triggered >= 2) {
            val confidence = (triggered.toDouble() / factors).coerceIn(0.5, 1.0)
            val details = buildString {
                append("${file.methodCount} methods")
                if (file.methodCount > maxMethods) append(" (max: $maxMethods)")
                append(", ${file.lineCount} lines")
                if (file.lineCount > maxLines) append(" (max: $maxLines)")
                append(", $fieldCount fields")
                if (fieldCount > maxFields) append(" (max: $maxFields)")
                append(", ${file.imports.size} imports")
            }
            violations += Violation(
                ruleName = ruleName,
                patternName = patternName,
                message = "Potential God Class detected: $details. This class likely has too many responsibilities.",
                severity = ViolationSeverity.WARNING,
                filePath = file.relativePath,
                lineNumber = 1,
                suggestedFix = "Split this class into smaller, focused classes. Group related methods and fields into cohesive modules.",
                category = ViolationCategory.CODE_SMELL,
                confidence = confidence,
                ruleId = ruleId
            )
        }
        return violations
    }

    private fun detectFeatureEnvy(
        file: ScannedFile,
        lines: List<String>,
        patternName: String,
        ruleName: String,
        ruleId: String
    ): List<Violation> {
        // Feature envy: a method that uses more data from other classes than its own
        // Heuristic: count references to other class names vs own class name
        val className = file.className ?: return emptyList()
        val otherClassRefs = mutableMapOf<String, Int>()
        val classNamePattern = Regex("""([A-Z][A-Za-z0-9_]+)\.(?:[a-z]\w+)""")

        for (line in lines) {
            for (match in classNamePattern.findAll(line)) {
                val refClass = match.groupValues[1]
                if (refClass != className && refClass.length > 1) {
                    otherClassRefs[refClass] = (otherClassRefs[refClass] ?: 0) + 1
                }
            }
        }

        val enviedClass = otherClassRefs.maxByOrNull { it.value }
        if (enviedClass != null && enviedClass.value > 10) {
            val confidence = (enviedClass.value.toDouble() / (enviedClass.value + 10)).coerceIn(0.5, 0.95)
            return listOf(
                Violation(
                    ruleName = ruleName,
                    patternName = patternName,
                    message = "Potential Feature Envy: this class references '${enviedClass.key}' ${enviedClass.value} times. " +
                            "Consider moving related logic to '${enviedClass.key}'.",
                    severity = ViolationSeverity.INFO,
                    filePath = file.relativePath,
                    lineNumber = 1,
                    suggestedFix = "Move the methods that heavily use '${enviedClass.key}' to that class, or extract a mediator.",
                    category = ViolationCategory.CODE_SMELL,
                    confidence = confidence,
                    ruleId = ruleId
                )
            )
        }
        return emptyList()
    }

    private fun detectDataClass(
        file: ScannedFile,
        lines: List<String>,
        patternName: String,
        ruleName: String,
        ruleId: String
    ): List<Violation> {
        // Data class smell: class with only getters/setters and no real logic
        val fieldCount = countFields(lines, file.language)
        val getterSetterPattern = Regex("""(?:get|set|is)[A-Z]\w*\s*\(""")
        val getterSetterCount = lines.count { getterSetterPattern.containsMatchIn(it) }

        if (fieldCount >= 5 && file.methodCount > 0 && getterSetterCount.toDouble() / file.methodCount > 0.8) {
            return listOf(
                Violation(
                    ruleName = ruleName,
                    patternName = patternName,
                    message = "Potential Data Class smell: $fieldCount fields with mostly getters/setters. " +
                            "Consider using a record/data class or adding behavior.",
                    severity = ViolationSeverity.INFO,
                    filePath = file.relativePath,
                    lineNumber = 1,
                    suggestedFix = "Use your language's data class/record construct, or move related behavior into this class.",
                    category = ViolationCategory.CODE_SMELL,
                    confidence = 0.6,
                    ruleId = ruleId
                )
            )
        }
        return emptyList()
    }

    private fun detectLongParameterList(
        file: ScannedFile,
        lines: List<String>,
        config: Map<String, Any>,
        patternName: String,
        ruleName: String,
        ruleId: String
    ): List<Violation> {
        val maxParams = (config["max_parameters"] as? Number)?.toInt() ?: 5
        val methods = ComplexityAnalyzer.extractMethods(lines, file.language)
        return methods.filter { it.parameterCount > maxParams }.map { method ->
            Violation(
                ruleName = ruleName,
                patternName = patternName,
                message = "Method '${method.name}' has ${method.parameterCount} parameters (max: $maxParams). " +
                        "Consider introducing a parameter object.",
                severity = ViolationSeverity.WARNING,
                filePath = file.relativePath,
                lineNumber = method.startLine,
                suggestedFix = "Group related parameters into a data class / struct / object.",
                category = ViolationCategory.CODE_SMELL,
                confidence = 0.9,
                ruleId = ruleId
            )
        }
    }

    private fun detectShotgunSurgery(
        file: ScannedFile,
        patternName: String,
        ruleName: String,
        ruleId: String
    ): List<Violation> {
        // Heuristic: if a file is imported by many other files, changes to it cause shotgun surgery
        // This is better detected at project level, so we flag high efferent coupling
        if (file.imports.size > 20) {
            return listOf(
                Violation(
                    ruleName = ruleName,
                    patternName = patternName,
                    message = "This file has ${file.imports.size} imports, suggesting high coupling. " +
                            "Changes here may require updating many other files.",
                    severity = ViolationSeverity.INFO,
                    filePath = file.relativePath,
                    lineNumber = 1,
                    suggestedFix = "Reduce coupling by introducing intermediary abstractions or facades.",
                    category = ViolationCategory.COUPLING,
                    confidence = 0.7,
                    ruleId = ruleId
                )
            )
        }
        return emptyList()
    }

    private fun countFields(lines: List<String>, language: String): Int {
        val fieldPatterns = when (language) {
            "java" -> listOf(
                Regex("""^\s*(?:private|protected|public)\s+(?:static\s+)?(?:final\s+)?[A-Z]\w*(?:<[^>]*>)?\s+\w+""")
            )
            "kotlin" -> listOf(
                Regex("""^\s*(?:val|var)\s+\w+"""),
                Regex("""^\s*(?:private|protected|internal)\s+(?:val|var)\s+\w+""")
            )
            "typescript", "javascript" -> listOf(
                Regex("""^\s*(?:private|protected|public|readonly)\s+\w+"""),
                Regex("""^\s*(?:#\w+|this\.\w+\s*=)""")
            )
            "python" -> listOf(
                Regex("""^\s*self\.\w+\s*=""")
            )
            "csharp" -> listOf(
                Regex("""^\s*(?:private|protected|public|internal)\s+(?:static\s+)?(?:readonly\s+)?[A-Z]\w*\s+\w+""")
            )
            else -> listOf(
                Regex("""^\s*(?:private|protected|public)\s+\w+"""),
                Regex("""^\s*(?:val|var|let|const)\s+\w+""")
            )
        }
        return lines.count { line -> fieldPatterns.any { it.containsMatchIn(line) } }
    }
}
