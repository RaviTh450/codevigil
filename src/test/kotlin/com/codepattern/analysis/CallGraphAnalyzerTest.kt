package com.codepattern.analysis

import com.codepattern.scanner.ScannedFile
import com.codepattern.scanner.ScannedProject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class CallGraphAnalyzerTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private fun createProject(files: Map<String, String>): Pair<ScannedProject, String> {
        val projectDir = tempDir.newFolder("project")
        val scannedFiles = mutableListOf<ScannedFile>()

        for ((name, content) in files) {
            val file = java.io.File(projectDir, name)
            file.parentFile.mkdirs()
            file.writeText(content)

            val lang = when (file.extension) {
                "kt" -> "kotlin"
                "java" -> "java"
                "py" -> "python"
                else -> "unknown"
            }

            scannedFiles += ScannedFile(
                relativePath = name,
                absolutePath = file.absolutePath,
                language = lang,
                imports = emptyList(),
                importLineNumbers = emptyMap(),
                className = name.substringBeforeLast("."),
                methodCount = 3,
                lineCount = content.lines().size,
                isInterface = false,
                concreteClassDependencies = emptyList()
            )
        }

        return Pair(
            ScannedProject(basePath = projectDir.absolutePath, files = scannedFiles),
            projectDir.absolutePath
        )
    }

    @Test
    fun `builds call graph and finds functions`() {
        val (project, basePath) = createProject(mapOf(
            "Service.kt" to """
                class Service {
                    fun handleRequest() {
                        validate()
                        process()
                    }
                    fun validate() {}
                    fun process() {
                        saveToDb()
                    }
                    fun saveToDb() {}
                }
            """.trimIndent()
        ))

        val graph = CallGraphAnalyzer.buildCallGraph(project, basePath)
        assertTrue("Should find functions", graph.nodes.isNotEmpty())
        assertTrue("Should find at least 3 functions", graph.nodes.size >= 3)
    }

    @Test
    fun `detects call edges`() {
        val (project, basePath) = createProject(mapOf(
            "App.kt" to """
                class App {
                    fun main() {
                        init()
                        run()
                    }
                    fun init() {}
                    fun run() {
                        process()
                    }
                    fun process() {}
                }
            """.trimIndent()
        ))

        val graph = CallGraphAnalyzer.buildCallGraph(project, basePath)
        assertTrue("Should have call edges", graph.edges.isNotEmpty())
    }

    @Test
    fun `finds longest path`() {
        val (project, basePath) = createProject(mapOf(
            "Chain.kt" to """
                class Chain {
                    fun a() { b() }
                    fun b() { c() }
                    fun c() { d() }
                    fun d() { e() }
                    fun e() {}
                }
            """.trimIndent()
        ))

        val graph = CallGraphAnalyzer.buildCallGraph(project, basePath)
        val stats = CallGraphAnalyzer.analyzeGraph(graph)
        assertTrue("Longest path should be at least 3", stats.longestPathLength >= 3)
    }

    @Test
    fun `generates ASCII report`() {
        val (project, basePath) = createProject(mapOf(
            "Simple.kt" to """
                class Simple {
                    fun start() { doWork() }
                    fun doWork() { finish() }
                    fun finish() {}
                }
            """.trimIndent()
        ))

        val graph = CallGraphAnalyzer.buildCallGraph(project, basePath)
        val stats = CallGraphAnalyzer.analyzeGraph(graph)
        val ascii = CallGraphAnalyzer.generateAscii(graph, stats)
        assertTrue("Should contain header", ascii.contains("Function Call Graph Analysis"))
        assertTrue("Should contain longest path", ascii.contains("Longest Execution Path"))
    }

    @Test
    fun `generates Mermaid output`() {
        val (project, basePath) = createProject(mapOf(
            "Flow.kt" to """
                class Flow {
                    fun step1() { step2() }
                    fun step2() { step3() }
                    fun step3() {}
                }
            """.trimIndent()
        ))

        val graph = CallGraphAnalyzer.buildCallGraph(project, basePath)
        val stats = CallGraphAnalyzer.analyzeGraph(graph)
        val mermaid = CallGraphAnalyzer.generateMermaid(graph, stats)
        assertTrue("Should be valid mermaid", mermaid.startsWith("graph TD"))
    }
}
