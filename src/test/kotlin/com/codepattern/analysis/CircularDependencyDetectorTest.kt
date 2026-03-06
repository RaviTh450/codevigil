package com.codepattern.analysis

import com.codepattern.scanner.ScannedFile
import com.codepattern.scanner.ScannedProject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CircularDependencyDetectorTest {

    private fun makeFile(
        path: String,
        className: String,
        imports: List<String> = emptyList()
    ): ScannedFile {
        return ScannedFile(
            relativePath = path,
            absolutePath = "/project/$path",
            language = "kotlin",
            imports = imports,
            importLineNumbers = emptyMap(),
            className = className,
            methodCount = 2,
            lineCount = 20,
            isInterface = false,
            concreteClassDependencies = emptyList()
        )
    }

    @Test
    fun `no cycles in acyclic graph`() {
        val files = listOf(
            makeFile("A.kt", "A", listOf("com.example.B")),
            makeFile("B.kt", "B", listOf("com.example.C")),
            makeFile("C.kt", "C", emptyList())
        )
        val project = ScannedProject(basePath = "/project", files = files)
        val cycles = CircularDependencyDetector.detectCycles(project)
        assertTrue(cycles.isEmpty(), "No cycles expected in acyclic graph")
    }

    @Test
    fun `detects simple cycle`() {
        val files = listOf(
            makeFile("A.kt", "A", listOf("com.example.B")),
            makeFile("B.kt", "B", listOf("com.example.A"))
        )
        val project = ScannedProject(basePath = "/project", files = files)
        val cycles = CircularDependencyDetector.detectCycles(project)
        assertTrue(cycles.isNotEmpty(), "Should detect A -> B -> A cycle")
    }

    @Test
    fun `detects three-node cycle`() {
        val files = listOf(
            makeFile("A.kt", "A", listOf("com.example.B")),
            makeFile("B.kt", "B", listOf("com.example.C")),
            makeFile("C.kt", "C", listOf("com.example.A"))
        )
        val project = ScannedProject(basePath = "/project", files = files)
        val cycles = CircularDependencyDetector.detectCycles(project)
        assertTrue(cycles.isNotEmpty(), "Should detect A -> B -> C -> A cycle")
    }

    @Test
    fun `converts cycles to violations`() {
        val files = listOf(
            makeFile("A.kt", "A", listOf("com.example.B")),
            makeFile("B.kt", "B", listOf("com.example.A"))
        )
        val project = ScannedProject(basePath = "/project", files = files)
        val cycles = CircularDependencyDetector.detectCycles(project)
        val violations = CircularDependencyDetector.toViolations(
            cycles, "Test", "Circular Dep", "circ-dep",
            com.codepattern.models.ViolationSeverity.WARNING
        )
        assertTrue(violations.isNotEmpty(), "Should produce violations from cycles")
        assertTrue(violations[0].message.contains("Circular dependency"), "Message should describe circular dependency")
    }

    @Test
    fun `no false positives for external imports`() {
        val files = listOf(
            makeFile("A.kt", "A", listOf("java.util.List", "kotlin.collections.Map")),
            makeFile("B.kt", "B", listOf("org.springframework.stereotype.Service"))
        )
        val project = ScannedProject(basePath = "/project", files = files)
        val cycles = CircularDependencyDetector.detectCycles(project)
        assertTrue(cycles.isEmpty(), "External imports should not create false cycles")
    }
}
