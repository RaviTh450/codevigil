package com.codepattern.scanner

import com.codepattern.plugin.PatternSettingsState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiRecursiveElementVisitor
import com.intellij.psi.PsiElement
import com.intellij.openapi.roots.ProjectRootManager

class ProjectScanner {

    fun scan(project: Project): ScannedProject {
        return ReadAction.compute<ScannedProject, RuntimeException> {
            val basePath = project.basePath ?: ""
            val files = mutableListOf<ScannedFile>()
            val psiManager = PsiManager.getInstance(project)
            val excludedDirs = getExcludedDirs()

            ProjectRootManager.getInstance(project).contentRoots.forEach { root ->
                collectFiles(root, basePath, psiManager, files, excludedDirs)
            }

            ScannedProject(basePath = basePath, files = files)
        }
    }

    fun scanSingleFile(psiFile: PsiFile, basePath: String): ScannedFile {
        return ReadAction.compute<ScannedFile, RuntimeException> {
            scanFile(psiFile, basePath)
        }
    }

    private fun getExcludedDirs(): Set<String> {
        return try {
            val settings = PatternSettingsState.getInstance()
            val userExcluded = settings.state.excludedPaths
            if (userExcluded.isNotEmpty()) userExcluded.toSet() else DEFAULT_EXCLUDED_DIRS
        } catch (_: Exception) {
            DEFAULT_EXCLUDED_DIRS
        }
    }

    private fun collectFiles(
        dir: VirtualFile,
        basePath: String,
        psiManager: PsiManager,
        files: MutableList<ScannedFile>,
        excludedDirs: Set<String>
    ) {
        for (child in dir.children) {
            if (child.isDirectory) {
                if (child.name in excludedDirs) continue
                collectFiles(child, basePath, psiManager, files, excludedDirs)
            } else {
                if (isSourceFile(child)) {
                    val psiFile = psiManager.findFile(child)
                    if (psiFile != null) {
                        files += scanFile(psiFile, basePath)
                    }
                }
            }
        }
    }

    private fun scanFile(psiFile: PsiFile, basePath: String): ScannedFile {
        val relativePath = psiFile.virtualFile.path.removePrefix(basePath).removePrefix("/")
        val text = psiFile.text
        val lines = text.lines()
        val language = detectLanguage(psiFile)
        val imports = ImportExtractor.extractImports(lines, language)
        val importLineNumbers = ImportExtractor.extractImportLineNumbers(lines, language)
        val concreteClassDeps = ImportExtractor.extractDirectInstantiations(lines, language)

        var methodCount = 0
        var isInterface = false
        var className: String? = null

        psiFile.accept(object : PsiRecursiveElementVisitor() {
            override fun visitElement(element: PsiElement) {
                val elementType = element.node?.elementType?.toString()?.lowercase() ?: ""
                val elementText = element.text

                // Count method-like declarations across languages
                if (elementType.contains("method") || elementType.contains("function") ||
                    elementType.contains("fun_") || elementType.contains("def")
                ) {
                    if (element.parent?.node?.elementType?.toString()?.lowercase()?.let {
                            it.contains("class") || it.contains("file") || it.contains("object") ||
                                    it.contains("module") || it.contains("program")
                        } == true) {
                        methodCount++
                    }
                }

                // Detect interfaces
                if (elementType.contains("interface") || elementType.contains("protocol") ||
                    (elementType.contains("class") && elementText.trimStart().startsWith("abstract"))
                ) {
                    isInterface = true
                }

                // Detect class name
                if (className == null && (elementType.contains("class") || elementType.contains("interface"))) {
                    element.children.firstOrNull { child ->
                        child.node?.elementType?.toString()?.lowercase()?.contains("identifier") == true
                    }?.let { identifier ->
                        className = identifier.text
                    }
                }

                super.visitElement(element)
            }
        })

        return ScannedFile(
            relativePath = relativePath,
            absolutePath = psiFile.virtualFile.path,
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

    private fun detectLanguage(psiFile: PsiFile): String {
        return when (psiFile.virtualFile.extension?.lowercase()) {
            "java" -> "java"
            "kt", "kts" -> "kotlin"
            "py" -> "python"
            "ts" -> "typescript"
            "tsx" -> "typescript"
            "js" -> "javascript"
            "jsx" -> "javascript"
            "cs" -> "csharp"
            "go" -> "go"
            "rs" -> "rust"
            "rb" -> "ruby"
            "php" -> "php"
            "swift" -> "swift"
            "dart" -> "dart"
            else -> psiFile.language.id.lowercase()
        }
    }

    companion object {
        private val DEFAULT_EXCLUDED_DIRS = setOf(
            "node_modules", ".git", ".idea", ".gradle", "build", "dist", "out",
            "__pycache__", ".venv", "venv", "target", ".next", ".nuxt",
            "vendor", "Pods", ".dart_tool", ".pub-cache"
        )

        private val SOURCE_EXTENSIONS = setOf(
            "java", "kt", "kts", "py", "ts", "tsx", "js", "jsx",
            "cs", "go", "rs", "rb", "php", "swift", "dart",
            "scala", "groovy", "clj", "ex", "exs", "vue", "svelte"
        )

        private fun isSourceFile(file: VirtualFile): Boolean {
            return file.extension?.lowercase() in SOURCE_EXTENSIONS
        }
    }
}
