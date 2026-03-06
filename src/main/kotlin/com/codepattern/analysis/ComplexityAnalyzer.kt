package com.codepattern.analysis

/**
 * Computes cyclomatic and cognitive complexity from source code lines.
 * Language-agnostic: uses structural keywords common across languages.
 *
 * Cyclomatic complexity follows Thomas McCabe's 1976 paper:
 * CC = number of independent execution paths through a function.
 * Each decision point (if, for, while, case, catch, &&, ||) adds +1.
 * 'else' does NOT increase CC — it's the same edge, not a new decision.
 *
 * Cognitive complexity follows SonarSource's 2017 specification:
 * - Nesting-sensitive: each structural keyword adds (1 + nesting level)
 * - Nesting increments for: if, for, while, do, switch, try, catch
 * - Flat increments (no nesting penalty) for: else, break, continue
 * - Boolean operators (&&, ||) within the same expression counted once
 * - Recursion adds +1
 *
 * References:
 * - McCabe, T.J. "A Complexity Measure" IEEE TSE, 1976
 * - SonarSource "Cognitive Complexity" specification, 2017
 */
object ComplexityAnalyzer {

    data class MethodInfo(
        val name: String,
        val startLine: Int,
        val endLine: Int,
        val lineCount: Int,
        val parameterCount: Int,
        val cyclomaticComplexity: Int,
        val cognitiveComplexity: Int
    )

    // Decision-point keywords that increase cyclomatic complexity
    private val BRANCH_KEYWORDS = setOf(
        "if", "else if", "elif", "elsif", "elseif",
        "case", "when",
        "for", "foreach", "while", "do",
        "catch", "except", "rescue",
        "&&", "||", "and", "or", "??"
    )

    // Keywords that increase cognitive complexity with nesting penalty
    // Per SonarSource spec: these are "structural" keywords that add to nesting
    private val NESTING_KEYWORDS = setOf(
        "if", "else if", "elif", "elsif", "elseif",
        "for", "foreach", "while", "do",
        "switch", "match",
        "catch", "except", "rescue"
    )

    // Keywords that increase nesting depth but don't add cognitive increment
    private val NESTING_ONLY_KEYWORDS = setOf("try")

    // Keywords that increase cognitive complexity without nesting penalty
    // Per SonarSource spec: "else" adds +1 but no nesting penalty
    private val FLAT_COGNITIVE_KEYWORDS = setOf(
        "else", "break", "continue", "goto"
    )

    private val METHOD_PATTERNS = listOf(
        // Java/Kotlin/C#/Dart: type/fun methodName(params) {
        Regex("""(?:(?:public|private|protected|internal|static|abstract|override|suspend|async|final)\s+)*(?:fun\s+|def\s+|func\s+|function\s+|(?:[A-Za-z_<>\[\]?,\s]+\s+))([A-Za-z_]\w*)\s*\(([^)]*)\)"""),
        // Python: def method_name(params):
        Regex("""def\s+([A-Za-z_]\w*)\s*\(([^)]*)\)\s*(?:->.*)?:"""),
        // Go: func (receiver) methodName(params)
        Regex("""func\s+(?:\([^)]*\)\s+)?([A-Za-z_]\w*)\s*\(([^)]*)\)"""),
        // Ruby: def method_name(params)
        Regex("""def\s+([A-Za-z_]\w*[?!]?)\s*(?:\(([^)]*)\))?"""),
        // Rust: fn method_name(params)
        Regex("""fn\s+([A-Za-z_]\w*)\s*(?:<[^>]*>)?\s*\(([^)]*)\)"""),
        // Swift: func methodName(params)
        Regex("""func\s+([A-Za-z_]\w*)\s*\(([^)]*)\)"""),
        // PHP: function methodName(params)
        Regex("""function\s+([A-Za-z_]\w*)\s*\(([^)]*)\)"""),
    )

