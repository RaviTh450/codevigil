package com.codepattern.analysis

import com.codepattern.models.*
import com.codepattern.scanner.ScannedFile
import com.codepattern.scanner.ScannedProject
import java.io.File

enum class DeadCodeType {
    UNUSED_IMPORT,
    UNUSED_VARIABLE,
    UNUSED_PARAMETER,
    UNREACHABLE_CODE,
    EMPTY_CATCH,
    EMPTY_METHOD,
    COMMENTED_CODE,
    UNUSED_PRIVATE_METHOD,
    REDUNDANT_NULL_CHECK,
    DEAD_CONDITIONAL
}

data class DeadCodeIssue(
    val type: DeadCodeType,
    val filePath: String,
    val lineNumber: Int,
    val name: String,
    val message: String,
    val severity: ViolationSeverity,
    val confidence: Double
)

/**
 * Detects dead code patterns including unused imports, unreachable code,
 * empty blocks, and other indicators of code that serves no purpose.
 */
object DeadCodeDetector {

    private val TERMINATOR_KEYWORDS = setOf("return", "throw", "break", "continue")

    private val CODE_LIKE_PATTERNS = Regex(
        """\b(if|for|while|return|val|var|fun|class|def|function|import|public|private)\b|[=;{})]\s*$"""
    )

    private val IMPORT_REGEX = Regex("""^\s*import\s+(.+)$""")
    private val ALIAS_REGEX = Regex("""^(.+)\s+as\s+(\w+)$""")
    private val WILDCARD_SUFFIX = ".*"

    private val VAR_DECLARATION_REGEX = Regex(
        """^\s*(?:val|var|let|const|final)\s+(\w+)"""
    )

    private val METHOD_DECLARATION_REGEX = Regex(
        """^\s*(?:(?:public|protected|internal|private|open|override|abstract|suspend|inline|static|final|synchronized)\s+)*fun\s+(\w+)\s*\(([^)]*)\)"""
    )

    private val PRIVATE_METHOD_REGEX = Regex(
        """^\s*private\s+(?:(?:suspend|inline)\s+)*fun\s+(\w+)\s*\("""
    )

    private val CATCH_REGEX = Regex("""^\s*\}\s*catch\s*\(""")
    private val CATCH_START_REGEX = Regex("""catch\s*\([^)]*\)\s*\{""")

    private val NULL_CHECK_REGEX = Regex("""if\s*\(\s*(\w+)\s*!=\s*null\s*\)""")
    private val NON_NULLABLE_VAL_REGEX = Regex("""^\s*(?:val|var)\s+(\w+)\s*:\s*([A-Z]\w+)(?:\s*=|$)""")

    private val DEAD_CONDITIONAL_REGEX = Regex("""(?:if|while)\s*\(\s*(true|false)\s*\)""")

    // ---------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------

    fun analyze(project: ScannedProject, basePath: String): List<DeadCodeIssue> {
        val issues = mutableListOf<DeadCodeIssue>()
        for (file in project.files) {
            val absolutePath = if (File(file.absolutePath).exists()) {
                file.absolutePath
            } else {
                File(basePath, file.relativePath).absolutePath
            }
            val sourceFile = File(absolutePath)
            if (!sourceFile.exists()) continue

            val lines = sourceFile.readLines()
            issues += analyzeFile(file, lines)
        }
        return issues
    }

    fun analyzeFile(file: ScannedFile, lines: List<String>): List<DeadCodeIssue> {
        val issues = mutableListOf<DeadCodeIssue>()
        issues += detectUnusedImports(file, lines)
        issues += detectUnusedVariables(file, lines)
        issues += detectUnusedParameters(file, lines)
        issues += detectUnreachableCode(file, lines)
        issues += detectEmptyCatch(file, lines)
        issues += detectEmptyMethods(file, lines)
        issues += detectCommentedCode(file, lines)
        issues += detectUnusedPrivateMethods(file, lines)
        issues += detectRedundantNullChecks(file, lines)
        issues += detectDeadConditionals(file, lines)
        return issues
    }

