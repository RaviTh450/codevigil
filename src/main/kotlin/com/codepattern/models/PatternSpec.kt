package com.codepattern.models

/**
 * Represents a loaded pattern specification.
 * Parsed from YAML pattern definition files.
 */
data class PatternSpec(
    val name: String,
    val description: String,
    val layers: List<Layer>,
    val rules: List<PatternRule>
)

data class Layer(
    val name: String,
    val description: String,
    val filePatterns: List<String>,       // glob patterns to identify files in this layer
    val namingConventions: List<String>,  // regex patterns for class/file naming
    val allowedDependencies: List<String> // names of other layers this layer can depend on
)

data class PatternRule(
    val id: String,
    val name: String,
    val description: String,
    val severity: ViolationSeverity,
    val type: RuleType,
    val config: Map<String, Any> = emptyMap()
)

enum class RuleType {
    LAYER_DEPENDENCY,        // validates import/dependency direction between layers
    NAMING_CONVENTION,       // validates file/class naming matches layer conventions
    FILE_ORGANIZATION,       // validates files are in correct directories
    SINGLE_RESPONSIBILITY,   // checks class complexity / multiple concerns
    INTERFACE_SEGREGATION,   // checks interface size
    DEPENDENCY_INVERSION,    // checks concrete vs abstract dependencies
    CYCLOMATIC_COMPLEXITY,   // checks method/function cyclomatic complexity
    COGNITIVE_COMPLEXITY,    // checks cognitive complexity (nesting depth, etc.)
    CODE_SMELL,              // detects common code smells (god class, feature envy, etc.)
    CIRCULAR_DEPENDENCY,     // detects circular import chains
    DEAD_CODE,               // detects potentially unused imports/code
    METHOD_LENGTH,           // checks individual method/function length
    PARAMETER_COUNT,         // checks excessive method parameters
    COUPLING                 // checks afferent/efferent coupling between modules
}
