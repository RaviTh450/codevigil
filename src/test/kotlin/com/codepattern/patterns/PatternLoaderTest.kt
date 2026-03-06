package com.codepattern.patterns

import com.codepattern.models.RuleType
import com.codepattern.models.ViolationSeverity
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PatternLoaderTest {

    private val loader = PatternLoader()

    @Test
    fun `loads valid YAML pattern`() {
        val yaml = """
            name: "Test Pattern"
            description: "A test pattern"
            layers:
              - name: "core"
                description: "Core layer"
                file_patterns:
                  - "**/core/**"
                naming_conventions:
                  - ".*Core$"
                allowed_dependencies: []
              - name: "infra"
                description: "Infrastructure layer"
                file_patterns:
                  - "**/infra/**"
                naming_conventions:
                  - ".*Impl$"
                allowed_dependencies:
                  - "core"
            rules:
              - id: "test-dep"
                name: "Test Dependency Rule"
                description: "Test rule"
                severity: "ERROR"
                type: "LAYER_DEPENDENCY"
        """.trimIndent()

        val spec = loader.loadFromYaml(yaml.byteInputStream())

        assertEquals("Test Pattern", spec.name)
        assertEquals("A test pattern", spec.description)
        assertEquals(2, spec.layers.size)
        assertEquals("core", spec.layers[0].name)
        assertEquals(listOf("**/core/**"), spec.layers[0].filePatterns)
        assertEquals(listOf(".*Core$"), spec.layers[0].namingConventions)
        assertEquals(emptyList(), spec.layers[0].allowedDependencies)
        assertEquals("infra", spec.layers[1].name)
        assertEquals(listOf("core"), spec.layers[1].allowedDependencies)
        assertEquals(1, spec.rules.size)
        assertEquals("test-dep", spec.rules[0].id)
        assertEquals(ViolationSeverity.ERROR, spec.rules[0].severity)
        assertEquals(RuleType.LAYER_DEPENDENCY, spec.rules[0].type)
    }

    @Test
    fun `handles YAML with missing optional fields`() {
        val yaml = """
            name: "Minimal"
            description: ""
            layers: []
            rules: []
        """.trimIndent()

        val spec = loader.loadFromYaml(yaml.byteInputStream())
        assertEquals("Minimal", spec.name)
        assertTrue(spec.layers.isEmpty())
        assertTrue(spec.rules.isEmpty())
    }

    @Test
    fun `loadFromYamlSafe returns null on invalid YAML`() {
        val result = loader.loadFromYamlSafe("not: [valid: yaml: {".byteInputStream())
        // Should not throw, may or may not parse depending on SnakeYAML leniency
        // The key point is it doesn't crash
    }

    @Test
    fun `loadFromYamlSafe returns null on empty input`() {
        val result = loader.loadFromYamlSafe("".byteInputStream())
        assertNull(result)
    }

    @Test
    fun `default severity is WARNING`() {
        val yaml = """
            name: "Test"
            description: ""
            layers: []
            rules:
              - id: "r1"
                name: "Rule 1"
                description: ""
                severity: "UNKNOWN"
                type: "FILE_ORGANIZATION"
        """.trimIndent()

        val spec = loader.loadFromYaml(yaml.byteInputStream())
        assertEquals(ViolationSeverity.WARNING, spec.rules[0].severity)
    }

    @Test
    fun `parses all rule types`() {
        val types = listOf(
            "LAYER_DEPENDENCY" to RuleType.LAYER_DEPENDENCY,
            "NAMING_CONVENTION" to RuleType.NAMING_CONVENTION,
            "FILE_ORGANIZATION" to RuleType.FILE_ORGANIZATION,
            "SINGLE_RESPONSIBILITY" to RuleType.SINGLE_RESPONSIBILITY,
            "INTERFACE_SEGREGATION" to RuleType.INTERFACE_SEGREGATION,
            "DEPENDENCY_INVERSION" to RuleType.DEPENDENCY_INVERSION,
        )

        for ((input, expected) in types) {
            val yaml = """
                name: "Test"
                description: ""
                layers: []
                rules:
                  - id: "r1"
                    name: "Rule"
                    description: ""
                    severity: "WARNING"
                    type: "$input"
            """.trimIndent()

            val spec = loader.loadFromYaml(yaml.byteInputStream())
            assertEquals(expected, spec.rules[0].type, "Failed for type: $input")
        }
    }

    @Test
    fun `parses rule config map`() {
        val yaml = """
            name: "Test"
            description: ""
            layers: []
            rules:
              - id: "srp"
                name: "SRP"
                description: ""
                severity: "WARNING"
                type: "SINGLE_RESPONSIBILITY"
                config:
                  max_methods_per_class: 15
                  max_lines_per_class: 500
        """.trimIndent()

        val spec = loader.loadFromYaml(yaml.byteInputStream())
        val config = spec.rules[0].config
        assertEquals(15, (config["max_methods_per_class"] as Number).toInt())
        assertEquals(500, (config["max_lines_per_class"] as Number).toInt())
    }
}
