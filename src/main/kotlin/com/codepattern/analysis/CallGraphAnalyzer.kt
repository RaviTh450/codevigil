package com.codepattern.analysis

import com.codepattern.models.Violation
import com.codepattern.models.ViolationCategory
import com.codepattern.models.ViolationSeverity
import com.codepattern.scanner.ScannedFile
import com.codepattern.scanner.ScannedProject
import java.io.File

/**
 * Builds a function-level call graph across the project and analyzes:
 *
 * 1. Longest call chain (deepest execution path)
 * 2. Recursive call detection
 * 3. Fan-in / fan-out per function (how many callers / callees)
 * 4. Hub functions (called by many, potential bottlenecks)
 * 5. Orphan functions (never called, potentially dead code)
 * 6. Visualization in ASCII, Mermaid, and DOT formats
 */
object CallGraphAnalyzer {

    data class FunctionNode(
        val name: String,           // fully qualified: "ClassName.methodName" or "file:functionName"
        val filePath: String,
        val lineNumber: Int,
        val paramCount: Int
    )

    data class CallEdge(
        val caller: String,
        val callee: String
    )

    data class CallGraph(
        val nodes: Map<String, FunctionNode>,
        val edges: List<CallEdge>,
        val adjacency: Map<String, Set<String>>,        // caller -> callees
        val reverseAdjacency: Map<String, Set<String>>  // callee -> callers
    )

    data class CallGraphStats(
        val totalFunctions: Int,
        val totalCalls: Int,
        val longestPath: List<String>,
        val longestPathLength: Int,
        val recursiveFunctions: List<String>,
        val hubFunctions: List<Pair<String, Int>>,   // name, fan-in count
        val highFanOut: List<Pair<String, Int>>,      // name, fan-out count
        val orphanFunctions: List<String>             // never called
    )

