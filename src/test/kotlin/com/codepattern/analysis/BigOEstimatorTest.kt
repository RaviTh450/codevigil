package com.codepattern.analysis

import com.codepattern.scanner.ScannedFile
import org.junit.Assert.*
import org.junit.Test

class BigOEstimatorTest {

    private fun scannedFile(path: String = "Test.java", lang: String = "java") = ScannedFile(
        relativePath = path, absolutePath = "/test/$path", language = lang,
        imports = emptyList(), importLineNumbers = emptyMap(), className = "Test",
        methodCount = 1, lineCount = 50, isInterface = false, concreteClassDependencies = emptyList()
    )

    @Test
    fun `detects O(1) for simple method`() {
        // Use Kotlin syntax for reliable method detection
        val lines = listOf(
            "fun getValue(): Int {",
            "    return 42",
            "}"
        )
        val results = BigOEstimator.analyzeFile(scannedFile("Test.kt", "kotlin"), lines)
        val method = results.find { it.name == "getValue" }
        assertNotNull("Should find method", method)
        assertEquals("O(1)", method!!.timeComplexity)
        assertEquals("O(1)", method.spaceComplexity)
    }

    @Test
    fun `detects O(n) for single loop`() {
        val lines = listOf(
            "fun sum(arr: IntArray): Int {",
            "    var total = 0",
            "    for (i in arr.indices) {",
            "        total += arr[i]",
            "    }",
            "    return total",
            "}"
        )
        val results = BigOEstimator.analyzeFile(scannedFile("Test.kt", "kotlin"), lines)
        val method = results.find { it.name == "sum" }
        assertNotNull("Should find method", method)
        assertEquals("O(n)", method!!.timeComplexity)
    }

    @Test
    fun `detects O(n^2) for nested loops`() {
        val lines = listOf(
            "fun bubbleSort(arr: IntArray) {",
            "    for (i in arr.indices) {",
            "        for (j in 0 until arr.size - 1) {",
            "            if (arr[j] > arr[j+1]) {",
            "                val temp = arr[j]",
            "                arr[j] = arr[j+1]",
            "                arr[j+1] = temp",
            "            }",
            "        }",
            "    }",
            "}"
        )
        val results = BigOEstimator.analyzeFile(scannedFile("Test.kt", "kotlin"), lines)
        val method = results.find { it.name == "bubbleSort" }
        assertNotNull("Should find method", method)
        assertTrue("Should be at least O(n²)", method!!.timeComplexity.contains("n²"))
    }

    @Test
    fun `detects recursion`() {
        val lines = listOf(
            "public class Test {",
            "    public int fibonacci(int n) {",
            "        if (n <= 1) return n;",
            "        return fibonacci(n - 1) + fibonacci(n - 2);",
            "    }",
            "}"
        )
        val results = BigOEstimator.analyzeFile(scannedFile(), lines)
        val method = results.find { it.name == "fibonacci" }
        assertNotNull("Should find method", method)
        assertTrue("Should detect recursion", method!!.hasRecursion)
        assertTrue("Should be exponential", method.timeComplexity.contains("2^n"))
    }

    @Test
    fun `detects sort complexity`() {
        val lines = listOf(
            "fun processData(items: List<Int>): List<Int> {",
            "    return items.sorted()",
            "}"
        )
        val results = BigOEstimator.analyzeFile(scannedFile("Test.kt", "kotlin"), lines)
        val method = results.find { it.name == "processData" }
        assertNotNull("Should find method", method)
        assertTrue("Should detect O(n log n) from sort",
            method!!.timeComplexity.contains("n log n"))
    }

    @Test
    fun `generates report`() {
        val complexities = listOf(
            BigOEstimator.FunctionComplexity(
                name = "test", filePath = "Test.java", lineNumber = 1,
                timeComplexity = "O(n²)", spaceComplexity = "O(n)",
                timeReason = "Nested loops", spaceReason = "Creates list",
                loopDepth = 2, hasRecursion = false,
                internalCalls = emptyList(), aggregateTimeComplexity = "O(n²)"
            )
        )
        val report = BigOEstimator.generateReport(complexities)
        assertTrue(report.contains("Big-O Complexity Report"))
        assertTrue(report.contains("O(n²)"))
    }

    @Test
    fun `complexity rank ordering is correct`() {
        assertTrue(BigOEstimator.complexityRank("O(1)") < BigOEstimator.complexityRank("O(n)"))
        assertTrue(BigOEstimator.complexityRank("O(n)") < BigOEstimator.complexityRank("O(n²)"))
        assertTrue(BigOEstimator.complexityRank("O(n²)") < BigOEstimator.complexityRank("O(2^n)"))
        assertTrue(BigOEstimator.complexityRank("O(log n)") < BigOEstimator.complexityRank("O(n)"))
        assertTrue(BigOEstimator.complexityRank("O(n log n)") < BigOEstimator.complexityRank("O(n²)"))
    }
}