    fun toViolations(issues: List<DeadCodeIssue>): List<Violation> = issues.map { issue ->
        Violation(
            ruleName = "dead-code-${issue.type.name.lowercase().replace("_", "-")}",
            patternName = "Dead Code Detection",
            message = issue.message,
            severity = issue.severity,
            filePath = issue.filePath,
            lineNumber = issue.lineNumber,
            suggestedFix = suggestedFixFor(issue.type, issue.name),
            category = ViolationCategory.DEAD_CODE,
            confidence = issue.confidence,
            ruleId = "dead-code-${issue.type.name.lowercase().replace("_", "-")}"
        )
    }

    fun generateReport(issues: List<DeadCodeIssue>): String {
        if (issues.isEmpty()) return "Dead Code Analysis: No issues found."

        val grouped = issues.groupBy { it.type }
        val summary = grouped.entries.sortedByDescending { it.value.size }

        return buildString {
            appendLine("=== Dead Code Analysis Report ===")
            appendLine()
            appendLine("Total issues: ${issues.size}")
            appendLine("By severity: ${issues.groupBy { it.severity }.mapValues { it.value.size }}")
            appendLine()

            for ((type, typeIssues) in summary) {
                appendLine("--- ${type.name} (${typeIssues.size}) ---")
                for (issue in typeIssues.sortedBy { it.filePath }) {
                    appendLine("  ${issue.filePath}:${issue.lineNumber} - ${issue.name}")
                    appendLine("    ${issue.message}")
                    appendLine("    Confidence: ${"%.0f".format(issue.confidence * 100)}% | Severity: ${issue.severity}")
                }
                appendLine()
            }

            appendLine("=== End of Report ===")
        }
    }

    // ---------------------------------------------------------------
    // Detection implementations
    // ---------------------------------------------------------------

    private fun detectUnusedImports(file: ScannedFile, lines: List<String>): List<DeadCodeIssue> {
        val issues = mutableListOf<DeadCodeIssue>()
        val importLines = mutableListOf<Triple<Int, String, String>>() // lineNum, fullImport, symbolToCheck
        var lastImportLine = 0

        for ((index, line) in lines.withIndex()) {
            val match = IMPORT_REGEX.find(line) ?: continue
            val importPath = match.groupValues[1].trim().removeSuffix(";")

            // Skip wildcard imports — we can't know what they bring in
            if (importPath.endsWith(WILDCARD_SUFFIX)) continue

            val aliasMatch = ALIAS_REGEX.find(importPath)
            val symbolToCheck = if (aliasMatch != null) {
                aliasMatch.groupValues[2]
            } else {
                importPath.substringAfterLast(".")
            }

            importLines += Triple(index + 1, importPath, symbolToCheck)
            lastImportLine = index
        }

        // Build the body text (everything after the import section)
        val bodyText = lines.drop(lastImportLine + 1).joinToString("\n")

        for ((lineNum, importPath, symbol) in importLines) {
            if (symbol.isBlank()) continue
            val symbolPattern = Regex("""\b${Regex.escape(symbol)}\b""")
            if (!symbolPattern.containsMatchIn(bodyText)) {
                issues += DeadCodeIssue(
                    type = DeadCodeType.UNUSED_IMPORT,
                    filePath = file.relativePath,
                    lineNumber = lineNum,
                    name = importPath,
                    message = "Import '$importPath' is not used in the file.",
                    severity = ViolationSeverity.INFO,
                    confidence = 0.9
                )
            }
        }
        return issues
    }

