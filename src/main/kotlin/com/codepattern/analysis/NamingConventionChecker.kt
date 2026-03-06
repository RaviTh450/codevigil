package com.codepattern.analysis

import com.codepattern.models.*
import com.codepattern.scanner.ScannedFile
import com.codepattern.scanner.ScannedProject
import java.io.File

/**
 * Enforces language-specific naming conventions.
 * Checks classes, methods, variables, constants, and file names.
 */
object NamingConventionChecker {

    data class NamingIssue(
        val filePath: String,
        val lineNumber: Int,
        val name: String,
        val kind: NameKind,
        val expected: String,
        val message: String,
        val severity: ViolationSeverity = ViolationSeverity.WARNING,
        val confidence: Double = 0.85
    )

    enum class NameKind { CLASS, METHOD, VARIABLE, CONSTANT, PARAMETER, FILE, PACKAGE }

    // PascalCase: starts with uppercase, no underscores (except _)
    private val PASCAL_CASE = Regex("^[A-Z][a-zA-Z0-9]*$")
    // camelCase: starts with lowercase
    private val CAMEL_CASE = Regex("^[a-z][a-zA-Z0-9]*$")
    // UPPER_SNAKE: all caps with underscores
    private val UPPER_SNAKE = Regex("^[A-Z][A-Z0-9_]*$")
    // snake_case
    private val SNAKE_CASE = Regex("^[a-z][a-z0-9_]*$")
    // kebab-case (for files)
    private val KEBAB_CASE = Regex("^[a-z][a-z0-9-]*$")

    private val CLASS_PATTERN = Regex("""(?:class|interface|enum|object|struct|trait)\s+([A-Za-z_]\w*)""")
    private val KOTLIN_JAVA_METHOD = Regex("""(?:fun|(?:public|private|protected|static|override|suspend|final)\s+)+\s*(?:\w+\s+)?([a-zA-Z_]\w*)\s*\(""")
    private val VARIABLE_PATTERN = Regex("""(?:val|var|let|const|final)\s+([a-zA-Z_]\w*)""")
    private val CONSTANT_PATTERN = Regex("""(?:const\s+val|static\s+final|#define)\s+([A-Za-z_]\w*)""")
    private val PYTHON_FUNCTION = Regex("""def\s+([a-zA-Z_]\w*)\s*\(""")
    private val GO_FUNCTION = Regex("""func\s+(?:\([^)]*\)\s+)?([A-Za-z_]\w*)\s*\(""")

    fun analyze(project: ScannedProject, basePath: String): List<NamingIssue> {
        val issues = mutableListOf<NamingIssue>()
        for (file in project.files) {
            try {
                val lines = File("$basePath/${file.relativePath}").readLines()
                issues += analyzeFile(file, lines)
            } catch (_: Exception) {}
        }
        return issues
    }

