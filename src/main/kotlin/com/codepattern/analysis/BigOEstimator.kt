package com.codepattern.analysis

import com.codepattern.models.Violation
import com.codepattern.models.ViolationCategory
import com.codepattern.models.ViolationSeverity
import com.codepattern.scanner.ScannedFile
import com.codepattern.scanner.ScannedProject
import java.io.File

/**
 * Estimates Big-O time and space complexity for each function via static analysis.
 *
 * Analyzes:
 * - Loop nesting depth → O(n), O(n²), O(n³)
 * - Recursive calls → O(2^n) or O(n!) depending on branching
 * - Data structure operations → O(1), O(log n), O(n)
 * - Internal function calls → aggregates their complexity
 * - Collection creation → space complexity
 * - Sorting/searching calls → known complexities
 *
 * Produces a per-function report with time complexity, space complexity,
 * and the full call chain showing how complexity compounds.
 */
object BigOEstimator {

    data class FunctionComplexity(
        val name: String,
        val filePath: String,
        val lineNumber: Int,
        val timeComplexity: String,         // e.g. "O(n²)"
        val spaceComplexity: String,        // e.g. "O(n)"
        val timeReason: String,             // explanation
        val spaceReason: String,
        val loopDepth: Int,
        val hasRecursion: Boolean,
        val internalCalls: List<String>,    // functions this calls
        val aggregateTimeComplexity: String // including internal calls
    )

    /** Known complexity of standard library operations */
    private val KNOWN_COMPLEXITIES = mapOf(
        // Sorting
        "sort" to "O(n log n)", "sorted" to "O(n log n)", "sortBy" to "O(n log n)",
        "sortedBy" to "O(n log n)", "Arrays.sort" to "O(n log n)", "Collections.sort" to "O(n log n)",
        // Searching
        "binarySearch" to "O(log n)", "indexOf" to "O(n)", "contains" to "O(n)",
        "containsKey" to "O(1)", "get" to "O(1)", "put" to "O(1)",
        // Collection operations
        "filter" to "O(n)", "map" to "O(n)", "forEach" to "O(n)", "flatMap" to "O(n*m)",
        "reduce" to "O(n)", "fold" to "O(n)", "find" to "O(n)", "any" to "O(n)",
        "all" to "O(n)", "none" to "O(n)", "count" to "O(n)", "sum" to "O(n)",
        "distinct" to "O(n)", "groupBy" to "O(n)", "associateBy" to "O(n)",
        // String
        "split" to "O(n)", "replace" to "O(n)", "substring" to "O(n)",
        "matches" to "O(n)", "trim" to "O(n)", "joinToString" to "O(n)",
        // Set operations
        "addAll" to "O(n)", "removeAll" to "O(n)", "retainAll" to "O(n)",
        "intersect" to "O(n)", "union" to "O(n)",
        // Tree/log operations
        "TreeMap" to "O(log n)", "TreeSet" to "O(log n)",
        "PriorityQueue" to "O(log n)", "heappush" to "O(log n)", "heappop" to "O(log n)"
    )

    fun analyze(project: ScannedProject, basePath: String): List<FunctionComplexity> {
        val results = mutableListOf<FunctionComplexity>()
        for (file in project.files) {
            try {
                val lines = File("$basePath/${file.relativePath}").readLines()
                results += analyzeFile(file, lines)
            } catch (_: Exception) {}
        }
        return results
    }

    fun analyzeFile(file: ScannedFile, lines: List<String>): List<FunctionComplexity> {
        val results = mutableListOf<FunctionComplexity>()
        val methods = ComplexityAnalyzer.extractMethods(lines, file.language)

        for (method in methods) {
            val bodyStart = method.startLine - 1
            val bodyEnd = minOf(bodyStart + method.lineCount, lines.size)
            val body = if (bodyStart < lines.size) lines.subList(bodyStart, bodyEnd) else emptyList()

            val analysis = analyzeBody(body, method.name, file.language)
            val internalCalls = detectInternalCalls(body, methods.map { it.name }.toSet())

            // Aggregate: check if any internal call has worse complexity
            val aggregateTime = aggregateComplexity(analysis.timeComplexity, internalCalls, methods, lines, file.language)

            results += FunctionComplexity(
                name = method.name,
                filePath = file.relativePath,
                lineNumber = method.startLine,
                timeComplexity = analysis.timeComplexity,
                spaceComplexity = analysis.spaceComplexity,
                timeReason = analysis.timeReason,
                spaceReason = analysis.spaceReason,
                loopDepth = analysis.loopDepth,
                hasRecursion = analysis.hasRecursion,
                internalCalls = internalCalls,
                aggregateTimeComplexity = aggregateTime
            )
        }

        return results
    }