    private fun detectUnusedVariables(file: ScannedFile, lines: List<String>): List<DeadCodeIssue> {
        val issues = mutableListOf<DeadCodeIssue>()

        for ((index, line) in lines.withIndex()) {
            val match = VAR_DECLARATION_REGEX.find(line) ?: continue
            val varName = match.groupValues[1]

            // Skip intentionally unused variables (underscore convention)
            if (varName.startsWith("_")) continue
            // Skip destructuring-style or very short names that are likely false positives
            if (varName.length < 2) continue

            // Search for the variable name in the rest of the surrounding scope
            // Simple heuristic: check all lines except the declaration line
            val varPattern = Regex("""\b${Regex.escape(varName)}\b""")
            val usedElsewhere = lines.withIndex().any { (i, l) ->
                i != index && varPattern.containsMatchIn(l)
            }

            if (!usedElsewhere) {
                issues += DeadCodeIssue(
                    type = DeadCodeType.UNUSED_VARIABLE,
                    filePath = file.relativePath,
                    lineNumber = index + 1,
                    name = varName,
                    message = "Variable '$varName' is declared but never referenced elsewhere in the file.",
                    severity = ViolationSeverity.WARNING,
                    confidence = 0.8
                )
            }
        }
        return issues
    }

    private fun detectUnusedParameters(file: ScannedFile, lines: List<String>): List<DeadCodeIssue> {
        val issues = mutableListOf<DeadCodeIssue>()
        val fullText = lines.joinToString("\n")

        for ((index, line) in lines.withIndex()) {
            val methodMatch = METHOD_DECLARATION_REGEX.find(line) ?: continue

            // Skip override methods — parameters are dictated by the superclass
            if (line.trimStart().startsWith("override")) continue

            val methodName = methodMatch.groupValues[1]
            val paramsStr = methodMatch.groupValues[2].trim()
            if (paramsStr.isBlank()) continue

            // Check if this is a single-expression function (= on the same line or next)
            val isSingleExpr = line.contains("=") && !line.contains("{")
            if (isSingleExpr) continue

            // Extract the method body
            val methodBody = extractMethodBody(lines, index) ?: continue

            val params = parseParameterNames(paramsStr)
            for (param in params) {
                if (param.startsWith("_")) continue

                val paramPattern = Regex("""\b${Regex.escape(param)}\b""")
                if (!paramPattern.containsMatchIn(methodBody)) {
                    issues += DeadCodeIssue(
                        type = DeadCodeType.UNUSED_PARAMETER,
                        filePath = file.relativePath,
                        lineNumber = index + 1,
                        name = param,
                        message = "Parameter '$param' in method '$methodName' is never used in the method body.",
                        severity = ViolationSeverity.WARNING,
                        confidence = 0.75
                    )
                }
            }
        }
        return issues
    }