    /**
     * Build the call graph from all project files.
     */
    fun buildCallGraph(project: ScannedProject, basePath: String): CallGraph {
        val nodes = mutableMapOf<String, FunctionNode>()
        val edges = mutableListOf<CallEdge>()

        // Phase 1: Collect all function declarations
        for (file in project.files) {
            try {
                val lines = File("$basePath/${file.relativePath}").readLines()
                val functions = extractFunctions(file, lines)
                for (fn in functions) {
                    nodes[fn.name] = fn
                }
            } catch (_: Exception) {}
        }

        val allFunctionNames = nodes.keys.toSet()
        val shortNames = mutableMapOf<String, String>() // short name -> full name
        for (name in allFunctionNames) {
            val short = name.substringAfterLast(".")
            // Only map if unique
            if (short !in shortNames) {
                shortNames[short] = name
            } else {
                shortNames.remove(short) // ambiguous, don't use short name
            }
        }

        // Phase 2: Detect function calls
        for (file in project.files) {
            try {
                val lines = File("$basePath/${file.relativePath}").readLines()
                val fileFunctions = extractFunctions(file, lines)

                for (fn in fileFunctions) {
                    val fnBody = extractFunctionBody(lines, fn.lineNumber - 1)
                    val callees = detectCallsInBody(fnBody, allFunctionNames, shortNames)
                    for (callee in callees) {
                        if (callee != fn.name) { // Skip self for non-recursive tracking (handled separately)
                            edges += CallEdge(caller = fn.name, callee = callee)
                        }
                        if (callee == fn.name) {
                            edges += CallEdge(caller = fn.name, callee = callee) // self-call = recursion
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // Build adjacency maps
        val adjacency = mutableMapOf<String, MutableSet<String>>()
        val reverseAdjacency = mutableMapOf<String, MutableSet<String>>()

        for (edge in edges) {
            adjacency.getOrPut(edge.caller) { mutableSetOf() }.add(edge.callee)
            reverseAdjacency.getOrPut(edge.callee) { mutableSetOf() }.add(edge.caller)
        }

        return CallGraph(
            nodes = nodes,
            edges = edges,
            adjacency = adjacency,
            reverseAdjacency = reverseAdjacency
        )
    }

    /**
     * Analyze the call graph and compute statistics.
     */
    fun analyzeGraph(graph: CallGraph): CallGraphStats {
        // Longest path (DFS with memoization)
        val longestPath = findLongestPath(graph)

        // Recursive functions (self-edges or cycles)
        val recursive = graph.edges.filter { it.caller == it.callee }.map { it.caller }.distinct()

        // Hub functions (high fan-in — called by many)
        val hubFunctions = graph.reverseAdjacency
            .map { (fn, callers) -> Pair(fn, callers.size) }
            .filter { it.second >= 5 }
            .sortedByDescending { it.second }

        // High fan-out (calls many functions)
        val highFanOut = graph.adjacency
            .map { (fn, callees) -> Pair(fn, callees.size) }
            .filter { it.second >= 8 }
            .sortedByDescending { it.second }

        // Orphan functions (declared but never called)
        val calledFunctions = graph.reverseAdjacency.keys
        val orphans = graph.nodes.keys.filter { it !in calledFunctions }
            .filter { !it.contains("main") && !it.contains("init") &&
                    !it.contains("test") && !it.contains("Test") &&
                    !it.contains("setup") && !it.contains("teardown") }

        return CallGraphStats(
            totalFunctions = graph.nodes.size,
            totalCalls = graph.edges.size,
            longestPath = longestPath,
            longestPathLength = longestPath.size,
            recursiveFunctions = recursive,
            hubFunctions = hubFunctions,
            highFanOut = highFanOut,
            orphanFunctions = orphans
        )
    }

    /**
     * Generate violations from call graph analysis.
     */
    fun toViolations(graph: CallGraph, stats: CallGraphStats): List<Violation> {
        val violations = mutableListOf<Violation>()

        // Longest path warning
        if (stats.longestPathLength > 10) {
            val startFn = stats.longestPath.firstOrNull() ?: "unknown"
            val node = graph.nodes[startFn]
            violations += Violation(
                ruleName = "Deep Call Chain",
                patternName = "Call Graph Analysis",
                message = "Longest execution path is ${stats.longestPathLength} functions deep: ${stats.longestPath.take(5).joinToString(" -> ")}${if (stats.longestPathLength > 5) " -> ..." else ""}. Deep chains increase stack overflow risk and make debugging harder.",
                severity = if (stats.longestPathLength > 20) ViolationSeverity.ERROR else ViolationSeverity.WARNING,
                filePath = node?.filePath ?: "",
                lineNumber = node?.lineNumber ?: 1,
                suggestedFix = "Refactor deep call chains by flattening logic, using iteration instead of recursion, or introducing intermediate orchestration layers.",
                category = ViolationCategory.COMPLEXITY,
                confidence = 0.85,
                ruleId = "callgraph-deep-chain"
            )
        }

        // Recursive functions
        for (fnName in stats.recursiveFunctions) {
            val node = graph.nodes[fnName] ?: continue
            violations += Violation(
                ruleName = "Recursive Function",
                patternName = "Call Graph Analysis",
                message = "Function '${fnName.substringAfterLast(".")}' calls itself recursively. Ensure there's a proper base case and the recursion depth is bounded.",
                severity = ViolationSeverity.WARNING,
                filePath = node.filePath,
                lineNumber = node.lineNumber,
                suggestedFix = "Consider converting to iterative approach with an explicit stack, or add @tailrec (Kotlin) for tail-call optimization.",
                category = ViolationCategory.COMPLEXITY,
                confidence = 0.90,
                ruleId = "callgraph-recursion"
            )
        }

        // High fan-out functions
        for ((fnName, count) in stats.highFanOut) {
            val node = graph.nodes[fnName] ?: continue
            violations += Violation(
                ruleName = "High Fan-Out",
                patternName = "Call Graph Analysis",
                message = "Function '${fnName.substringAfterLast(".")}' calls $count other functions. High fan-out indicates the function has too many responsibilities.",
                severity = ViolationSeverity.WARNING,
                filePath = node.filePath,
                lineNumber = node.lineNumber,
                suggestedFix = "Extract groups of related calls into helper methods or use the Facade pattern to reduce direct dependencies.",
                category = ViolationCategory.COMPLEXITY,
                confidence = 0.80,
                ruleId = "callgraph-fanout"
            )
        }

        return violations
    }

    // ── Visualization ──

    fun generateAscii(graph: CallGraph, stats: CallGraphStats): String {
        val sb = StringBuilder()
        sb.appendLine("=== Function Call Graph Analysis ===")
        sb.appendLine()
        sb.appendLine("Total functions: ${stats.totalFunctions}")
        sb.appendLine("Total call edges: ${stats.totalCalls}")
        sb.appendLine()

        // Longest path
        sb.appendLine("Longest Execution Path (${stats.longestPathLength} functions):")
        for ((idx, fn) in stats.longestPath.withIndex()) {
            val indent = "  ".repeat(idx)
            val shortName = fn.substringAfterLast(".")
            val connector = if (idx == 0) "" else "-> "
            sb.appendLine("  $indent$connector$shortName")
        }
        sb.appendLine()

        // Recursive functions
        if (stats.recursiveFunctions.isNotEmpty()) {
            sb.appendLine("Recursive Functions:")
            for (fn in stats.recursiveFunctions) {
                sb.appendLine("  [RECURSIVE] ${fn.substringAfterLast(".")}")
            }
            sb.appendLine()
        }

        // Hub functions
        if (stats.hubFunctions.isNotEmpty()) {
            sb.appendLine("Hub Functions (most called):")
            for ((fn, count) in stats.hubFunctions.take(10)) {
                val bar = "#".repeat(minOf(count, 40))
                sb.appendLine("  ${fn.substringAfterLast(".").padEnd(30)} $bar ($count callers)")
            }
            sb.appendLine()
        }

        // High fan-out
        if (stats.highFanOut.isNotEmpty()) {
            sb.appendLine("High Fan-Out Functions (most calls):")
            for ((fn, count) in stats.highFanOut.take(10)) {
                val bar = ">".repeat(minOf(count, 40))
                sb.appendLine("  ${fn.substringAfterLast(".").padEnd(30)} $bar ($count callees)")
            }
            sb.appendLine()
        }

        // Call matrix for top functions
        val topFunctions = (stats.hubFunctions.map { it.first } + stats.highFanOut.map { it.first })
            .distinct().take(10)
        if (topFunctions.size >= 2) {
            sb.appendLine("Call Matrix (top functions):")
            val colWidth = 12
            sb.append(" ".repeat(colWidth))
            for (fn in topFunctions) {
                sb.append(fn.substringAfterLast(".").take(colWidth - 1).padEnd(colWidth))
            }
            sb.appendLine()
            sb.appendLine("-".repeat(colWidth * (topFunctions.size + 1)))

            for (from in topFunctions) {
                sb.append(from.substringAfterLast(".").take(colWidth - 1).padEnd(colWidth))
                for (to in topFunctions) {
                    val calls = graph.adjacency[from]?.contains(to) == true
                    val cell = if (from == to) "-" else if (calls) "X" else "."
                    sb.append(cell.padEnd(colWidth))
                }
                sb.appendLine()
            }
        }

        return sb.toString()
    }

    fun generateMermaid(graph: CallGraph, stats: CallGraphStats): String {
        val sb = StringBuilder()
        sb.appendLine("graph TD")

        // Limit to most important functions to keep the graph readable
        val importantFns = (stats.longestPath +
                stats.hubFunctions.map { it.first } +
                stats.highFanOut.map { it.first } +
                stats.recursiveFunctions).distinct().take(30)

        val importantSet = importantFns.toSet()

        for (fn in importantFns) {
            val short = fn.substringAfterLast(".").replace(Regex("[^a-zA-Z0-9]"), "_")
            val label = fn.substringAfterLast(".")
            sb.appendLine("    $short[\"$label\"]")
        }

        for (edge in graph.edges) {
            if (edge.caller in importantSet && edge.callee in importantSet) {
                val from = edge.caller.substringAfterLast(".").replace(Regex("[^a-zA-Z0-9]"), "_")
                val to = edge.callee.substringAfterLast(".").replace(Regex("[^a-zA-Z0-9]"), "_")
                if (edge.caller == edge.callee) {
                    sb.appendLine("    $from -->|recursive| $from")
                } else {
                    sb.appendLine("    $from --> $to")
                }
            }
        }

        // Style recursive and hub functions
        sb.appendLine()
        for (fn in stats.recursiveFunctions.filter { it in importantSet }) {
            val id = fn.substringAfterLast(".").replace(Regex("[^a-zA-Z0-9]"), "_")
            sb.appendLine("    style $id fill:#ff6b6b,color:white")
        }
        for ((fn, _) in stats.hubFunctions.filter { it.first in importantSet }) {
            val id = fn.substringAfterLast(".").replace(Regex("[^a-zA-Z0-9]"), "_")
            sb.appendLine("    style $id fill:#3498db,color:white")
        }

        // Highlight longest path
        if (stats.longestPath.size >= 2) {
            sb.appendLine()
            sb.appendLine("    %% Longest path highlighted")
            for (j in 0 until stats.longestPath.size - 1) {
                val from = stats.longestPath[j]
                val to = stats.longestPath[j + 1]
                if (from in importantSet && to in importantSet) {
                    val fromId = from.substringAfterLast(".").replace(Regex("[^a-zA-Z0-9]"), "_")
                    val toId = to.substringAfterLast(".").replace(Regex("[^a-zA-Z0-9]"), "_")
                    sb.appendLine("    linkStyle ${graph.edges.indexOfFirst { it.caller == from && it.callee == to }} stroke:red,stroke-width:3px")
                }
            }
        }

        return sb.toString()
    }

    fun generateDot(graph: CallGraph, stats: CallGraphStats): String {
        val sb = StringBuilder()
        sb.appendLine("digraph CallGraph {")
        sb.appendLine("    rankdir=TB;")
        sb.appendLine("    node [shape=box, style=rounded, fontname=\"Helvetica\", fontsize=10];")
        sb.appendLine("    edge [fontsize=8];")
        sb.appendLine()

        val importantFns = (stats.longestPath +
                stats.hubFunctions.map { it.first } +
                stats.highFanOut.map { it.first } +
                stats.recursiveFunctions).distinct().take(40)
        val importantSet = importantFns.toSet()

        // Nodes
        for (fn in importantFns) {
            val id = sanitizeId(fn)
            val label = fn.substringAfterLast(".")
            val node = graph.nodes[fn]
            val fileInfo = if (node != null) "\\n${node.filePath.substringAfterLast("/")}:${node.lineNumber}" else ""

            val style = when {
                fn in stats.recursiveFunctions -> ", style=\"filled\", fillcolor=\"#ff6b6b\", fontcolor=\"white\""
                stats.hubFunctions.any { it.first == fn } -> ", style=\"filled\", fillcolor=\"#3498db\", fontcolor=\"white\""
                stats.highFanOut.any { it.first == fn } -> ", style=\"filled\", fillcolor=\"#f39c12\""
                else -> ""
            }

            sb.appendLine("    $id [label=\"$label$fileInfo\"$style];")
        }

        sb.appendLine()

        // Edges
        for (edge in graph.edges) {
            if (edge.caller in importantSet && edge.callee in importantSet) {
                val from = sanitizeId(edge.caller)
                val to = sanitizeId(edge.callee)
                val isOnLongestPath = stats.longestPath.windowed(2).any {
                    it[0] == edge.caller && it[1] == edge.callee
                }
                val style = when {
                    edge.caller == edge.callee -> " [color=red, label=\"recursive\"]"
                    isOnLongestPath -> " [color=red, penwidth=2, label=\"longest path\"]"
                    else -> ""
                }
                sb.appendLine("    $from -> $to$style;")
            }
        }

        sb.appendLine()
        sb.appendLine("    // Legend")
        sb.appendLine("    subgraph cluster_legend {")
        sb.appendLine("        label=\"Legend\";")
        sb.appendLine("        style=dashed;")
        sb.appendLine("        legend_recursive [label=\"Recursive\", style=filled, fillcolor=\"#ff6b6b\", fontcolor=white];")
        sb.appendLine("        legend_hub [label=\"Hub (high fan-in)\", style=filled, fillcolor=\"#3498db\", fontcolor=white];")
        sb.appendLine("        legend_fanout [label=\"High fan-out\", style=filled, fillcolor=\"#f39c12\"];")
        sb.appendLine("    }")

        sb.appendLine("}")
        return sb.toString()
    }

    // ── Internal Helpers ──

    private fun extractFunctions(file: ScannedFile, lines: List<String>): List<FunctionNode> {
        val functions = mutableListOf<FunctionNode>()
        var currentClass: String? = null

        for ((i, line) in lines.withIndex()) {
            val trimmed = line.trim()

            // Track class context
            val classMatch = Regex("""(?:class|object|interface)\s+(\w+)""").find(trimmed)
            if (classMatch != null) {
                currentClass = classMatch.groupValues[1]
            }

            // Detect function declarations across languages
            val fnMatch = detectFunctionDeclaration(trimmed, file.language)
            if (fnMatch != null) {
                val qualifiedName = if (currentClass != null) "$currentClass.${fnMatch.first}" else "${file.relativePath.substringBeforeLast(".")}:${fnMatch.first}"
                functions += FunctionNode(
                    name = qualifiedName,
                    filePath = file.relativePath,
                    lineNumber = i + 1,
                    paramCount = fnMatch.second
                )
            }
        }

        return functions
    }

    private fun detectFunctionDeclaration(line: String, language: String): Pair<String, Int>? {
        if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) return null

        val patterns = when (language) {
            "kotlin" -> listOf(
                Regex("""(?:fun|override\s+fun|private\s+fun|public\s+fun|internal\s+fun|suspend\s+fun)\s+(\w+)\s*\(([^)]*)\)""")
            )
            "java" -> listOf(
                Regex("""(?:public|private|protected|static|\s)*\s+\w+\s+(\w+)\s*\(([^)]*)\)""")
            )
            "python" -> listOf(
                Regex("""def\s+(\w+)\s*\(([^)]*)\)""")
            )
            "typescript", "javascript" -> listOf(
                Regex("""(?:function|async function)\s+(\w+)\s*\(([^)]*)\)"""),
                Regex("""(?:const|let|var)\s+(\w+)\s*=\s*(?:async\s+)?\(([^)]*)\)\s*=>"""),
                Regex("""(\w+)\s*\(([^)]*)\)\s*[:{]""")
            )
            "go" -> listOf(
                Regex("""func\s+(?:\(\w+\s+\*?\w+\)\s+)?(\w+)\s*\(([^)]*)\)""")
            )
            "rust" -> listOf(
                Regex("""(?:pub\s+)?fn\s+(\w+)\s*\(([^)]*)\)""")
            )
            "csharp" -> listOf(
                Regex("""(?:public|private|protected|internal|static|\s)*\s+\w+\s+(\w+)\s*\(([^)]*)\)""")
            )
            "ruby" -> listOf(
                Regex("""def\s+(\w+)\s*(?:\(([^)]*)\))?""")
            )
            "php" -> listOf(
                Regex("""function\s+(\w+)\s*\(([^)]*)\)""")
            )
            "swift" -> listOf(
                Regex("""func\s+(\w+)\s*\(([^)]*)\)""")
            )
            else -> return null
        }

        for (pattern in patterns) {
            val match = pattern.find(line)
            if (match != null) {
                val name = match.groupValues[1]
                val params = match.groupValues.getOrElse(2) { "" }
                val paramCount = if (params.isBlank()) 0 else params.split(",").size
                // Filter out common non-function matches
                if (name in setOf("if", "while", "for", "switch", "catch", "return", "new", "class")) continue
                return Pair(name, paramCount)
            }
        }

        return null
    }

    private fun extractFunctionBody(lines: List<String>, startIndex: Int): List<String> {
        val body = mutableListOf<String>()
        var braceDepth = 0
        var started = false

        for (i in startIndex until minOf(lines.size, startIndex + 200)) {
            val line = lines[i]
            body += line

            for (ch in line) {
                if (ch == '{') { braceDepth++; started = true }
                if (ch == '}') braceDepth--
            }

            // Python: use indentation instead
            if (!started && i > startIndex && !lines[startIndex].contains("{")) {
                if (line.isNotBlank() && !line.startsWith(" ") && !line.startsWith("\t") && i > startIndex + 1) {
                    break
                }
            }

            if (started && braceDepth <= 0) break
        }

        return body
    }

    private fun detectCallsInBody(body: List<String>, allFunctions: Set<String>, shortNames: Map<String, String>): Set<String> {
        val calls = mutableSetOf<String>()
        val callPattern = Regex("""(\w+)\s*\(""")

        for (line in body) {
            val trimmed = line.trim()
            if (trimmed.startsWith("//") || trimmed.startsWith("*")) continue

            for (match in callPattern.findAll(trimmed)) {
                val calledName = match.groupValues[1]
                if (calledName in setOf("if", "while", "for", "switch", "catch", "return", "new", "class", "when", "fun", "function", "def")) continue

                // Check if this matches a known function (full name or short name)
                val fullName = allFunctions.find { it.endsWith(".$calledName") || it.endsWith(":$calledName") }
                    ?: shortNames[calledName]

                if (fullName != null) {
                    calls += fullName
                }
            }
        }

        return calls
    }

    /**
     * Find the longest path in the call graph using DFS with memoization.
     */
    private fun findLongestPath(graph: CallGraph): List<String> {
        val memo = mutableMapOf<String, List<String>>()
        var bestPath = emptyList<String>()

        fun dfs(node: String, visited: MutableSet<String>): List<String> {
            if (node in visited) return listOf(node) // cycle, stop
            memo[node]?.let { return it }

            visited.add(node)
            val callees = graph.adjacency[node] ?: emptySet()
            var longest = listOf(node)

            for (callee in callees) {
                if (callee !in visited) {
                    val path = listOf(node) + dfs(callee, visited)
                    if (path.size > longest.size) {
                        longest = path
                    }
                }
            }

            visited.remove(node)
            memo[node] = longest
            return longest
        }

        for (node in graph.nodes.keys) {
            val path = dfs(node, mutableSetOf())
            if (path.size > bestPath.size) {
                bestPath = path
            }
        }

        return bestPath
    }

    private fun sanitizeId(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9]"), "_")
    }
}