    fun toViolations(complexities: List<FunctionComplexity>): List<Violation> {
        val violations = mutableListOf<Violation>()

        for (fc in complexities) {
            val rank = complexityRank(fc.aggregateTimeComplexity)

            if (rank >= 3) { // O(n²) or worse
                violations += Violation(
                    ruleName = "Time Complexity: ${fc.aggregateTimeComplexity}",
                    patternName = "Complexity Analysis",
                    message = "Function '${fc.name}' has ${fc.aggregateTimeComplexity} time complexity" +
                            (if (fc.internalCalls.isNotEmpty()) " (including calls to: ${fc.internalCalls.joinToString(", ")})" else "") +
                            ". ${fc.timeReason}",
                    severity = if (rank >= 5) ViolationSeverity.ERROR else ViolationSeverity.WARNING,
                    filePath = fc.filePath,
                    lineNumber = fc.lineNumber,
                    suggestedFix = suggestOptimization(fc),
                    category = ViolationCategory.COMPLEXITY,
                    confidence = 0.80,
                    ruleId = "bigo-time"
                )
            }

            val spaceRank = complexityRank(fc.spaceComplexity)
            if (spaceRank >= 3) {
                violations += Violation(
                    ruleName = "Space Complexity: ${fc.spaceComplexity}",
                    patternName = "Complexity Analysis",
                    message = "Function '${fc.name}' has ${fc.spaceComplexity} space complexity. ${fc.spaceReason}",
                    severity = ViolationSeverity.WARNING,
                    filePath = fc.filePath,
                    lineNumber = fc.lineNumber,
                    suggestedFix = "Consider streaming, pagination, or in-place algorithms to reduce memory usage.",
                    category = ViolationCategory.MEMORY,
                    confidence = 0.75,
                    ruleId = "bigo-space"
                )
            }
        }

        return violations
    }

    /**
     * Generate a per-function complexity report.
     */
    fun generateReport(complexities: List<FunctionComplexity>): String {
        val sb = StringBuilder()
        sb.appendLine("=== Big-O Complexity Report ===")
        sb.appendLine()
        sb.appendLine("${"Function".padEnd(35)} ${"Time".padEnd(12)} ${"Space".padEnd(12)} ${"Aggregate".padEnd(12)} Calls")
        sb.appendLine("-".repeat(100))

        val sorted = complexities.sortedByDescending { complexityRank(it.aggregateTimeComplexity) }

        for (fc in sorted) {
            val name = fc.name.take(34).padEnd(35)
            val time = fc.timeComplexity.padEnd(12)
            val space = fc.spaceComplexity.padEnd(12)
            val agg = fc.aggregateTimeComplexity.padEnd(12)
            val calls = if (fc.internalCalls.isEmpty()) "-" else fc.internalCalls.joinToString(", ")
            sb.appendLine("$name $time $space $agg $calls")
        }

        sb.appendLine()

        // Summary
        val worstTime = sorted.firstOrNull()
        if (worstTime != null) {
            sb.appendLine("Worst time complexity:  ${worstTime.aggregateTimeComplexity} in '${worstTime.name}' (${worstTime.filePath}:${worstTime.lineNumber})")
            sb.appendLine("  Reason: ${worstTime.timeReason}")
        }

        val worstSpace = complexities.maxByOrNull { complexityRank(it.spaceComplexity) }
        if (worstSpace != null) {
            sb.appendLine("Worst space complexity: ${worstSpace.spaceComplexity} in '${worstSpace.name}' (${worstSpace.filePath}:${worstSpace.lineNumber})")
            sb.appendLine("  Reason: ${worstSpace.spaceReason}")
        }

        // Distribution
        sb.appendLine()
        sb.appendLine("Complexity Distribution:")
        val distribution = complexities.groupBy { it.aggregateTimeComplexity }
            .mapValues { it.value.size }
            .toSortedMap(compareBy { complexityRank(it) })
        for ((complexity, count) in distribution) {
            val bar = "#".repeat(minOf(count, 50))
            sb.appendLine("  ${complexity.padEnd(12)} $bar ($count functions)")
        }

        return sb.toString()
    }

