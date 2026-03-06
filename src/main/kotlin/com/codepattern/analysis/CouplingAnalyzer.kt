package com.codepattern.analysis

import com.codepattern.scanner.ScannedFile
import com.codepattern.scanner.ScannedProject

/**
 * Analyzes afferent (incoming) and efferent (outgoing) coupling between modules.
 *
 * Afferent coupling (Ca): number of other modules that depend on this module
 * Efferent coupling (Ce): number of other modules this module depends on
 * Instability (I): Ce / (Ca + Ce) — 0 = maximally stable, 1 = maximally unstable
 */
object CouplingAnalyzer {

    data class CouplingMetrics(
        val filePath: String,
        val afferentCoupling: Int,
        val efferentCoupling: Int,
        val instability: Double
    )

    fun analyze(project: ScannedProject): Map<String, CouplingMetrics> {
        val files = project.files

        // Build module -> file path index
        val moduleIndex = mutableMapOf<String, String>()
        for (file in files) {
            file.className?.let { moduleIndex[it] = file.relativePath }
            val shortName = file.relativePath.substringBeforeLast(".").substringAfterLast("/")
            moduleIndex[shortName] = file.relativePath
        }

        // Compute efferent coupling (how many other project files each file imports)
        val efferent = mutableMapOf<String, MutableSet<String>>()
        for (file in files) {
            val deps = mutableSetOf<String>()
            for (imp in file.imports) {
                val resolved = resolveToProjectFile(imp, moduleIndex)
                if (resolved != null && resolved != file.relativePath) {
                    deps.add(resolved)
                }
            }
            efferent[file.relativePath] = deps
        }

        // Compute afferent coupling (how many files depend on each file)
        val afferent = mutableMapOf<String, MutableSet<String>>()
        for (file in files) {
            afferent[file.relativePath] = mutableSetOf()
        }
        for ((filePath, deps) in efferent) {
            for (dep in deps) {
                afferent.getOrPut(dep) { mutableSetOf() }.add(filePath)
            }
        }

        // Build metrics
        val result = mutableMapOf<String, CouplingMetrics>()
        for (file in files) {
            val ca = afferent[file.relativePath]?.size ?: 0
            val ce = efferent[file.relativePath]?.size ?: 0
            val instability = if (ca + ce > 0) ce.toDouble() / (ca + ce) else 0.0
            result[file.relativePath] = CouplingMetrics(
                filePath = file.relativePath,
                afferentCoupling = ca,
                efferentCoupling = ce,
                instability = instability
            )
        }
        return result
    }

    private fun resolveToProjectFile(importPath: String, moduleIndex: Map<String, String>): String? {
        val lastSegment = importPath.substringAfterLast(".").substringAfterLast("/")
        return moduleIndex[lastSegment]
    }
}
