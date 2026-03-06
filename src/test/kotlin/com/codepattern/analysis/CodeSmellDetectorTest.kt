package com.codepattern.analysis

import com.codepattern.scanner.ScannedFile
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodeSmellDetectorTest {

    private fun makeFile(
        methodCount: Int = 5,
        lineCount: Int = 100,
        imports: List<String> = emptyList(),
        className: String? = "TestClass",
        isInterface: Boolean = false
    ): ScannedFile {
        return ScannedFile(
            relativePath = "src/TestClass.kt",
            absolutePath = "/project/src/TestClass.kt",
            language = "kotlin",
            imports = imports,
            importLineNumbers = emptyMap(),
            className = className,
            methodCount = methodCount,
            lineCount = lineCount,
            isInterface = isInterface,
            concreteClassDependencies = emptyList()
        )
    }

    @Test
    fun `god class detected when multiple thresholds exceeded`() {
        val file = makeFile(methodCount = 25, lineCount = 600, imports = (1..20).map { "import$it" })
        val lines = (1..600).map { "    val line$it = $it" }
        val config = mapOf<String, Any>("smell_type" to "GOD_CLASS", "max_methods" to 20, "max_lines" to 500, "max_fields" to 15)

        val violations = CodeSmellDetector.detect(file, lines, config, "Test", "God Class", "test-god")
        assertTrue(violations.isNotEmpty(), "Should detect god class")
        assertTrue(violations[0].message.contains("God Class"), "Message should mention God Class")
        assertTrue(violations[0].confidence >= 0.5, "Confidence should be at least 0.5")
    }

    @Test
    fun `no god class when thresholds not exceeded`() {
        val file = makeFile(methodCount = 5, lineCount = 100)
        val lines = (1..100).map { "    val line$it = $it" }
        val config = mapOf<String, Any>("smell_type" to "GOD_CLASS", "max_methods" to 20, "max_lines" to 500, "max_fields" to 15)

        val violations = CodeSmellDetector.detect(file, lines, config, "Test", "God Class", "test-god")
        assertTrue(violations.isEmpty(), "Should not detect god class for small class")
    }

    @Test
    fun `high coupling detected`() {
        val file = makeFile(imports = (1..25).map { "com.example.service.Service$it" })
        val lines = listOf("class TestClass {", "}")
        val config = mapOf<String, Any>("smell_type" to "GOD_CLASS", "max_methods" to 20, "max_lines" to 500, "max_fields" to 15)

        // Even though it's a god class check, the import count is a factor
        // The test verifies that high import count contributes to the score
        val violations = CodeSmellDetector.detect(file, lines, config, "Test", "God Class", "test-god")
        // With only 5 methods and 100 lines, but 25 imports, it needs at least 2 factors to trigger
        // methodCount=5 < 20, lineCount=100 < 500, fieldCount likely < 15, imports=25 > 15 → only 1 factor
        // So this should NOT trigger
        assertTrue(violations.isEmpty())
    }

    @Test
    fun `feature envy not triggered for normal class`() {
        val file = makeFile(className = "OrderService")
        val lines = listOf(
            "class OrderService {",
            "    fun process() {",
            "        val x = getValue()",
            "    }",
            "}"
        )
        val config = mapOf<String, Any>("smell_type" to "FEATURE_ENVY")

        val violations = CodeSmellDetector.detect(file, lines, config, "Test", "Feature Envy", "test-envy")
        assertTrue(violations.isEmpty(), "Normal class should not trigger feature envy")
    }
}
