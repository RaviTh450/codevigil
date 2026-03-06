package com.codepattern.patterns

import com.codepattern.models.*
import com.codepattern.scanner.ScannedFile
import com.codepattern.scanner.ScannedProject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PatternMatcherTest {

    private val matcher = PatternMatcher()

    private fun makePattern(layers: List<Layer>, rules: List<PatternRule>): PatternSpec {
        return PatternSpec(name = "Test", description = "Test pattern", layers = layers, rules = rules)
    }

    private fun makeFile(
        relativePath: String,
        imports: List<String> = emptyList(),
        importLineNumbers: Map<String, Int> = emptyMap(),
        methodCount: Int = 0,
        lineCount: Int = 10,
        isInterface: Boolean = false,
        concreteClassDependencies: List<String> = emptyList()
    ): ScannedFile {
        return ScannedFile(
            relativePath = relativePath,
            absolutePath = "/project/$relativePath",
            language = "java",
            imports = imports,
            importLineNumbers = importLineNumbers,
            className = relativePath.substringAfterLast("/").substringBeforeLast("."),
            methodCount = methodCount,
            lineCount = lineCount,
            isInterface = isInterface,
            concreteClassDependencies = concreteClassDependencies
        )
    }

    // ---- Layer Dependency ----

    @Test
    fun `detects layer dependency violation`() {
        val layers = listOf(
            Layer("model", "Model", listOf("**/model/**"), emptyList(), emptyList()),
            Layer("controller", "Controller", listOf("**/controller/**"), emptyList(), listOf("model"))
        )
        val rule = PatternRule("dep", "Dep Rule", "", ViolationSeverity.ERROR, RuleType.LAYER_DEPENDENCY)
        val pattern = makePattern(layers, listOf(rule))

        // Model file importing from controller = violation
        val file = makeFile(
            "src/model/UserModel.java",
            imports = listOf("com.example.controller.UserController"),
            importLineNumbers = mapOf("com.example.controller.UserController" to 3)
        )
        val project = ScannedProject("/project", listOf(file))

        val violations = matcher.analyze(project, pattern)
        assertTrue(violations.isNotEmpty())
        assertEquals(ViolationSeverity.ERROR, violations[0].severity)
        assertTrue(violations[0].message.contains("should not depend on"))
    }

    @Test
    fun `allows valid layer dependencies`() {
        val layers = listOf(
            Layer("model", "Model", listOf("**/model/**"), emptyList(), emptyList()),
            Layer("controller", "Controller", listOf("**/controller/**"), emptyList(), listOf("model"))
        )
        val rule = PatternRule("dep", "Dep Rule", "", ViolationSeverity.ERROR, RuleType.LAYER_DEPENDENCY)
        val pattern = makePattern(layers, listOf(rule))

        // Controller importing model = allowed
        val file = makeFile(
            "src/controller/UserController.java",
            imports = listOf("com.example.model.User"),
            importLineNumbers = mapOf("com.example.model.User" to 3)
        )
        val project = ScannedProject("/project", listOf(file))

        val violations = matcher.analyze(project, pattern)
        assertTrue(violations.isEmpty())
    }

    // ---- Naming Convention ----

    @Test
    fun `detects naming convention violation`() {
        val layers = listOf(
            Layer("controller", "Controller", listOf("**/controller/**"), listOf(".*Controller$"), emptyList())
        )
        val rule = PatternRule("naming", "Naming", "", ViolationSeverity.WARNING, RuleType.NAMING_CONVENTION)
        val pattern = makePattern(layers, listOf(rule))

        // File in controller directory but not ending with Controller
        val file = makeFile("src/controller/UserHandler.java")
        val project = ScannedProject("/project", listOf(file))

        val violations = matcher.analyze(project, pattern)
        assertTrue(violations.isNotEmpty())
        assertTrue(violations[0].message.contains("does not match naming convention"))
    }

    @Test
    fun `passes naming convention check`() {
        val layers = listOf(
            Layer("controller", "Controller", listOf("**/controller/**"), listOf(".*Controller$"), emptyList())
        )
        val rule = PatternRule("naming", "Naming", "", ViolationSeverity.WARNING, RuleType.NAMING_CONVENTION)
        val pattern = makePattern(layers, listOf(rule))

        val file = makeFile("src/controller/UserController.java")
        val project = ScannedProject("/project", listOf(file))

        val violations = matcher.analyze(project, pattern)
        assertTrue(violations.isEmpty())
    }

    // ---- Single Responsibility ----

    @Test
    fun `detects SRP violation for too many methods`() {
        val rule = PatternRule(
            "srp", "SRP", "", ViolationSeverity.WARNING, RuleType.SINGLE_RESPONSIBILITY,
            config = mapOf("max_methods_per_class" to 5, "max_lines_per_class" to 300)
        )
        val pattern = makePattern(emptyList(), listOf(rule))

        val file = makeFile("src/BigClass.java", methodCount = 12, lineCount = 50)
        val project = ScannedProject("/project", listOf(file))

        val violations = matcher.analyze(project, pattern)
        assertTrue(violations.any { it.message.contains("12 methods") })
    }

    @Test
    fun `detects SRP violation for too many lines`() {
        val rule = PatternRule(
            "srp", "SRP", "", ViolationSeverity.WARNING, RuleType.SINGLE_RESPONSIBILITY,
            config = mapOf("max_methods_per_class" to 50, "max_lines_per_class" to 100)
        )
        val pattern = makePattern(emptyList(), listOf(rule))

        val file = makeFile("src/HugeFile.java", methodCount = 3, lineCount = 500)
        val project = ScannedProject("/project", listOf(file))

        val violations = matcher.analyze(project, pattern)
        assertTrue(violations.any { it.message.contains("500 lines") })
    }

    // ---- Interface Segregation ----

    @Test
    fun `detects ISP violation for fat interface`() {
        val rule = PatternRule(
            "isp", "ISP", "", ViolationSeverity.WARNING, RuleType.INTERFACE_SEGREGATION,
            config = mapOf("max_interface_methods" to 3)
        )
        val pattern = makePattern(emptyList(), listOf(rule))

        val file = makeFile("src/BigInterface.java", methodCount = 10, isInterface = true)
        val project = ScannedProject("/project", listOf(file))

        val violations = matcher.analyze(project, pattern)
        assertTrue(violations.isNotEmpty())
        assertTrue(violations[0].message.contains("10 methods"))
    }

    @Test
    fun `passes ISP check for small interface`() {
        val rule = PatternRule(
            "isp", "ISP", "", ViolationSeverity.WARNING, RuleType.INTERFACE_SEGREGATION,
            config = mapOf("max_interface_methods" to 5)
        )
        val pattern = makePattern(emptyList(), listOf(rule))

        val file = makeFile("src/SmallInterface.java", methodCount = 3, isInterface = true)
        val project = ScannedProject("/project", listOf(file))

        val violations = matcher.analyze(project, pattern)
        assertTrue(violations.isEmpty())
    }

    @Test
    fun `ISP check ignores non-interfaces`() {
        val rule = PatternRule(
            "isp", "ISP", "", ViolationSeverity.WARNING, RuleType.INTERFACE_SEGREGATION,
            config = mapOf("max_interface_methods" to 3)
        )
        val pattern = makePattern(emptyList(), listOf(rule))

        val file = makeFile("src/BigClass.java", methodCount = 20, isInterface = false)
        val project = ScannedProject("/project", listOf(file))

        val violations = matcher.analyze(project, pattern)
        assertTrue(violations.isEmpty())
    }

    // ---- Dependency Inversion ----

    @Test
    fun `detects DIP violation for concrete dependencies`() {
        val rule = PatternRule("dip", "DIP", "", ViolationSeverity.INFO, RuleType.DEPENDENCY_INVERSION)
        val pattern = makePattern(emptyList(), listOf(rule))

        val file = makeFile(
            "src/Service.java",
            concreteClassDependencies = listOf("MySqlRepository", "HttpClient")
        )
        val project = ScannedProject("/project", listOf(file))

        val violations = matcher.analyze(project, pattern)
        assertEquals(2, violations.size)
        assertTrue(violations.any { it.message.contains("MySqlRepository") })
        assertTrue(violations.any { it.message.contains("HttpClient") })
    }

    // ---- File classification ----

    @Test
    fun `classifies files into correct layers`() {
        val layers = listOf(
            Layer("model", "Model", listOf("**/model/**"), emptyList(), emptyList()),
            Layer("controller", "Controller", listOf("**/controller/**"), emptyList(), emptyList())
        )
        val pattern = makePattern(layers, emptyList())

        val files = listOf(
            makeFile("src/model/User.java"),
            makeFile("src/model/Order.java"),
            makeFile("src/controller/UserController.java"),
            makeFile("src/util/Helper.java")
        )
        val project = ScannedProject("/project", files)

        val classified = matcher.classifyFiles(project, pattern)
        assertEquals(2, classified["model"]?.size)
        assertEquals(1, classified["controller"]?.size)
        assertEquals(1, classified["Unassigned"]?.size)
    }

    // ---- Single file analysis ----

    @Test
    fun `analyzeFile works for single file`() {
        val layers = listOf(
            Layer("model", "Model", listOf("**/model/**"), listOf(".*Model$"), emptyList()),
        )
        val rule = PatternRule("naming", "Naming", "", ViolationSeverity.WARNING, RuleType.NAMING_CONVENTION)
        val pattern = makePattern(layers, listOf(rule))

        val file = makeFile("src/model/BadName.java")
        val violations = matcher.analyzeFile(file, pattern)
        assertTrue(violations.isNotEmpty())
        assertTrue(violations[0].message.contains("does not match naming convention"))
    }
}
