package com.codepattern.analysis

import com.codepattern.models.Violation
import com.codepattern.models.ViolationCategory
import com.codepattern.models.ViolationSeverity
import com.codepattern.scanner.ScannedFile
import com.codepattern.scanner.ScannedProject

/**
 * Detects circular import/dependency chains across the project.
 * Uses DFS cycle detection on the import graph.
 */
object CircularDependencyDetector {

    data class DependencyCycle(
        val chain: List<String>,  // file paths forming the cycle
        val length: Int
    ) {
        fun describe(): String {
            return chain.joinToString(" -> ") { it.substringAfterLast("/") } + " -> ${chain.first().substringAfterLast("/")}"
        }
    }

    fun detectCycles(project: ScannedProject): List<DependencyCycle> {
        // Build adjacency list: file -> set of files it imports
        val fileByModule = buildModuleIndex(project.files)
        val graph = buildDependencyGraph(project.files, fileByModule)

        // DFS-based cycle detection
        val visited = mutableSetOf<String>()
        val inStack = mutableSetOf<String>()
        val cycles = mutableListOf<DependencyCycle>()
        val path = mutableListOf<String>()

        for (node in graph.keys) {
            if (node !in visited) {
                dfs(node, graph, visited, inStack, path, cycles)
            }
        }

        // Deduplicate: normalize cycle representation
        return deduplicateCycles(cycles)
    }

    fun toViolations(
        cycles: List<DependencyCycle>,
        patternName: String,
        ruleName: String,
        ruleId: String,
        severity: ViolationSeverity
    ): List<Violation> {
        return cycles.flatMap { cycle ->
            cycle.chain.map { filePath ->
                Violation(
                    ruleName = ruleName,
                    patternName = patternName,
                    message = "Circular dependency detected: ${cycle.describe()}",
                    severity = severity,
                    filePath = filePath,
                    lineNumber = 1,
                    suggestedFix = "Break the cycle by introducing an interface/abstraction, " +
                            "moving shared code to a common module, or restructuring the dependency direction.",
                    category = ViolationCategory.COUPLING,
                    confidence = 0.95,
                    ruleId = ruleId
                )
            }
        }
    }

    private fun buildModuleIndex(files: List<ScannedFile>): Map<String, String> {
        // Map module/package names to file paths for import resolution
        val index = mutableMapOf<String, String>()
        for (file in files) {
            val path = file.relativePath
            // Use file path without extension as module identifier
            val moduleKey = path.substringBeforeLast(".").replace("/", ".")
            index[moduleKey] = path

            // Also index by class name if available
            file.className?.let { index[it] = path }

            // Also index by last path segment
            val shortKey = path.substringBeforeLast(".").substringAfterLast("/")
            index[shortKey] = path
        }
        return index
    }

    private fun buildDependencyGraph(
        files: List<ScannedFile>,
        moduleIndex: Map<String, String>
    ): Map<String, Set<String>> {
        val graph = mutableMapOf<String, MutableSet<String>>()

        for (file in files) {
            val deps = mutableSetOf<String>()
            for (imp in file.imports) {
                // Try to resolve the import to a project file
                val resolved = resolveImport(imp, moduleIndex)
                if (resolved != null && resolved != file.relativePath) {
                    deps.add(resolved)
                }
            }
            graph[file.relativePath] = deps
        }

        return graph
    }

    private fun resolveImport(importPath: String, moduleIndex: Map<String, String>): String? {
        // Direct match
        moduleIndex[importPath]?.let { return it }

        // Try as dotted path
        val dotted = importPath.replace("/", ".")
        moduleIndex[dotted]?.let { return it }

        // Try last segment (class name)
        val lastSegment = importPath.substringAfterLast(".").substringAfterLast("/")
        moduleIndex[lastSegment]?.let { return it }

        // Try partial matches
        for ((key, value) in moduleIndex) {
            if (key.endsWith(".$lastSegment") || key.endsWith("/$lastSegment")) {
                return value
            }
        }

        return null
    }

    private fun dfs(
        node: String,
        graph: Map<String, Set<String>>,
        visited: MutableSet<String>,
        inStack: MutableSet<String>,
        path: MutableList<String>,
        cycles: MutableList<DependencyCycle>
    ) {
        visited.add(node)
        inStack.add(node)
        path.add(node)

        for (neighbor in graph[node] ?: emptySet()) {
            if (neighbor in inStack) {
                // Found a cycle: extract the cycle portion from path
                val cycleStart = path.indexOf(neighbor)
                if (cycleStart >= 0) {
                    val cycle = path.subList(cycleStart, path.size).toList()
                    cycles.add(DependencyCycle(chain = cycle, length = cycle.size))
                }
            } else if (neighbor !in visited) {
                dfs(neighbor, graph, visited, inStack, path, cycles)
            }
        }

        path.removeAt(path.size - 1)
        inStack.remove(node)
    }

    private fun deduplicateCycles(cycles: List<DependencyCycle>): List<DependencyCycle> {
        val seen = mutableSetOf<Set<String>>()
        return cycles.filter { cycle ->
            val key = cycle.chain.toSet()
            if (key in seen) false
            else { seen.add(key); true }
        }
    }
}
