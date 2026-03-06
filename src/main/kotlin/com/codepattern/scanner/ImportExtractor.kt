package com.codepattern.scanner

/**
 * Language-agnostic import/dependency extraction from source lines.
 * Extracted as a standalone utility for testability.
 */
object ImportExtractor {

    fun extractImports(lines: List<String>, language: String): List<String> {
        return lines.mapNotNull { line -> extractImportFromLine(line.trim(), language) }
    }

    fun extractImportLineNumbers(lines: List<String>, language: String): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        lines.forEachIndexed { index, line ->
            val importPath = extractImportFromLine(line.trim(), language)
            if (importPath != null) {
                result[importPath] = index + 1
            }
        }
        return result
    }

    fun extractImportFromLine(line: String, language: String): String? {
        return when (language) {
            "java", "kotlin" -> {
                if (line.startsWith("import ")) {
                    line.removePrefix("import ").removePrefix("static ").removeSuffix(";").trim()
                } else null
            }
            "python" -> {
                when {
                    line.startsWith("from ") -> line.removePrefix("from ").substringBefore(" import").trim()
                    line.startsWith("import ") -> line.removePrefix("import ").substringBefore(",").trim()
                    else -> null
                }
            }
            "typescript", "javascript" -> {
                when {
                    line.contains("from ") && (line.startsWith("import") || line.contains("require")) -> {
                        line.substringAfter("from ").trim().removeSuffix(";")
                            .removeSurrounding("'").removeSurrounding("\"")
                    }
                    line.contains("require(") -> {
                        line.substringAfter("require(").substringBefore(")").trim()
                            .removeSurrounding("'").removeSurrounding("\"")
                    }
                    else -> null
                }
            }
            "go" -> {
                val trimmed = line.trim()
                if (trimmed.startsWith("\"") || trimmed.startsWith("`")) {
                    trimmed.removeSurrounding("\"").removeSurrounding("`").trim()
                } else null
            }
            "csharp" -> {
                if (line.startsWith("using ") && !line.startsWith("using (") && !line.startsWith("using var")) {
                    line.removePrefix("using ").removePrefix("static ").removeSuffix(";").trim()
                } else null
            }
            "rust" -> {
                if (line.startsWith("use ")) {
                    line.removePrefix("use ").removeSuffix(";").trim()
                } else null
            }
            "php" -> {
                if (line.startsWith("use ")) {
                    line.removePrefix("use ").removeSuffix(";").substringBefore(" as ").trim()
                } else null
            }
            "ruby" -> {
                when {
                    line.startsWith("require ") -> line.removePrefix("require ").removeSurrounding("'").removeSurrounding("\"")
                    line.startsWith("require_relative ") -> line.removePrefix("require_relative ").removeSurrounding("'").removeSurrounding("\"")
                    else -> null
                }
            }
            "swift" -> {
                if (line.startsWith("import ")) line.removePrefix("import ").trim() else null
            }
            "dart" -> {
                if (line.startsWith("import ")) {
                    line.removePrefix("import ").removeSuffix(";").trim()
                        .removeSurrounding("'").removeSurrounding("\"")
                } else null
            }
            else -> null
        }
    }

    /**
     * Detect direct instantiation patterns (new Foo(), Foo()) to find concrete dependencies.
     * Returns list of class names that are directly instantiated.
     */
    fun extractDirectInstantiations(lines: List<String>, language: String): List<String> {
        val results = mutableListOf<String>()
        val classNamePattern = "[A-Z][A-Za-z0-9_]+"

        for (line in lines) {
            val trimmed = line.trim()
            when (language) {
                "java", "csharp", "dart", "php" -> {
                    // new ClassName(
                    val newPattern = Regex("""new\s+($classNamePattern)\s*\(""")
                    newPattern.findAll(trimmed).forEach { results += it.groupValues[1] }
                }
                "kotlin" -> {
                    // ClassName( but not fun/val/var declarations or annotations
                    if (!trimmed.startsWith("fun ") && !trimmed.startsWith("class ") &&
                        !trimmed.startsWith("interface ") && !trimmed.startsWith("@")) {
                        val ctorPattern = Regex("""(?<!=\s*)($classNamePattern)\(""")
                        ctorPattern.findAll(trimmed).forEach { match ->
                            val name = match.groupValues[1]
                            // Exclude common non-constructor calls
                            if (name[0].isUpperCase() && name !in KOTLIN_BUILTINS) {
                                results += name
                            }
                        }
                    }
                }
                "python" -> {
                    val ctorPattern = Regex("""($classNamePattern)\(""")
                    ctorPattern.findAll(trimmed).forEach { match ->
                        val name = match.groupValues[1]
                        if (name[0].isUpperCase() && name !in PYTHON_BUILTINS) {
                            results += name
                        }
                    }
                }
                "typescript", "javascript" -> {
                    val newPattern = Regex("""new\s+($classNamePattern)\s*\(""")
                    newPattern.findAll(trimmed).forEach { results += it.groupValues[1] }
                }
                "go" -> {
                    // pkg.StructName{} or &pkg.StructName{}
                    val literalPattern = Regex("""&?($classNamePattern)\{""")
                    literalPattern.findAll(trimmed).forEach { results += it.groupValues[1] }
                }
                "rust" -> {
                    // StructName { or StructName::new(
                    val structPattern = Regex("""($classNamePattern)(?:\s*\{|::new\()""")
                    structPattern.findAll(trimmed).forEach { results += it.groupValues[1] }
                }
                "swift" -> {
                    val ctorPattern = Regex("""($classNamePattern)\(""")
                    ctorPattern.findAll(trimmed).forEach { match ->
                        val name = match.groupValues[1]
                        if (name[0].isUpperCase()) results += name
                    }
                }
            }
        }

        return results.distinct()
    }

    private val KOTLIN_BUILTINS = setOf(
        "String", "Int", "Long", "Float", "Double", "Boolean", "Char", "Byte", "Short",
        "Array", "List", "Map", "Set", "MutableList", "MutableMap", "MutableSet",
        "Pair", "Triple", "Regex", "Exception", "RuntimeException", "IllegalArgumentException",
        "IllegalStateException", "UnsupportedOperationException", "IndexOutOfBoundsException"
    )

    private val PYTHON_BUILTINS = setOf(
        "Exception", "ValueError", "TypeError", "KeyError", "IndexError", "RuntimeError",
        "AttributeError", "IOError", "OSError", "FileNotFoundError", "NotImplementedError",
        "StopIteration", "GeneratorExit", "SystemExit", "BaseException"
    )
}
