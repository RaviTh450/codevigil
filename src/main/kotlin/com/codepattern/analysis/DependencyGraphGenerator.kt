package com.codepattern.analysis

import com.codepattern.models.Layer
import com.codepattern.models.PatternSpec
import com.codepattern.models.Violation
import com.codepattern.patterns.PatternMatcher
import com.codepattern.scanner.ScannedFile
import com.codepattern.scanner.ScannedProject

/**
 * Generates dependency graph visualizations in multiple formats:
 * - Mermaid (for GitHub/GitLab/Confluence rendering)
 * - ASCII (for terminal / tool window)
 * - DOT (for Graphviz)
 *
 * Shows both the EXPECTED dependency graph (from pattern spec)
 * and the ACTUAL dependency graph (from real imports).
 */
object DependencyGraphGenerator {

    data class LayerEdge(
        val from: String,
        val to: String,
        val fileCount: Int,       // how many files have this dependency
        val isAllowed: Boolean    // whether this dependency is allowed by the pattern
    )

    /**
     * Build the actual dependency graph between layers from scanned files.
     */
    fun buildActualGraph(
        project: ScannedProject,
        pattern: PatternSpec,
        matcher: PatternMatcher
    ): List<LayerEdge> {
        val classified = matcher.classifyFiles(project, pattern)
        val edges = mutableMapOf<Pair<String, String>, Int>()

        for (layer in pattern.layers) {
            val filesInLayer = classified[layer.name] ?: continue
            for (file in filesInLayer) {
                for (imp in file.imports) {
                    val targetLayer = identifyLayerByImport(imp, pattern.layers)
                    if (targetLayer != null && targetLayer.name != layer.name) {
                        val key = Pair(layer.name, targetLayer.name)
                        edges[key] = (edges[key] ?: 0) + 1
                    }
                }
            }
        }

        return edges.map { (key, count) ->
            val isAllowed = key.second in (pattern.layers.find { it.name == key.first }?.allowedDependencies ?: emptyList())
            LayerEdge(from = key.first, to = key.second, fileCount = count, isAllowed = isAllowed)
        }.sortedByDescending { it.fileCount }
    }

    /**
     * Generate Mermaid diagram syntax.
     */
    fun generateMermaid(pattern: PatternSpec, actualEdges: List<LayerEdge>): String {
        val sb = StringBuilder()
        sb.appendLine("graph TD")

        // Define layer nodes with styling
        for ((i, layer) in pattern.layers.withIndex()) {
            val id = sanitizeId(layer.name)
            sb.appendLine("    $id[\"${layer.name}\"]")
        }

        // Expected edges (dashed green)
        for (layer in pattern.layers) {
            for (dep in layer.allowedDependencies) {
                val fromId = sanitizeId(layer.name)
                val toId = sanitizeId(dep)
                sb.appendLine("    $fromId -.->|allowed| $toId")
            }
        }

        // Actual violation edges (solid red)
        for (edge in actualEdges) {
            if (!edge.isAllowed) {
                val fromId = sanitizeId(edge.from)
                val toId = sanitizeId(edge.to)
                sb.appendLine("    $fromId ==>|\"VIOLATION (${edge.fileCount} files)\"| $toId")
            }
        }

        // Styling
        sb.appendLine()
        sb.appendLine("    classDef violation fill:#ff6b6b,stroke:#c0392b,color:white")
        for (edge in actualEdges.filter { !it.isAllowed }) {
            sb.appendLine("    class ${sanitizeId(edge.from)} violation")
        }

        return sb.toString()
    }