    fun analyzeFile(file: ScannedFile, lines: List<String>): List<NamingIssue> {
        val issues = mutableListOf<NamingIssue>()
        val lang = file.language

        // Check file name
        issues += checkFileName(file)

        for ((idx, line) in lines.withIndex()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*") || trimmed.startsWith("#")) continue

            // Class names
            CLASS_PATTERN.find(trimmed)?.let { match ->
                val name = match.groupValues[1]
                if (!PASCAL_CASE.matches(name) && name != "_") {
                    issues += NamingIssue(
                        filePath = file.relativePath, lineNumber = idx + 1, name = name,
                        kind = NameKind.CLASS, expected = "PascalCase",
                        message = "Class '$name' should use PascalCase naming."
                    )
                }
            }

            // Constants
            CONSTANT_PATTERN.find(trimmed)?.let { match ->
                val name = match.groupValues[1]
                if (!UPPER_SNAKE.matches(name)) {
                    issues += NamingIssue(
                        filePath = file.relativePath, lineNumber = idx + 1, name = name,
                        kind = NameKind.CONSTANT, expected = "UPPER_SNAKE_CASE",
                        message = "Constant '$name' should use UPPER_SNAKE_CASE."
                    )
                }
                return@let // skip variable check for constants
            }

            // Methods
            val methodPattern = when (lang) {
                "python" -> PYTHON_FUNCTION
                "go" -> GO_FUNCTION
                else -> KOTLIN_JAVA_METHOD
            }
            methodPattern.find(trimmed)?.let { match ->
                val name = match.groupValues[1]
                if (name in setOf("if", "for", "while", "switch", "catch", "class", "main")) return@let
                when (lang) {
                    "python", "ruby", "rust" -> {
                        if (!SNAKE_CASE.matches(name) && name != "__init__" && !name.startsWith("_")) {
                            issues += NamingIssue(
                                filePath = file.relativePath, lineNumber = idx + 1, name = name,
                                kind = NameKind.METHOD, expected = "snake_case",
                                message = "Function '$name' should use snake_case in $lang."
                            )
                        }
                    }
                    else -> {
                        if (!CAMEL_CASE.matches(name) && !name.startsWith("_")) {
                            issues += NamingIssue(
                                filePath = file.relativePath, lineNumber = idx + 1, name = name,
                                kind = NameKind.METHOD, expected = "camelCase",
                                message = "Method '$name' should use camelCase."
                            )
                        }
                    }
                }
            }

            // Variables (skip if already matched as constant)
            if (CONSTANT_PATTERN.find(trimmed) == null) {
                VARIABLE_PATTERN.find(trimmed)?.let { match ->
                    val name = match.groupValues[1]
                    if (name == "_" || name.startsWith("_")) return@let
                    when (lang) {
                        "python", "ruby", "rust" -> {
                            if (!SNAKE_CASE.matches(name) && !UPPER_SNAKE.matches(name)) {
                                issues += NamingIssue(
                                    filePath = file.relativePath, lineNumber = idx + 1, name = name,
                                    kind = NameKind.VARIABLE, expected = "snake_case",
                                    message = "Variable '$name' should use snake_case in $lang.",
                                    severity = ViolationSeverity.INFO, confidence = 0.7
                                )
                            }
                        }
                        else -> {
                            if (!CAMEL_CASE.matches(name) && !UPPER_SNAKE.matches(name)) {
                                issues += NamingIssue(
                                    filePath = file.relativePath, lineNumber = idx + 1, name = name,
                                    kind = NameKind.VARIABLE, expected = "camelCase",
                                    message = "Variable '$name' should use camelCase.",
                                    severity = ViolationSeverity.INFO, confidence = 0.7
                                )
                            }
                        }
                    }
                }
            }
        }
        return issues
    }

    private fun checkFileName(file: ScannedFile): List<NamingIssue> {
        val fileName = file.relativePath.substringAfterLast("/").substringBeforeLast(".")
        return when (file.language) {
            "kotlin", "java", "csharp", "swift", "dart" -> {
                if (!PASCAL_CASE.matches(fileName) && !fileName.contains("Test") && fileName != "build") {
                    listOf(NamingIssue(
                        filePath = file.relativePath, lineNumber = 1, name = fileName,
                        kind = NameKind.FILE, expected = "PascalCase",
                        message = "File name '$fileName' should use PascalCase for ${file.language}.",
                        severity = ViolationSeverity.INFO, confidence = 0.6
                    ))
                } else emptyList()
            }
            "python", "ruby" -> {
                if (!SNAKE_CASE.matches(fileName) && fileName != "__init__" && fileName != "__main__") {
                    listOf(NamingIssue(
                        filePath = file.relativePath, lineNumber = 1, name = fileName,
                        kind = NameKind.FILE, expected = "snake_case",
                        message = "File name '$fileName' should use snake_case for ${file.language}.",
                        severity = ViolationSeverity.INFO, confidence = 0.6
                    ))
                } else emptyList()
            }
            else -> emptyList()
        }
    }

    fun toViolations(issues: List<NamingIssue>): List<Violation> = issues.map { issue ->
        Violation(
            ruleName = "Naming Convention",
            patternName = "Naming Conventions",
            message = issue.message,
            severity = issue.severity,
            filePath = issue.filePath,
            lineNumber = issue.lineNumber,
            suggestedFix = "Rename '${issue.name}' to follow ${issue.expected} convention.",
            category = ViolationCategory.NAMING,
            confidence = issue.confidence,
            ruleId = "naming-${issue.kind.name.lowercase()}"
        )
    }

    fun generateReport(issues: List<NamingIssue>): String {
        if (issues.isEmpty()) return "Naming Conventions: All names follow conventions."
        val grouped = issues.groupBy { it.kind }
        return buildString {
            appendLine("=== Naming Convention Report ===")
            appendLine("Total issues: ${issues.size}")
            appendLine()
            for ((kind, kindIssues) in grouped.toSortedMap()) {
                appendLine("${kind.name} naming (${kindIssues.size}):")
                for (i in kindIssues.take(10)) {
                    appendLine("  ${i.filePath}:${i.lineNumber} — '${i.name}' should be ${i.expected}")
                }
                if (kindIssues.size > 10) appendLine("  ... and ${kindIssues.size - 10} more")
                appendLine()
            }
        }
    }
}
