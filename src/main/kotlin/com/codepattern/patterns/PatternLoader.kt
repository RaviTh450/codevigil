package com.codepattern.patterns

import com.codepattern.models.*
import com.intellij.openapi.diagnostic.Logger
import org.yaml.snakeyaml.Yaml
import java.io.InputStream

class PatternLoader {

    private val yaml = Yaml()
    private val log = Logger.getInstance(PatternLoader::class.java)

    fun loadBuiltInPatterns(): List<PatternSpec> {
        val patternFiles = listOf(
            "mvc.yml", "clean-architecture.yml", "repository.yml", "solid.yml", "ddd.yml",
            "hexagonal.yml", "cqrs.yml", "microservices.yml", "layered.yml",
            "observer.yml", "factory.yml", "code-quality.yml"
        )
        return patternFiles.mapNotNull { fileName ->
            try {
                val stream = javaClass.classLoader.getResourceAsStream("patterns/$fileName")
                stream?.let { loadFromYaml(it) }
            } catch (e: Exception) {
                log.warn("Failed to load built-in pattern '$fileName': ${e.message}", e)
                null
            }
        }
    }

    fun loadFromYaml(input: InputStream): PatternSpec {
        val data = yaml.load<Map<String, Any>>(input)
            ?: throw IllegalArgumentException("YAML file is empty or invalid")
        return parsePatternSpec(data)
    }

    fun loadFromYamlSafe(input: InputStream): PatternSpec? {
        return try {
            loadFromYaml(input)
        } catch (e: Exception) {
            log.warn("Failed to parse pattern YAML: ${e.message}", e)
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parsePatternSpec(data: Map<String, Any>): PatternSpec {
        val name = data["name"] as? String
            ?: throw IllegalArgumentException("Pattern spec missing required 'name' field")
        val description = data["description"] as? String ?: ""

        val layersData = data["layers"] as? List<Map<String, Any>> ?: emptyList()
        val layers = layersData.map { layerData ->
            Layer(
                name = layerData["name"] as? String
                    ?: throw IllegalArgumentException("Layer missing required 'name' field"),
                description = layerData["description"] as? String ?: "",
                filePatterns = (layerData["file_patterns"] as? List<String>) ?: emptyList(),
                namingConventions = (layerData["naming_conventions"] as? List<String>) ?: emptyList(),
                allowedDependencies = (layerData["allowed_dependencies"] as? List<String>) ?: emptyList()
            )
        }

        val rulesData = data["rules"] as? List<Map<String, Any>> ?: emptyList()
        val rules = rulesData.map { ruleData ->
            PatternRule(
                id = ruleData["id"] as? String ?: "",
                name = ruleData["name"] as? String ?: "",
                description = ruleData["description"] as? String ?: "",
                severity = parseSeverity(ruleData["severity"] as? String),
                type = parseRuleType(ruleData["type"] as? String),
                config = (ruleData["config"] as? Map<String, Any>) ?: emptyMap()
            )
        }

        return PatternSpec(name = name, description = description, layers = layers, rules = rules)
    }

    private fun parseSeverity(value: String?): ViolationSeverity {
        return when (value?.uppercase()) {
            "ERROR" -> ViolationSeverity.ERROR
            "INFO" -> ViolationSeverity.INFO
            else -> ViolationSeverity.WARNING
        }
    }

    private fun parseRuleType(value: String?): RuleType {
        return try {
            RuleType.valueOf(value?.uppercase() ?: "FILE_ORGANIZATION")
        } catch (_: Exception) {
            RuleType.FILE_ORGANIZATION
        }
    }
}
