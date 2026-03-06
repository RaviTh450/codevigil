package com.codepattern.analysis

import com.codepattern.scanner.ScannedFile
import org.junit.Assert.*
import org.junit.Test

class ThreadSafetyAnalyzerTest {

    private fun scannedFile(path: String = "Test.kt", lang: String = "kotlin") = ScannedFile(
        relativePath = path, absolutePath = "/test/$path", language = lang,
        imports = emptyList(), importLineNumbers = emptyMap(), className = "Test",
        methodCount = 1, lineCount = 50, isInterface = false, concreteClassDependencies = emptyList()
    )

    @Test
    fun `detects nested synchronized blocks`() {
        val lines = listOf(
            "class AccountService {",
            "    val lockA = Any()",
            "    val lockB = Any()",
            "    fun transfer() {",
            "        synchronized(lockA) {",
            "            synchronized(lockB) {",
            "                // deadlock risk!",
            "            }",
            "        }",
            "    }",
            "}"
        )
        val issues = ThreadSafetyAnalyzer.analyzeFile(scannedFile(), lines)
        assertTrue("Should detect nested locks",
            issues.any { it.type == ThreadSafetyAnalyzer.ThreadIssueType.NESTED_LOCKS })
    }

    @Test
    fun `detects mutable singleton state`() {
        val lines = listOf(
            "object AppState {",
            "    var counter = 0",
            "    fun increment() { counter++ }",
            "}"
        )
        val issues = ThreadSafetyAnalyzer.analyzeFile(scannedFile(), lines)
        assertTrue("Should detect mutable var in object",
            issues.any { it.type == ThreadSafetyAnalyzer.ThreadIssueType.MUTABLE_SINGLETON })
    }

    @Test
    fun `detects blocking in suspend function`() {
        val lines = listOf(
            "class ApiClient {",
            "    suspend fun fetchData(): String {",
            "        Thread.sleep(1000)",
            "        return \"data\"",
            "    }",
            "}"
        )
        val issues = ThreadSafetyAnalyzer.analyzeFile(scannedFile(), lines)
        assertTrue("Should detect blocking call in suspend fun",
            issues.any { it.type == ThreadSafetyAnalyzer.ThreadIssueType.COROUTINE_BLOCKING })
    }

    @Test
    fun `detects unbounded thread creation`() {
        val lines = listOf(
            "class Worker {",
            "    fun process(items: List<Item>) {",
            "        for (item in items) {",
            "            Thread { processItem(item) }.start()",
            "        }",
            "    }",
            "}"
        )
        val issues = ThreadSafetyAnalyzer.analyzeFile(scannedFile(), lines)
        assertTrue("Should detect direct Thread creation",
            issues.any { it.type == ThreadSafetyAnalyzer.ThreadIssueType.UNBOUNDED_THREADS })
    }

    @Test
    fun `converts to violations with correct category`() {
        val issues = listOf(
            ThreadSafetyAnalyzer.ThreadIssue(
                type = ThreadSafetyAnalyzer.ThreadIssueType.NESTED_LOCKS,
                filePath = "Test.kt", lineNumber = 5,
                description = "Nested locks", severity = com.codepattern.models.ViolationSeverity.ERROR,
                confidence = 0.80
            )
        )
        val violations = ThreadSafetyAnalyzer.toViolations(issues)
        assertEquals(1, violations.size)
        assertEquals(com.codepattern.models.ViolationCategory.THREAD_SAFETY, violations[0].category)
    }
}