    private fun detectUnreachableCode(file: ScannedFile, lines: List<String>): List<DeadCodeIssue> {
        val issues = mutableListOf<DeadCodeIssue>()
        val allowedAfterTerminator = setOf("}", "catch", "finally", "else", ")")

        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trim()
            val indent = line.length - line.trimStart().length

            // Check if this line ends with a terminator statement
            val isTerminator = TERMINATOR_KEYWORDS.any { keyword ->
                trimmed.startsWith("$keyword ") || trimmed.startsWith("$keyword;") ||
                    trimmed == keyword || trimmed.startsWith("$keyword(")
            }
            if (!isTerminator) continue

            // Look at the next non-blank line
            var nextIndex = index + 1
            while (nextIndex < lines.size && lines[nextIndex].isBlank()) {
                nextIndex++
            }
            if (nextIndex >= lines.size) continue

            val nextLine = lines[nextIndex].trim()
            val nextIndent = lines[nextIndex].length - lines[nextIndex].trimStart().length

            // The next line is unreachable if it's at the same or deeper indent and not a
            // closing brace, catch, finally, or else
            if (nextIndent >= indent && allowedAfterTerminator.none { nextLine.startsWith(it) }) {
                issues += DeadCodeIssue(
                    type = DeadCodeType.UNREACHABLE_CODE,
                    filePath = file.relativePath,
                    lineNumber = nextIndex + 1,
                    name = nextLine.take(60),
                    message = "Code after '${trimmed.take(30)}' is unreachable.",
                    severity = ViolationSeverity.WARNING,
                    confidence = 0.85
                )
            }
        }
        return issues
    }

    private fun detectEmptyCatch(file: ScannedFile, lines: List<String>): List<DeadCodeIssue> {
        val issues = mutableListOf<DeadCodeIssue>()

        for ((index, line) in lines.withIndex()) {
            if (!CATCH_START_REGEX.containsMatchIn(line) && !CATCH_REGEX.containsMatchIn(line)) continue

            // Find the opening brace for this catch
            val braceIndex = findOpeningBrace(lines, index)
            if (braceIndex == null) continue

            val body = extractBlockBody(lines, braceIndex)
            if (body != null && body.isBlank()) {
                issues += DeadCodeIssue(
                    type = DeadCodeType.EMPTY_CATCH,
                    filePath = file.relativePath,
                    lineNumber = index + 1,
                    name = "catch",
                    message = "Empty catch block swallows exception silently.",
                    severity = ViolationSeverity.WARNING,
                    confidence = 0.95
                )
            }
        }
        return issues
    }

    private fun detectEmptyMethods(file: ScannedFile, lines: List<String>): List<DeadCodeIssue> {
        val issues = mutableListOf<DeadCodeIssue>()

        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trim()

            // Skip abstract and interface methods
            if (trimmed.contains("abstract ")) continue
            if (file.isInterface && !trimmed.contains("{")) continue

            val methodMatch = METHOD_DECLARATION_REGEX.find(line) ?: continue
            val methodName = methodMatch.groupValues[1]

            // Skip expression-body functions
            if (line.contains("=") && !line.contains("{")) continue

            val braceIndex = findOpeningBrace(lines, index) ?: continue
            val body = extractBlockBody(lines, braceIndex)

            if (body != null && body.isBlank()) {
                issues += DeadCodeIssue(
                    type = DeadCodeType.EMPTY_METHOD,
                    filePath = file.relativePath,
                    lineNumber = index + 1,
                    name = methodName,
                    message = "Method '$methodName' has an empty body.",
                    severity = ViolationSeverity.INFO,
                    confidence = 0.9
                )
            }
        }
        return issues
    }

    private fun detectCommentedCode(file: ScannedFile, lines: List<String>): List<DeadCodeIssue> {
        val issues = mutableListOf<DeadCodeIssue>()
        var consecutiveCommentStart = -1
        var consecutiveCount = 0
        var codeLikeCount = 0

        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("//")) {
                if (consecutiveCommentStart == -1) {
                    consecutiveCommentStart = index
                    consecutiveCount = 0
                    codeLikeCount = 0
                }
                consecutiveCount++
                val commentContent = trimmed.removePrefix("//").trim()
                if (CODE_LIKE_PATTERNS.containsMatchIn(commentContent)) {
                    codeLikeCount++
                }
            } else {
                if (consecutiveCount > 3 && codeLikeCount >= 2) {
                    issues += DeadCodeIssue(
                        type = DeadCodeType.COMMENTED_CODE,
                        filePath = file.relativePath,
                        lineNumber = consecutiveCommentStart + 1,
                        name = "comment-block",
                        message = "$consecutiveCount consecutive commented lines with code-like patterns detected.",
                        severity = ViolationSeverity.INFO,
                        confidence = (codeLikeCount.toDouble() / consecutiveCount).coerceIn(0.5, 0.9)
                    )
                }
                consecutiveCommentStart = -1
                consecutiveCount = 0
                codeLikeCount = 0
            }
        }

        // Handle trailing block at end of file
        if (consecutiveCount > 3 && codeLikeCount >= 2) {
            issues += DeadCodeIssue(
                type = DeadCodeType.COMMENTED_CODE,
                filePath = file.relativePath,
                lineNumber = consecutiveCommentStart + 1,
                name = "comment-block",
                message = "$consecutiveCount consecutive commented lines with code-like patterns detected.",
                severity = ViolationSeverity.INFO,
                confidence = (codeLikeCount.toDouble() / consecutiveCount).coerceIn(0.5, 0.9)
            )
        }
        return issues
    }

    private fun detectUnusedPrivateMethods(file: ScannedFile, lines: List<String>): List<DeadCodeIssue> {
        val issues = mutableListOf<DeadCodeIssue>()
        val privateMethods = mutableListOf<Pair<Int, String>>() // lineNum, methodName

        for ((index, line) in lines.withIndex()) {
            val match = PRIVATE_METHOD_REGEX.find(line) ?: continue
            privateMethods += (index + 1) to match.groupValues[1]
        }

        val fullText = lines.joinToString("\n")

        for ((lineNum, methodName) in privateMethods) {
            // Search for calls to this method elsewhere in the file.
            // A call looks like: methodName( or ::methodName or .methodName(
            val callPattern = Regex("""(?<!fun\s)(?:\.|\b|::)${Regex.escape(methodName)}\s*[\(<]""")
            val declarationLine = lines[lineNum - 1]

            val usages = lines.withIndex().count { (i, l) ->
                i != (lineNum - 1) && callPattern.containsMatchIn(l)
            }

            if (usages == 0) {
                issues += DeadCodeIssue(
                    type = DeadCodeType.UNUSED_PRIVATE_METHOD,
                    filePath = file.relativePath,
                    lineNumber = lineNum,
                    name = methodName,
                    message = "Private method '$methodName' is never called within the file.",
                    severity = ViolationSeverity.WARNING,
                    confidence = 0.85
                )
            }
        }
        return issues
    }

    private fun detectRedundantNullChecks(file: ScannedFile, lines: List<String>): List<DeadCodeIssue> {
        val issues = mutableListOf<DeadCodeIssue>()

        // Collect non-nullable val/var declarations (Kotlin-specific: Type without ?)
        val nonNullableVars = mutableSetOf<String>()
        for (line in lines) {
            val match = NON_NULLABLE_VAL_REGEX.find(line) ?: continue
            val varName = match.groupValues[1]
            val typeName = match.groupValues[2]
            // If the type doesn't contain ?, it's non-nullable in Kotlin
            if (!line.contains("$typeName?")) {
                nonNullableVars += varName
            }
        }

        for ((index, line) in lines.withIndex()) {
            val match = NULL_CHECK_REGEX.find(line) ?: continue
            val varName = match.groupValues[1]
            if (varName in nonNullableVars) {
                issues += DeadCodeIssue(
                    type = DeadCodeType.REDUNDANT_NULL_CHECK,
                    filePath = file.relativePath,
                    lineNumber = index + 1,
                    name = varName,
                    message = "Null check on '$varName' is redundant — it is declared as non-nullable.",
                    severity = ViolationSeverity.INFO,
                    confidence = 0.8
                )
            }
        }
        return issues
    }

    private fun detectDeadConditionals(file: ScannedFile, lines: List<String>): List<DeadCodeIssue> {
        val issues = mutableListOf<DeadCodeIssue>()

        for ((index, line) in lines.withIndex()) {
            val match = DEAD_CONDITIONAL_REGEX.find(line) ?: continue
            val condition = match.groupValues[1]
            val keyword = if (line.contains("while")) "while" else "if"

            // while(true) is an intentional infinite loop pattern — skip it
            if (keyword == "while" && condition == "true") continue

            issues += DeadCodeIssue(
                type = DeadCodeType.DEAD_CONDITIONAL,
                filePath = file.relativePath,
                lineNumber = index + 1,
                name = "$keyword($condition)",
                message = "Condition '$keyword($condition)' is always $condition — this is dead or trivial code.",
                severity = if (condition == "false") ViolationSeverity.WARNING else ViolationSeverity.INFO,
                confidence = 0.95
            )
        }
        return issues
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private fun suggestedFixFor(type: DeadCodeType, name: String): String = when (type) {
        DeadCodeType.UNUSED_IMPORT -> "Remove unused import '$name'."
        DeadCodeType.UNUSED_VARIABLE -> "Remove unused variable '$name' or prefix with '_' if intentionally unused."
        DeadCodeType.UNUSED_PARAMETER -> "Remove unused parameter '$name' or prefix with '_' if required by interface."
        DeadCodeType.UNREACHABLE_CODE -> "Remove unreachable code after the terminating statement."
        DeadCodeType.EMPTY_CATCH -> "Handle the exception or add a comment explaining why it is ignored."
        DeadCodeType.EMPTY_METHOD -> "Implement the method body or remove it if unnecessary."
        DeadCodeType.COMMENTED_CODE -> "Remove commented-out code; use version control instead."
        DeadCodeType.UNUSED_PRIVATE_METHOD -> "Remove unused private method '$name'."
        DeadCodeType.REDUNDANT_NULL_CHECK -> "Remove the redundant null check on '$name' — it cannot be null."
        DeadCodeType.DEAD_CONDITIONAL -> "Simplify or remove the dead conditional '$name'."
    }

    private fun parseParameterNames(paramsStr: String): List<String> {
        if (paramsStr.isBlank()) return emptyList()
        return paramsStr.split(",").mapNotNull { param ->
            val trimmed = param.trim()
            // Handle Kotlin-style: "name: Type", Java-style: "Type name", or just "name"
            val colonIndex = trimmed.indexOf(':')
            if (colonIndex > 0) {
                // Kotlin-style: name: Type
                trimmed.substring(0, colonIndex).trim()
                    .removePrefix("vararg ")
                    .removePrefix("noinline ")
                    .removePrefix("crossinline ")
                    .trim()
            } else {
                // Java-style: last token is the name
                val tokens = trimmed.split(Regex("""\s+"""))
                tokens.lastOrNull()?.takeIf { it.matches(Regex("""\w+""")) }
            }
        }
    }

    /**
     * Extracts the text body of the method starting at [startLineIndex].
     * Returns the body between the first `{` and its matching `}`, excluding both braces.
     * Returns null if no brace-delimited body is found.
     */
    private fun extractMethodBody(lines: List<String>, startLineIndex: Int): String? {
        val braceStart = findOpeningBrace(lines, startLineIndex) ?: return null
        return extractBlockBody(lines, braceStart)
    }

    /**
     * Finds the line index containing the opening `{` starting from [fromLine].
     * Searches at most 3 lines ahead to handle multi-line signatures.
     */
    private fun findOpeningBrace(lines: List<String>, fromLine: Int): Int? {
        val searchLimit = (fromLine + 4).coerceAtMost(lines.size)
        for (i in fromLine until searchLimit) {
            if (lines[i].contains("{")) return i
        }
        return null
    }

    /**
     * Given a line index containing an opening `{`, extracts all text between
     * that `{` and its matching `}`. Returns the body content or null.
     */
    private fun extractBlockBody(lines: List<String>, braceLineIndex: Int): String? {
        var depth = 0
        val body = StringBuilder()
        var started = false

        for (i in braceLineIndex until lines.size) {
            val line = lines[i]
            for (ch in line) {
                if (ch == '{') {
                    if (!started) {
                        // This is the opening brace we care about
                        depth = 1
                        started = true
                    } else {
                        body.append(ch)
                        depth++
                    }
                } else if (ch == '}') {
                    if (!started) continue // skip braces before our target opening
                    depth--
                    if (depth == 0) {
                        return body.toString().trim()
                    }
                    body.append(ch)
                } else if (started) {
                    body.append(ch)
                }
            }
            if (started) body.append('\n')
        }
        return null
    }
}
