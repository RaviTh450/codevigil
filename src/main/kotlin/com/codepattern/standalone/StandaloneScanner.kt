package com.codepattern.standalone

import com.codepattern.scanner.ImportExtractor
import com.codepattern.scanner.ScannedFile
import com.codepattern.scanner.ScannedProject
import java.io.File

/**
 * Standalone project scanner that works without IntelliJ PSI.
 * Used by the CLI tool and LSP server for cross-IDE support.
 * Performs text-based analysis — less precise than PSI but works everywhere.
 */
class StandaloneScanner(
    private val excludedDirs: Set<String> = DEFAULT_EXCLUDED_DIRS
) {

    fun scan(projectPath: String): ScannedProject {
        val baseDir = File(projectPath)
        if (!baseDir.isDirectory) {
            throw IllegalArgumentException("Not a valid directory: $projectPath")
        }

        val files = mutableListOf<ScannedFile>()
        collectFiles(baseDir, baseDir.absolutePath, files)
        return ScannedProject(basePath = baseDir.absolutePath, files = files)
    }

    fun scanSingleFile(filePath: String, basePath: String): ScannedFile {
        val file = File(filePath)
        if (!file.isFile) throw IllegalArgumentException("Not a valid file: $filePath")
        return parseFile(file, basePath)
    }

    /**
     * Scan only specific files (for incremental scanning).
     */
    fun scanFiles(projectPath: String, relativePaths: List<String>): ScannedProject {
        val baseDir = File(projectPath)
        val files = mutableListOf<ScannedFile>()
        for (relPath in relativePaths) {
            val file = File(baseDir, relPath)
            if (file.isFile && isSourceFile(file)) {
                try {
                    files += parseFile(file, baseDir.absolutePath)
                } catch (_: Exception) {
                    // Skip files that can't be parsed
                }
            }
        }
        return ScannedProject(basePath = baseDir.absolutePath, files = files)
    }

    private fun collectFiles(dir: File, basePath: String, files: MutableList<ScannedFile>) {
        val children = dir.listFiles() ?: return
        for (child in children.sortedBy { it.name }) {
            if (child.isDirectory) {
                if (child.name in excludedDirs) continue
                collectFiles(child, basePath, files)
            } else if (isSourceFile(child)) {
                try {
                    files += parseFile(child, basePath)
                } catch (_: Exception) {
                    // Skip files that can't be parsed
                }
            }
        }
    }

    private fun parseFile(file: File, basePath: String): ScannedFile {
        val relativePath = file.absolutePath.removePrefix(basePath).removePrefix("/").removePrefix("\\")
        val language = detectLanguage(file.extension.lowercase())
        val lines = file.readLines()

        val imports = ImportExtractor.extractImports(lines, language)
        val importLineNumbers = ImportExtractor.extractImportLineNumbers(lines, language)
        val concreteClassDeps = ImportExtractor.extractDirectInstantiations(lines, language)

        var methodCount = 0
        var isInterface = false
        var className: String? = null
        var classCount = 0

        for (line in lines) {
            val trimmed = line.trim()

            // Count methods/functions
            if (isMethodDeclaration(trimmed, language)) {
                methodCount++
            }

            // Detect interfaces
            if (isInterfaceDeclaration(trimmed, language)) {
                isInterface = true
            }

            // Detect class declarations
            if (isClassDeclaration(trimmed, language)) {
                classCount++
                if (className == null) {
                    className = extractClassName(trimmed, language)
                }
            }
        }

        return ScannedFile(
            relativePath = relativePath,
            absolutePath = file.absolutePath,
            language = language,
            imports = imports,
            importLineNumbers = importLineNumbers,
            className = className,
            methodCount = methodCount,
            lineCount = lines.size,
            isInterface = isInterface,
            concreteClassDependencies = concreteClassDeps
        )
    }

    private fun isMethodDeclaration(line: String, language: String): Boolean {
        return when (language) {
            "java", "kotlin" -> {
                (line.contains("fun ") || line.matches(Regex(""".*(?:public|private|protected|static)\s+\w.*\(.*\).*""")))
                        && !line.startsWith("//") && !line.startsWith("*")
                        && !line.startsWith("class ") && !line.startsWith("interface ")
            }
            "python" -> line.startsWith("def ") || line.matches(Regex("""\s+def\s+.*"""))
            "typescript", "javascript" -> {
                (line.contains("function ") || line.matches(Regex(""".*\w+\s*\(.*\)\s*[:{].*""")))
                        && !line.startsWith("//") && !line.startsWith("class ")
            }
            "go" -> line.startsWith("func ")
            "rust" -> line.matches(Regex("""^\s*(?:pub\s+)?fn\s+.*"""))
            "csharp" -> {
                line.matches(Regex(""".*(?:public|private|protected|internal|static)\s+\w.*\(.*\).*"""))
                        && !line.startsWith("//") && !line.startsWith("class ") && !line.startsWith("interface ")
            }
            "ruby" -> line.matches(Regex("""\s*def\s+\w+.*"""))
            "php" -> line.matches(Regex(""".*(?:public|private|protected|static)?\s*function\s+.*"""))
            "swift" -> line.matches(Regex(""".*func\s+\w+.*"""))
            "dart" -> {
                line.matches(Regex(""".*\w+\s+\w+\s*\(.*\)\s*[{]?.*"""))
                        && !line.startsWith("//") && !line.startsWith("class ")
            }
            else -> false
        }
    }

    private fun isInterfaceDeclaration(line: String, language: String): Boolean {
        return when (language) {
            "java", "kotlin", "csharp", "typescript" -> line.matches(Regex(""".*\binterface\s+\w+.*"""))
            "swift" -> line.matches(Regex(""".*\bprotocol\s+\w+.*"""))
            "go" -> line.matches(Regex(""".*type\s+\w+\s+interface\s*\{.*"""))
            "rust" -> line.matches(Regex(""".*\btrait\s+\w+.*"""))
            "python" -> line.matches(Regex(""".*class\s+\w+.*\(.*ABC.*\).*"""))
            else -> false
        }
    }

    private fun isClassDeclaration(line: String, language: String): Boolean {
        return when (language) {
            "java", "kotlin", "csharp", "typescript", "javascript", "dart", "php" ->
                line.matches(Regex(""".*\bclass\s+\w+.*"""))
            "python" -> line.matches(Regex("""^\s*class\s+\w+.*"""))
            "ruby" -> line.matches(Regex("""^\s*class\s+\w+.*"""))
            "swift" -> line.matches(Regex(""".*\b(?:class|struct)\s+\w+.*"""))
            "go" -> line.matches(Regex(""".*type\s+\w+\s+struct\s*\{.*"""))
            "rust" -> line.matches(Regex(""".*\bstruct\s+\w+.*"""))
            else -> false
        }
    }

    private fun extractClassName(line: String, language: String): String? {
        val patterns = when (language) {
            "java", "kotlin", "csharp", "typescript", "javascript", "dart", "php", "python", "ruby" ->
                Regex("""(?:class|interface)\s+(\w+)""")
            "swift" -> Regex("""(?:class|struct|protocol)\s+(\w+)""")
            "go" -> Regex("""type\s+(\w+)\s+(?:struct|interface)""")
            "rust" -> Regex("""(?:struct|trait|enum)\s+(\w+)""")
            else -> null
        }
        return patterns?.find(line)?.groupValues?.getOrNull(1)
    }

    private fun detectLanguage(extension: String): String {
        return when (extension) {
            "java" -> "java"
            "kt", "kts" -> "kotlin"
            "py" -> "python"
            "ts", "tsx" -> "typescript"
            "js", "jsx" -> "javascript"
            "cs" -> "csharp"
            "go" -> "go"
            "rs" -> "rust"
            "rb" -> "ruby"
            "php" -> "php"
            "swift" -> "swift"
            "dart" -> "dart"
            "scala" -> "scala"
            "groovy" -> "groovy"
            "vue", "svelte" -> "typescript"
            "ex", "exs" -> "elixir"
            else -> "unknown"
        }
    }

    companion object {
        val DEFAULT_EXCLUDED_DIRS = setOf(
            "node_modules", ".git", ".idea", ".gradle", "build", "dist", "out",
            "__pycache__", ".venv", "venv", "target", ".next", ".nuxt",
            "vendor", "Pods", ".dart_tool", ".pub-cache", ".svn", ".hg",
            "bin", "obj", ".vs", ".settings", ".classpath", ".project"
        )

        private val SOURCE_EXTENSIONS = setOf(
            "java", "kt", "kts", "py", "ts", "tsx", "js", "jsx",
            "cs", "go", "rs", "rb", "php", "swift", "dart",
            "scala", "groovy", "clj", "ex", "exs", "vue", "svelte"
        )

        private fun isSourceFile(file: File): Boolean {
            return file.extension.lowercase() in SOURCE_EXTENSIONS
        }
    }
}