    /**
     * Generate ASCII art dependency diagram for terminal/tool window.
     */
    fun generateAscii(pattern: PatternSpec, actualEdges: List<LayerEdge>): String {
        val sb = StringBuilder()
        val maxNameLen = pattern.layers.maxOfOrNull { it.name.length } ?: 10
        val violationEdges = actualEdges.filter { !it.isAllowed }

        sb.appendLine("Architecture Dependency Graph: ${pattern.name}")
        sb.appendLine("=" .repeat(50))
        sb.appendLine()

        // Draw layers as boxes with connections
        sb.appendLine("Expected (allowed) dependencies:")
        sb.appendLine()

        for (layer in pattern.layers) {
            val box = "[${layer.name}]"
            val deps = layer.allowedDependencies
            if (deps.isEmpty()) {
                sb.appendLine("  $box  (innermost - no dependencies)")
            } else {
                sb.appendLine("  $box --> ${deps.joinToString(", ") { "[$it]" }}")
            }
        }

        if (violationEdges.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("VIOLATIONS (illegal dependencies):")
            sb.appendLine()
            for (edge in violationEdges) {
                sb.appendLine("  [${edge.from}] --X--> [${edge.to}]  (${edge.fileCount} file(s))")
            }
        }

        // Actual dependency matrix
        sb.appendLine()
        sb.appendLine("Dependency Matrix (actual imports between layers):")
        sb.appendLine()

        val layerNames = pattern.layers.map { it.name }
        val colWidth = maxNameLen + 2

        // Header
        sb.append(" ".repeat(colWidth))
        for (name in layerNames) {
            sb.append(name.padEnd(colWidth))
        }
        sb.appendLine()
        sb.appendLine("-".repeat(colWidth * (layerNames.size + 1)))

        // Rows
        for (fromLayer in layerNames) {
            sb.append(fromLayer.padEnd(colWidth))
            for (toLayer in layerNames) {
                val edge = actualEdges.find { it.from == fromLayer && it.to == toLayer }
                val cell = if (edge != null) {
                    val marker = if (edge.isAllowed) "${edge.fileCount}" else "${edge.fileCount}!"
                    marker
                } else if (fromLayer == toLayer) {
                    "-"
                } else {
                    "."
                }
                sb.append(cell.padEnd(colWidth))
            }
            sb.appendLine()
        }

        sb.appendLine()
        sb.appendLine("Legend: N = N files with this dependency, N! = VIOLATION, . = none, - = self")

        return sb.toString()
    }

    /**
     * Generate DOT format for Graphviz.
     */
    fun generateDot(pattern: PatternSpec, actualEdges: List<LayerEdge>): String {
        val sb = StringBuilder()
        sb.appendLine("digraph ArchitectureDependencies {")
        sb.appendLine("    rankdir=TB;")
        sb.appendLine("    node [shape=box, style=rounded, fontname=\"Helvetica\"];")
        sb.appendLine()

        for (layer in pattern.layers) {
            val id = sanitizeId(layer.name)
            sb.appendLine("    $id [label=\"${layer.name}\"];")
        }

        sb.appendLine()

        // Allowed edges
        for (layer in pattern.layers) {
            for (dep in layer.allowedDependencies) {
                sb.appendLine("    ${sanitizeId(layer.name)} -> ${sanitizeId(dep)} [color=green, style=dashed, label=\"allowed\"];")
            }
        }

        // Violation edges
        for (edge in actualEdges.filter { !it.isAllowed }) {
            sb.appendLine("    ${sanitizeId(edge.from)} -> ${sanitizeId(edge.to)} [color=red, penwidth=2, label=\"VIOLATION\\n${edge.fileCount} files\"];")
        }

        sb.appendLine("}")
        return sb.toString()
    }

    private fun identifyLayerByImport(importPath: String, layers: List<Layer>): Layer? {
        val importLower = importPath.lowercase()
        val importSegments = importLower.replace(".", "/").split("/").toSet()
        return layers.firstOrNull { layer ->
            layer.filePatterns.any { pattern ->
                val segments = pattern.replace("**/", "").replace("/**", "").replace("*", "")
                    .split("/").filter { it.isNotEmpty() }
                segments.any { segment -> segment.lowercase() in importSegments }
            }
        }
    }

    private fun sanitizeId(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9]"), "_")
    }
}
