package com.codepattern.analysis

import com.codepattern.models.ViolationCategory
import com.codepattern.scanner.ScannedFile
import org.junit.Assert.*
import org.junit.Test

class NamingConventionCheckerTest {

    private fun scannedFile(path: String = "Test.kt", lang: String = "kotlin") = ScannedFile(
        relativePath = path, absolutePath = "/test/$path", language = lang,
        imports = emptyList(), importLineNumbers = emptyMap(), className = "Test",
        methodCount = 1, lineCount = 50, isInterface = false, concreteClassDependencies = emptyList()
    )

    @Test
    fun `detects non-PascalCase class name`() {
        val lines = listOf("class myService {", "}")
        val issues = NamingConventionChecker.analyzeFile(scannedFile(), lines)
        assertTrue("Should detect bad class name", issues.any {
            it.kind == NamingConventionChecker.NameKind.CLASS && it.name == "myService"
        })
    }

    @Test
    fun `accepts PascalCase class name`() {
        val lines = listOf("class MyService {", "}")
        val issues = NamingConventionChecker.analyzeFile(scannedFile(), lines)
        assertTrue("Should not flag PascalCase", issues.none { it.kind == NamingConventionChecker.NameKind.CLASS })
    }

    @Test
    fun `detects snake_case in Python function as correct`() {
        val lines = listOf("def process_data(input):", "    return input")
        val issues = NamingConventionChecker.analyzeFile(scannedFile("test.py", "python"), lines)
        assertTrue("Should not flag snake_case in Python", issues.none {
            it.kind == NamingConventionChecker.NameKind.METHOD && it.name == "process_data"
        })
    }

    @Test
    fun `detects camelCase function in Python`() {
        val lines = listOf("def processData(input):", "    return input")
        val issues = NamingConventionChecker.analyzeFile(scannedFile("test.py", "python"), lines)
        assertTrue("Should flag camelCase in Python", issues.any {
            it.kind == NamingConventionChecker.NameKind.METHOD && it.name == "processData"
        })
    }

    @Test
    fun `toViolations uses NAMING category`() {
        val issues = listOf(
            NamingConventionChecker.NamingIssue(
                filePath = "Test.kt", lineNumber = 1, name = "bad_name",
                kind = NamingConventionChecker.NameKind.CLASS, expected = "PascalCase",
                message = "Bad name"
            )
        )
        val violations = NamingConventionChecker.toViolations(issues)
        assertEquals(ViolationCategory.NAMING, violations[0].category)
    }
}