    // ── Internal Analysis ──

    private data class BodyAnalysis(
        val timeComplexity: String,
        val spaceComplexity: String,
        val timeReason: String,
        val spaceReason: String,
        val loopDepth: Int,
        val hasRecursion: Boolean
    )

    private fun analyzeBody(body: List<String>, functionName: String, language: String): BodyAnalysis {
        var maxLoopDepth = 0
        var currentLoopDepth = 0
        var hasRecursion = false
        var hasBinaryDivision = false
        var allocatesCollection = false
        var allocatesInLoop = false
        var knownCallComplexity = "O(1)"
        var spaceAllocations = mutableListOf<String>()

        val loopKeywords = setOf("for", "while", "do")

        for (line in body) {
            val trimmed = line.trim()
            if (trimmed.startsWith("//") || trimmed.startsWith("*")) continue

            // Detect loops
            val isLoopStart = loopKeywords.any { keyword ->
                trimmed.startsWith("$keyword ") || trimmed.startsWith("$keyword(") ||
                        trimmed.contains(".forEach") || trimmed.contains(".map ") ||
                        trimmed.contains(".map{") || trimmed.contains(".filter ") ||
                        trimmed.contains(".filter{") || trimmed.contains(".flatMap")
            }

            if (isLoopStart) {
                currentLoopDepth++
                maxLoopDepth = maxOf(maxLoopDepth, currentLoopDepth)
            }

            // Track loop exit
            if (trimmed == "}" && currentLoopDepth > 0) {
                currentLoopDepth--
            }

            // Detect recursion
            if (trimmed.contains("$functionName(") && !trimmed.contains("fun $functionName") &&
                !trimmed.contains("def $functionName") && !trimmed.contains("function $functionName")) {
                hasRecursion = true
            }

            // Detect binary division patterns (binary search, divide & conquer)
            if (trimmed.contains("/ 2") || trimmed.contains(">> 1") ||
                trimmed.contains("mid") || trimmed.contains("pivot")) {
                hasBinaryDivision = true
            }

            // Detect known library call complexities
            for ((call, complexity) in KNOWN_COMPLEXITIES) {
                if (trimmed.contains(".$call(") || trimmed.contains(".$call {")) {
                    if (complexityRank(complexity) > complexityRank(knownCallComplexity)) {
                        knownCallComplexity = complexity
                    }
                }
            }

            // Detect space allocations
            if (trimmed.contains("ArrayList") || trimmed.contains("mutableListOf") ||
                trimmed.contains("HashMap") || trimmed.contains("mutableMapOf") ||
                trimmed.contains("Array(") || trimmed.contains("ByteArray(") ||
                trimmed.contains("new int[") || trimmed.contains("new String[")) {
                allocatesCollection = true
                if (currentLoopDepth > 0) allocatesInLoop = true
                spaceAllocations += "collection"
            }

            // Detect large string building
            if (trimmed.contains("StringBuilder") || trimmed.contains("StringBuffer") ||
                trimmed.contains("buildString")) {
                spaceAllocations += "string buffer"
            }
        }

        // Determine time complexity
        val (timeComplexity, timeReason) = when {
            hasRecursion && hasBinaryDivision -> Pair("O(n log n)", "Recursive with binary division (divide & conquer)")
            hasRecursion && maxLoopDepth > 0 -> Pair("O(2^n)", "Recursive function with loops — exponential branching possible")
            hasRecursion -> Pair("O(2^n)", "Recursive without clear halving — may be exponential")
            maxLoopDepth >= 3 -> Pair("O(n³)", "Triple-nested loops")
            maxLoopDepth == 2 -> {
                val rank = complexityRank(knownCallComplexity)
                if (rank >= 2) Pair("O(n² log n)", "Nested loops with $knownCallComplexity operation inside")
                else Pair("O(n²)", "Double-nested loops")
            }
            maxLoopDepth == 1 -> {
                val rank = complexityRank(knownCallComplexity)
                when {
                    rank >= 2 -> Pair("O(n log n)", "Loop with $knownCallComplexity operation inside")
                    rank >= 1 -> Pair("O(n²)", "Loop with O(n) operation inside (e.g., contains, indexOf)")
                    else -> Pair("O(n)", "Single loop")
                }
            }
            complexityRank(knownCallComplexity) > 0 -> Pair(knownCallComplexity, "Dominated by $knownCallComplexity library call")
            else -> Pair("O(1)", "Constant time — no loops or recursive calls")
        }

        // Determine space complexity
        val (spaceComplexity, spaceReason) = when {
            allocatesInLoop -> Pair("O(n²)", "Allocating collections inside loops — quadratic space growth")
            allocatesCollection && maxLoopDepth > 0 -> Pair("O(n)", "Collection grows proportional to input size")
            hasRecursion -> Pair("O(n)", "Recursive call stack grows with input")
            allocatesCollection -> Pair("O(n)", "Allocates collection proportional to input")
            spaceAllocations.isNotEmpty() -> Pair("O(n)", "Builds ${spaceAllocations.joinToString(", ")}")
            else -> Pair("O(1)", "Constant space — no significant allocations")
        }

        return BodyAnalysis(timeComplexity, spaceComplexity, timeReason, spaceReason, maxLoopDepth, hasRecursion)
    }

