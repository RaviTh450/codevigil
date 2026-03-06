package com.codepattern.analysis

import com.codepattern.models.ViolationCategory
import com.codepattern.scanner.ScannedFile
import org.junit.Assert.*
import org.junit.Test

class DuplicateCodeDetectorTest {

    private fun scannedFile(path: String = "Test.kt", lang: String = "kotlin") = ScannedFile(
        relativePath = path, absolutePath = "/test/$path", language = lang,
        imports = emptyList(), importLineNumbers = emptyMap(), className = "Test",
        methodCount = 1, lineCount = 50, isInterface = false, concreteClassDependencies = emptyList()
    )

    @Test
    fun `detects identical code blocks`() {
        val file1 = scannedFile("A.kt")
        val file2 = scannedFile("B.kt")
        val sharedCode = listOf(
            "fun processData(input: List<Int>): List<Int> {",
            "    val result = mutableListOf<Int>()",
            "    for (item in input) {",
            "        if (item > 0) {",
            "            result.add(item * 2)",
            "        }",
            "    }",
            "    return result",
            "}"
        )
        val files = listOf(
            Pair(file1, sharedCode + listOf("", "fun unique1() { }")),
            Pair(file2, listOf("fun unique2() { }", "") + sharedCode)
        )
        val report = DuplicateCodeDetector.analyzeFiles(files)
        assertTrue("Should find duplicates", report.totalDuplicateBlocks > 0)
    }

    @Test
    fun `no duplicates for unique code`() {
        val file1 = scannedFile("A.kt")
        val file2 = scannedFile("B.kt")
        val files = listOf(
            Pair(file1, listOf("fun foo() { println(1) }", "fun bar() { println(2) }")),
            Pair(file2, listOf("fun baz() { println(3) }", "fun qux() { println(4) }"))
        )
        val report = DuplicateCodeDetector.analyzeFiles(files)
        assertEquals("Should find no duplicates", 0, report.totalDuplicateBlocks)
    }

    @Test
    fun `generates report`() {
        val report = DuplicateReport(
            totalDuplicateBlocks = 2,
            totalDuplicateLines = 14,
            duplicationPercentage = 12.5,
            blocks = listOf(
                DuplicateBlock(
                    file1Path = "A.kt", file1StartLine = 1, file1EndLine = 7,
                    file2Path = "B.kt", file2StartLine = 3, file2EndLine = 9,
                    lineCount = 7, similarity = 1.0
                )
            )
        )
        val text = DuplicateCodeDetector.generateReport(report)
        assertTrue(text.contains("Duplicate"))
        assertTrue(text.contains("A.kt"))
    }

    @Test
    fun `toViolations converts`() {
        val report = DuplicateReport(
            totalDuplicateBlocks = 1,
            totalDuplicateLines = 10,
            duplicationPercentage = 5.0,
            blocks = listOf(
                DuplicateBlock(
                    file1Path = "A.kt", file1StartLine = 1, file1EndLine = 10,
                    file2Path = "B.kt", file2StartLine = 5, file2EndLine = 14,
                    lineCount = 10, similarity = 0.95
                )
            )
        )
        val violations = DuplicateCodeDetector.toViolations(report)
        assertTrue("Should have violations", violations.isNotEmpty())
        assertEquals(ViolationCategory.DUPLICATION, violations[0].category)
    }
}
