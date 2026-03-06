package com.codepattern.patterns

import com.codepattern.analysis.FitnessFunction
import com.codepattern.models.*
import com.intellij.openapi.diagnostic.Logger
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * Loads custom patterns and configuration from the project root.
 *
 * Looks for:
 *   .codevigil.yml   — Project-level pattern config
 *   .codevigil/       — Directory for custom pattern YAML files
 *
 * .codevigil.yml format:
 * ```yaml
 * # Which built-in patterns to use
 * patterns:
 *   - clean-architecture
 *   - solid
 *
 * # Custom patterns (inline or reference file)
 * custom_patterns:
 *   - file: .codevigil/my-pattern.yml
 *
 * # Architecture fitness thresholds
 * fitness:
 *   min_health_score: 70
 *   max_errors: 0
 *   max_warnings: 10
 *   max_god_classes: 0
 *   max_circular_deps: 0
 *
 * # Excluded paths (merged with defaults)
 * exclude:
 *   - generated/
 *   - proto/
 *
 * # Multi-pattern mode
 * multi_pattern: true
 * ```
 */
class CustomPatternLoader {

    private val yaml = Yaml()
    private val log = Logger.getInstance(CustomPatternLoader::class.java)

    data class ProjectConfig(
        val patternNames: List<String>,
        val customPatterns: List<PatternSpec>,
        val fitnessConfig: FitnessFunction.FitnessConfig?,
        val excludedPaths: List<String>,
        val multiPattern: Boolean
    )

    /**
     * Load project configuration from .codevigil.yml in the project root.
     */
    fun loadProjectConfig(projectPath: String): ProjectConfig? {
        val configFile = File(projectPath, ".codevigil.yml")
        if (!configFile.exists()) {
            // Also try .codevigil.yaml
            val altFile = File(projectPath, ".codevigil.yaml")
            if (!altFile.exists()) return null
            return parseConfigFile(altFile, projectPath)
        }
        return parseConfigFile(configFile, projectPath)
    }

    /**
     * Load all custom pattern YAML files from .codevigil/ directory.
     */
    fun loadCustomPatterns(projectPath: String): List<PatternSpec> {
        val customDir = File(projectPath, ".codevigil")
        if (!customDir.isDirectory) return emptyList()

        return customDir.listFiles()
            ?.filter { it.extension in listOf("yml", "yaml") && it.name != ".codevigil.yml" }
            ?.mapNotNull { file ->
                try {
                    parsePatternFile(file)
                } catch (e: Exception) {
                    log.warn("Failed to load custom pattern '${file.name}': ${e.message}")
                    null
                }
            } ?: emptyList()
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseConfigFile(file: File, projectPath: String): ProjectConfig? {
        return try {
            val data = yaml.load<Map<String, Any>>(file.inputStream()) ?: return null

            val patternNames = (data["patterns"] as? List<String>) ?: emptyList()

            val customPatterns = mutableListOf<PatternSpec>()
            val customPatternRefs = data["custom_patterns"] as? List<Map<String, Any>> ?: emptyList()
            for (ref in customPatternRefs) {
                val filePath = ref["file"] as? String
                if (filePath != null) {
                    val patternFile = File(projectPath, filePath)
                    if (patternFile.exists()) {
                        parsePatternFile(patternFile)?.let { customPatterns.add(it) }
                    }
                }
            }

            // Also load patterns from .codevigil/ directory
            customPatterns.addAll(loadCustomPatterns(projectPath))

            val fitnessData = data["fitness"] as? Map<String, Any>
            val fitnessConfig = fitnessData?.let { FitnessFunction.parseConfig(it) }

            val excludedPaths = (data["exclude"] as? List<String>) ?: emptyList()
            val multiPattern = data["multi_pattern"] as? Boolean ?: false

            ProjectConfig(
                patternNames = patternNames,
                customPatterns = customPatterns,
                fitnessConfig = fitnessConfig,
                excludedPaths = excludedPaths,
                multiPattern = multiPattern
            )
        } catch (e: Exception) {
            log.warn("Failed to parse .codevigil.yml: ${e.message}")
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parsePatternFile(file: File): PatternSpec? {
        val data = yaml.load<Map<String, Any>>(file.inputStream()) ?: return null
        val name = data["name"] as? String ?: return null
        val description = data["description"] as? String ?: ""

        val layersData = data["layers"] as? List<Map<String, Any>> ?: emptyList()
        val layers = layersData.map { ld ->
            Layer(
                name = ld["name"] as? String ?: "",
                description = ld["description"] as? String ?: "",
                filePatterns = (ld["file_patterns"] as? List<String>) ?: emptyList(),
                namingConventions = (ld["naming_conventions"] as? List<String>) ?: emptyList(),
                allowedDependencies = (ld["allowed_dependencies"] as? List<String>) ?: emptyList()
            )
        }

        val rulesData = data["rules"] as? List<Map<String, Any>> ?: emptyList()
        val rules = rulesData.map { rd ->
            PatternRule(
                id = rd["id"] as? String ?: "",
                name = rd["name"] as? String ?: "",
                description = rd["description"] as? String ?: "",
                severity = when ((rd["severity"] as? String)?.uppercase()) {
                    "ERROR" -> ViolationSeverity.ERROR
                    "INFO" -> ViolationSeverity.INFO
                    else -> ViolationSeverity.WARNING
                },
                type = try {
                    RuleType.valueOf((rd["type"] as? String)?.uppercase() ?: "FILE_ORGANIZATION")
                } catch (_: Exception) {
                    RuleType.FILE_ORGANIZATION
                },
                config = (rd["config"] as? Map<String, Any>) ?: emptyMap()
            )
        }

        return PatternSpec(name = name, description = description, layers = layers, rules = rules)
    }
}