    fun extractMethods(lines: List<String>, language: String): List<MethodInfo> {
        val methods = mutableListOf<MethodInfo>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            val methodMatch = findMethodDeclaration(line)
            if (methodMatch != null) {
                val (name, params) = methodMatch
                val paramCount = countParameters(params)
                val endLine = findMethodEnd(lines, i, language)
                val methodLines = lines.subList(i, (endLine + 1).coerceAtMost(lines.size))
                val cyclomatic = computeCyclomaticComplexity(methodLines)
                val cognitive = computeCognitiveComplexity(methodLines)

                methods += MethodInfo(
                    name = name,
                    startLine = i + 1,
                    endLine = endLine + 1,
                    lineCount = endLine - i + 1,
                    parameterCount = paramCount,
                    cyclomaticComplexity = cyclomatic,
                    cognitiveComplexity = cognitive
                )
                i = endLine + 1
            } else {
                i++
            }
        }
        return methods
    }

    fun computeFileCyclomaticComplexity(lines: List<String>): Int {
        return computeCyclomaticComplexity(lines)
    }

    fun computeFileCognitiveComplexity(lines: List<String>): Int {
        return computeCognitiveComplexity(lines)
    }

    fun computeMaxNestingDepth(lines: List<String>): Int {
        var maxDepth = 0
        var currentDepth = 0
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("#") || trimmed.startsWith("*")) continue
            // Count brace-based nesting
            for (ch in trimmed) {
                if (ch == '{') currentDepth++
                if (ch == '}') currentDepth = (currentDepth - 1).coerceAtLeast(0)
            }
            maxDepth = maxOf(maxDepth, currentDepth)
            // Also check indentation-based nesting (Python, Ruby)
            if (!trimmed.startsWith("}") && !trimmed.startsWith("{")) {
                val indent = line.length - line.trimStart().length
                val indentDepth = indent / 4 // assume 4-space indent
                maxDepth = maxOf(maxDepth, indentDepth)
            }
        }
        return maxDepth
    }

    private fun computeCyclomaticComplexity(lines: List<String>): Int {
        var complexity = 1 // base path
        for (line in lines) {
            val trimmed = line.trim().lowercase()
            if (trimmed.startsWith("//") || trimmed.startsWith("#") || trimmed.startsWith("*")) continue

            for (keyword in BRANCH_KEYWORDS) {
                if (keyword.length <= 2) {
                    // Operators: count occurrences
                    var idx = 0
                    while (true) {
                        idx = trimmed.indexOf(keyword, idx)
                        if (idx == -1) break
                        // Don't count inside strings (simple heuristic: not between quotes)
                        if (!isInsideString(trimmed, idx)) {
                            complexity++
                        }
                        idx += keyword.length
                    }
                } else {
                    // Keywords: word-boundary match
                    if (containsKeyword(trimmed, keyword)) {
                        complexity++
                    }
                }
            }
        }
        return complexity
    }

    private fun computeCognitiveComplexity(lines: List<String>): Int {
        var complexity = 0
        var nestingLevel = 0

        for (line in lines) {
            val trimmed = line.trim().lowercase()
            if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("#") || trimmed.startsWith("*")) continue

            // Per SonarSource spec: measure nesting at the START of the line,
            // then add complexity, then update nesting for the next line.

            // Check for nesting-only keywords (try — increases nesting but no cognitive increment)
            var isNestingOnly = false
            for (keyword in NESTING_ONLY_KEYWORDS) {
                if (containsKeyword(trimmed, keyword)) {
                    isNestingOnly = true
                    break
                }
            }

            // Check for nesting keywords (if, for, while, etc.)
            var foundNesting = false
            if (!isNestingOnly) {
                for (keyword in NESTING_KEYWORDS) {
                    if (containsKeyword(trimmed, keyword)) {
                        complexity += 1 + nestingLevel  // base increment + nesting penalty
                        foundNesting = true
                        break
                    }
                }
            }

            // Check for flat keywords (else, break, continue)
            if (!foundNesting && !isNestingOnly) {
                for (keyword in FLAT_COGNITIVE_KEYWORDS) {
                    if (containsKeyword(trimmed, keyword)) {
                        complexity += 1  // flat increment, no nesting penalty
                        break
                    }
                }
            }

            // Boolean operator sequences: && and || add +1 each per SonarSource spec
            // but only when they represent a new boolean sequence
            if (!isInsideString(trimmed, 0)) {
                var prevWasBoolOp = false
                for (op in listOf("&&", "||")) {
                    var idx = 0
                    while (true) {
                        idx = trimmed.indexOf(op, idx)
                        if (idx == -1) break
                        if (!isInsideString(trimmed, idx)) {
                            complexity += 1
                        }
                        idx += op.length
                    }
                }
            }

            // Update nesting level for next line (via braces)
            for (ch in trimmed) {
                if (ch == '{') nestingLevel++
                if (ch == '}') nestingLevel = (nestingLevel - 1).coerceAtLeast(0)
            }
        }

        return complexity
    }

    private fun findMethodDeclaration(line: String): Pair<String, String>? {
        // Skip comments, imports, package declarations
        val trimmed = line.trim()
        if (trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*") ||
            trimmed.startsWith("import ") || trimmed.startsWith("package ") ||
            trimmed.startsWith("from ") || trimmed.startsWith("using ")) return null

        for (pattern in METHOD_PATTERNS) {
            val match = pattern.find(trimmed)
            if (match != null) {
                val name = match.groupValues[1]
                val params = match.groupValues.getOrElse(2) { "" }
                // Skip constructors and class declarations
                if (name in setOf("if", "for", "while", "switch", "catch", "class", "interface", "enum")) continue
                return Pair(name, params)
            }
        }
        return null
    }

    private fun findMethodEnd(lines: List<String>, startLine: Int, language: String): Int {
        val indentBased = language in setOf("python", "ruby")

        if (indentBased) {
            val startIndent = lines[startLine].length - lines[startLine].trimStart().length
            for (i in (startLine + 1) until lines.size) {
                val line = lines[i]
                if (line.isBlank()) continue
                val indent = line.length - line.trimStart().length
                if (indent <= startIndent && line.trim().isNotEmpty()) {
                    return (i - 1).coerceAtLeast(startLine)
                }
            }
            return lines.size - 1
        }

        // Brace-based languages
        var braceCount = 0
        var foundOpen = false
        for (i in startLine until lines.size) {
            for (ch in lines[i]) {
                if (ch == '{') { braceCount++; foundOpen = true }
                if (ch == '}') braceCount--
            }
            if (foundOpen && braceCount <= 0) return i
        }
        // If no braces found (single-line method or expression body), return next few lines
        return (startLine + 1).coerceAtMost(lines.size - 1)
    }

    private fun countParameters(params: String): Int {
        val trimmed = params.trim()
        if (trimmed.isEmpty()) return 0
        // Handle self/this/receiver parameters
        val cleaned = trimmed
            .replace(Regex("""^\s*self\s*,?\s*"""), "")
            .replace(Regex("""^\s*this\s*,?\s*"""), "")
            .trim()
        if (cleaned.isEmpty()) return 0
        return cleaned.split(",").count { it.trim().isNotEmpty() }
    }

    private fun containsKeyword(line: String, keyword: String): Boolean {
        var idx = 0
        while (true) {
            idx = line.indexOf(keyword, idx)
            if (idx == -1) return false
            val before = if (idx > 0) line[idx - 1] else ' '
            val after = if (idx + keyword.length < line.length) line[idx + keyword.length] else ' '
            if (!before.isLetterOrDigit() && before != '_' && !after.isLetterOrDigit() && after != '_') {
                if (!isInsideString(line, idx)) return true
            }
            idx += keyword.length
        }
    }

    private fun isInsideString(line: String, position: Int): Boolean {
        var inSingle = false
        var inDouble = false
        for (i in 0 until position) {
            when (line[i]) {
                '\'' -> if (!inDouble && (i == 0 || line[i - 1] != '\\')) inSingle = !inSingle
                '"' -> if (!inSingle && (i == 0 || line[i - 1] != '\\')) inDouble = !inDouble
            }
        }
        return inSingle || inDouble
    }
}