    private fun detectInternalCalls(body: List<String>, knownFunctions: Set<String>): List<String> {
        val calls = mutableSetOf<String>()
        val callPattern = Regex("""(\w+)\s*\(""")

        for (line in body) {
            val trimmed = line.trim()
            if (trimmed.startsWith("//") || trimmed.startsWith("*")) continue
            for (match in callPattern.findAll(trimmed)) {
                val name = match.groupValues[1]
                if (name in knownFunctions && name !in setOf("if", "while", "for", "when", "switch", "catch", "return")) {
                    calls += name
                }
            }
        }

        return calls.toList()
    }

    private fun aggregateComplexity(
        ownComplexity: String,
        internalCalls: List<String>,
        allMethods: List<ComplexityAnalyzer.MethodInfo>,
        allLines: List<String>,
        language: String
    ): String {
        var worstRank = complexityRank(ownComplexity)
        var worst = ownComplexity

        for (callName in internalCalls) {
            val method = allMethods.find { it.name == callName } ?: continue
            val bodyStart = method.startLine - 1
            val bodyEnd = minOf(bodyStart + method.lineCount, allLines.size)
            val body = if (bodyStart < allLines.size) allLines.subList(bodyStart, bodyEnd) else emptyList()
            val analysis = analyzeBody(body, method.name, language)

            val callRank = complexityRank(analysis.timeComplexity)
            if (callRank > worstRank) {
                worstRank = callRank
                worst = analysis.timeComplexity
            }
        }

        return worst
    }

    /**
     * Rank complexity for comparison. Higher = worse.
     */
    fun complexityRank(complexity: String): Int {
        return when {
            complexity.contains("n!") || complexity.contains("factorial") -> 7
            complexity.contains("2^n") || complexity.contains("exponential") -> 6
            complexity.contains("n³") || complexity.contains("n^3") -> 5
            complexity.contains("n²") && complexity.contains("log") -> 4
            complexity.contains("n²") || complexity.contains("n^2") -> 3
            complexity.contains("n log n") || complexity.contains("n*m") -> 2
            complexity.contains("O(n)") -> 1
            complexity.contains("log n") -> 0
            else -> -1 // O(1)
        }
    }

    private fun suggestOptimization(fc: FunctionComplexity): String {
        return when {
            fc.hasRecursion -> "Consider memoization (dynamic programming) or converting to iterative with a stack. If doing divide-and-conquer, ensure the problem size halves each time."
            fc.loopDepth >= 3 -> "Triple-nested loops are O(n³). Use hash maps for O(1) lookup, pre-sort data, or break the problem into smaller steps."
            fc.loopDepth == 2 -> "Nested loops are O(n²). Consider using a HashMap/Set for O(1) lookup in the inner loop, or use sorting + two-pointer technique."
            fc.aggregateTimeComplexity.contains("log") -> "Already O(n log n). Check if the sorting is necessary or if a single-pass O(n) solution exists."
            else -> "Profile this function with representative data sizes. Consider algorithmic improvements: hash-based lookups, divide & conquer, or streaming."
        }
    }
}
