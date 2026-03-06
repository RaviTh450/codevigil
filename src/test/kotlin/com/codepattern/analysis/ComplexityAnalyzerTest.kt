package com.codepattern.analysis

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ComplexityAnalyzerTest {

    @Test
    fun `simple method has cyclomatic complexity 1`() {
        val lines = listOf(
            "fun greet(name: String): String {",
            "    return \"Hello, \$name\"",
            "}"
        )
        val methods = ComplexityAnalyzer.extractMethods(lines, "kotlin")
        assertTrue(methods.isNotEmpty(), "Should detect at least one method")
        assertEquals(1, methods[0].cyclomaticComplexity)
    }

    @Test
    fun `if-else adds cyclomatic complexity`() {
        val lines = listOf(
            "fun check(x: Int): String {",
            "    if (x > 0) {",
            "        return \"positive\"",
            "    } else if (x < 0) {",
            "        return \"negative\"",
            "    } else {",
            "        return \"zero\"",
            "    }",
            "}"
        )
        val complexity = ComplexityAnalyzer.computeFileCyclomaticComplexity(lines)
        // Base 1 + if + else if + else = at least 3
        assertTrue(complexity >= 3, "Expected complexity >= 3, got $complexity")
    }

    @Test
    fun `for loop adds complexity`() {
        val lines = listOf(
            "fun sum(list: List<Int>): Int {",
            "    var total = 0",
            "    for (item in list) {",
            "        total += item",
            "    }",
            "    return total",
            "}"
        )
        val complexity = ComplexityAnalyzer.computeFileCyclomaticComplexity(lines)
        assertTrue(complexity >= 2, "Expected complexity >= 2, got $complexity")
    }

    @Test
    fun `logical operators add complexity`() {
        val lines = listOf(
            "fun validate(a: Boolean, b: Boolean): Boolean {",
            "    return a && b || !a",
            "}"
        )
        val complexity = ComplexityAnalyzer.computeFileCyclomaticComplexity(lines)
        // Base 1 + && + || = 3
        assertTrue(complexity >= 3, "Expected complexity >= 3, got $complexity")
    }

    @Test
    fun `extracts method info correctly`() {
        val lines = listOf(
            "fun process(input: String, count: Int, flag: Boolean): List<String> {",
            "    if (flag) {",
            "        return listOf(input)",
            "    }",
            "    return emptyList()",
            "}"
        )
        val methods = ComplexityAnalyzer.extractMethods(lines, "kotlin")
        assertTrue(methods.isNotEmpty())
        assertEquals("process", methods[0].name)
        assertEquals(3, methods[0].parameterCount)
    }

    @Test
    fun `java method detection`() {
        val lines = listOf(
            "public String getName(int id, String prefix) {",
            "    if (id > 0) {",
            "        return prefix + id;",
            "    }",
            "    return null;",
            "}"
        )
        val methods = ComplexityAnalyzer.extractMethods(lines, "java")
        assertTrue(methods.isNotEmpty())
        assertEquals("getName", methods[0].name)
        assertEquals(2, methods[0].parameterCount)
    }

    @Test
    fun `python method detection`() {
        val lines = listOf(
            "def calculate(self, x, y):",
            "    if x > y:",
            "        return x - y",
            "    elif x < y:",
            "        return y - x",
            "    else:",
            "        return 0"
        )
        val methods = ComplexityAnalyzer.extractMethods(lines, "python")
        assertTrue(methods.isNotEmpty())
        assertEquals("calculate", methods[0].name)
        assertEquals(2, methods[0].parameterCount)  // self excluded
    }

    @Test
    fun `cognitive complexity increases with nesting`() {
        val lines = listOf(
            "fun nested(a: Int, b: Int) {",
            "    if (a > 0) {",          // +1
            "        if (b > 0) {",      // +1 + 1 nesting = +2
            "            for (i in 1..a) {",  // +1 + 2 nesting = +3
            "                println(i)",
            "            }",
            "        }",
            "    }",
            "}"
        )
        val cognitive = ComplexityAnalyzer.computeFileCognitiveComplexity(lines)
        assertTrue(cognitive >= 4, "Expected cognitive >= 4, got $cognitive")
    }

    @Test
    fun `max nesting depth detects deep nesting`() {
        val lines = listOf(
            "fun deep() {",
            "    if (true) {",
            "        for (i in 1..10) {",
            "            while (true) {",
            "                break",
            "            }",
            "        }",
            "    }",
            "}"
        )
        val depth = ComplexityAnalyzer.computeMaxNestingDepth(lines)
        assertTrue(depth >= 3, "Expected depth >= 3, got $depth")
    }

    @Test
    fun `zero parameter method`() {
        val lines = listOf(
            "fun doSomething() {",
            "    println(\"hello\")",
            "}"
        )
        val methods = ComplexityAnalyzer.extractMethods(lines, "kotlin")
        assertTrue(methods.isNotEmpty())
        assertEquals(0, methods[0].parameterCount)
    }

    @Test
    fun `comments and blank lines are ignored`() {
        val lines = listOf(
            "// This is a comment with if and for",
            "fun simple() {",
            "    // if this were real code",
            "    println(\"hello\")",
            "}"
        )
        val complexity = ComplexityAnalyzer.computeFileCyclomaticComplexity(lines)
        assertEquals(1, complexity, "Comments should not affect complexity")
    }
}
