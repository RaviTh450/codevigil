package com.codepattern.analysis

import com.codepattern.models.ViolationCategory
import com.codepattern.models.ViolationSeverity
import com.codepattern.scanner.ScannedFile
import org.junit.Assert.*
import org.junit.Test

class DeadCodeDetectorTest {

    private fun scannedFile(path: String = "Test.kt", lang: String = "kotlin") = ScannedFile(
        relativePath = path, absolutePath = "/test/$path", language = lang,
        imports = emptyList(), importLineNumbers = emptyMap(), className = "Test",
        methodCount = 1, lineCount = 50, isInterface = false, concreteClassDependencies = emptyList()
    )

    @Test
    fun `detects unused imports`() {
        val lines = listOf(
            "import java.util.ArrayList",
            "import java.io.File",
            "",
            "class Test {",
            "    fun doSomething(): File {",
            "        return File(\"/tmp\")",
            "    }",
            "}"
        )
        val issues = DeadCodeDetector.analyzeFile(scannedFile(), lines)
        val unusedImports = issues.filter { it.type == DeadCodeType.UNUSED_IMPORT }
        assertTrue("Should detect unused ArrayList import", unusedImports.any { it.name.contains("ArrayList") })
    }

    @Test
    fun `detects empty catch blocks`() {
        val lines = listOf(
            "fun riskyOp() {",
            "    try {",
            "        doSomething()",
            "    } catch (e: Exception) {",
            "    }",
            "}"
        )
        val issues = DeadCodeDetector.analyzeFile(scannedFile(), lines)
        assertTrue("Should detect empty catch", issues.any { it.type == DeadCodeType.EMPTY_CATCH })
    }

    @Test
    fun `detects unreachable code after return`() {
        val lines = listOf(
            "fun calc(): Int {",
            "    return 42",
            "    val x = 10",
            "}"
        )
        val issues = DeadCodeDetector.analyzeFile(scannedFile(), lines)
        assertTrue("Should detect unreachable code", issues.any { it.type == DeadCodeType.UNREACHABLE_CODE })
    }

    @Test
    fun `detects commented out code`() {
        val lines = listOf(
            "fun active() {",
            "    println(\"active\")",
            "//    val x = 10",
            "//    if (x > 5) {",
            "//        return x",
            "//    }",
            "}"
        )
        val issues = DeadCodeDetector.analyzeFile(scannedFile(), lines)
        assertTrue("Should detect commented code", issues.any { it.type == DeadCodeType.COMMENTED_CODE })
    }

    @Test
    fun `detects dead conditionals`() {
        val lines = listOf(
            "fun test() {",
            "    if (false) {",
            "        println(\"never\")",
            "    }",
            "}"
        )
        val issues = DeadCodeDetector.analyzeFile(scannedFile(), lines)
        assertTrue("Should detect dead conditional", issues.any { it.type == DeadCodeType.DEAD_CONDITIONAL })
    }

    @Test
    fun `toViolations works`() {
        val issues = listOf(
            DeadCodeIssue(
                type = DeadCodeType.UNUSED_IMPORT,
                filePath = "Test.kt", lineNumber = 1, name = "java.util.ArrayList",
                message = "Unused import", severity = ViolationSeverity.WARNING,
                confidence = 0.9
            )
        )
        val violations = DeadCodeDetector.toViolations(issues)
        assertEquals(1, violations.size)
        assertEquals(ViolationCategory.DEAD_CODE, violations[0].category)
    }
}
