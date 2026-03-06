package com.codepattern.scanner

import com.codepattern.analysis.*
import com.codepattern.models.PatternSpec
import com.codepattern.models.Violation
import com.codepattern.patterns.CustomPatternLoader
import com.codepattern.patterns.PatternLoader
import com.codepattern.patterns.PatternMatcher
import com.codepattern.report.ReportGenerator
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import java.util.concurrent.CopyOnWriteArrayList

@Service(Service.Level.PROJECT)
class ProjectScannerService(private val project: Project) {

    private val scanner = ProjectScanner()
    val matcher = PatternMatcher()
    private val loader = PatternLoader()
    private val customLoader = CustomPatternLoader()
    private val log = Logger.getInstance(ProjectScannerService::class.java)

    private val scanListeners = CopyOnWriteArrayList<() -> Unit>()

    var availablePatterns: List<PatternSpec> = emptyList()
        private set

    @Volatile
    var selectedPattern: PatternSpec? = null

    /** All patterns selected for multi-pattern analysis */
    var selectedPatterns: List<PatternSpec> = emptyList()

    var lastScanResult: ScannedProject? = null
        private set

    var lastViolations: List<Violation> = emptyList()
        private set

    /** Last drift report after comparing against baseline */
    var lastDriftReport: ArchitectureDriftTracker.DriftReport? = null
        private set

    /** Last fitness function results */
    var lastFitnessResults: List<FitnessFunction.FitnessResult>? = null
        private set

    /** Last dependency graph edges */
    var lastDependencyGraph: List<DependencyGraphGenerator.LayerEdge>? = null
        private set

    /** Project-level config from .codevigil.yml */
    var projectConfig: CustomPatternLoader.ProjectConfig? = null
        private set

    fun loadPatterns() {
        val builtIn = loader.loadBuiltInPatterns()
        val basePath = project.basePath

        // Load custom patterns from .codevigil/ and .codevigil.yml
        val custom = if (basePath != null) {
            projectConfig = customLoader.loadProjectConfig(basePath)
            customLoader.loadCustomPatterns(basePath)
        } else emptyList()

        availablePatterns = builtIn + custom

        // If project config specifies patterns, auto-select them
        projectConfig?.let { config ->
            if (config.patternNames.isNotEmpty()) {
                selectedPatterns = availablePatterns.filter { pattern ->
                    config.patternNames.any { it.equals(pattern.name, ignoreCase = true) ||
                            pattern.name.lowercase().replace(" ", "-").contains(it.lowercase()) }
                }
                if (selectedPatterns.isNotEmpty() && selectedPattern == null) {
                    selectedPattern = selectedPatterns[0]
                }
            }
        }
    }

    fun scanAndAnalyze(): List<Violation> {
        val pattern = selectedPattern ?: return emptyList()
        lastScanResult = scanner.scan(project)
        lastViolations = matcher.analyze(lastScanResult!!, pattern)
        postScanAnalysis()
        notifyListeners()
        return lastViolations
    }

    /**
     * Multi-pattern scan: analyze against all selected patterns.
     */
    fun scanAndAnalyzeMultiPattern(): List<Violation> {
        val patterns = selectedPatterns.ifEmpty { listOfNotNull(selectedPattern) }
        if (patterns.isEmpty()) return emptyList()

        lastScanResult = scanner.scan(project)
        val allViolations = mutableListOf<Violation>()
        val seen = mutableSetOf<String>()  // deduplicate by fingerprint

        for (pattern in patterns) {
            for (v in matcher.analyze(lastScanResult!!, pattern)) {
                val fp = ArchitectureDriftTracker.fingerprint(v)
                if (fp !in seen) {
                    seen.add(fp)
                    allViolations.add(v)
                }
            }
        }

        lastViolations = allViolations
        postScanAnalysis()
        notifyListeners()
        return lastViolations
    }

    /**
     * Incremental scan: only re-analyze changed files and merge with previous results.
     */
    fun scanIncremental(): List<Violation> {
        val pattern = selectedPattern ?: return emptyList()
        val basePath = project.basePath ?: return emptyList()

        val changedFiles = IncrementalScanner.getChangedFilesSinceLastCommit(basePath)

        if (changedFiles.isFullScan || lastScanResult == null) {
            // Fallback to full scan
            return scanAndAnalyze()
        }

        if (changedFiles.totalChanges == 0) {
            return lastViolations  // nothing changed
        }

        // Re-scan only changed files
        val newViolations = mutableListOf<Violation>()
        val changedPaths = changedFiles.allChanged.toSet()

        // Keep violations from unchanged files
        for (v in lastViolations) {
            if (v.filePath !in changedPaths) {
                newViolations.add(v)
            }
        }

        // Re-analyze changed files
        lastScanResult = scanner.scan(project)
        for (file in lastScanResult!!.files) {
            if (file.relativePath in changedPaths) {
                newViolations.addAll(matcher.analyzeFile(file, pattern))
            }
        }

        lastViolations = newViolations
        postScanAnalysis()
        notifyListeners()
        return lastViolations
    }

    /**
     * Run post-scan analysis: drift detection, fitness functions, dependency graph.
     */
    private fun postScanAnalysis() {
        val basePath = project.basePath ?: return
        val pattern = selectedPattern ?: return
        val scannedProject = lastScanResult ?: return

        // Dependency graph
        try {
            lastDependencyGraph = DependencyGraphGenerator.buildActualGraph(scannedProject, pattern, matcher)
        } catch (e: Exception) {
            log.debug("Dependency graph generation failed: ${e.message}")
        }

        // Drift detection
        try {
            val healthScore = ReportGenerator.computeHealthScore(lastViolations, scannedProject.files.size)
            val baseline = ArchitectureDriftTracker.loadBaseline(basePath, pattern.name)
            if (baseline != null) {
                lastDriftReport = ArchitectureDriftTracker.detectDrift(baseline, healthScore, lastViolations)
            }
        } catch (e: Exception) {
            log.debug("Drift detection failed: ${e.message}")
        }

        // Fitness functions
        try {
            val fitnessConfig = projectConfig?.fitnessConfig ?: FitnessFunction.FitnessConfig()
            lastFitnessResults = FitnessFunction.evaluate(lastViolations, scannedProject.files.size, fitnessConfig)
        } catch (e: Exception) {
            log.debug("Fitness evaluation failed: ${e.message}")
        }
    }

    /**
     * Save current scan as a baseline for drift tracking.
     */
    fun saveBaseline() {
        val basePath = project.basePath ?: return
        val pattern = selectedPattern ?: return
        val scannedProject = lastScanResult ?: return
        val healthScore = ReportGenerator.computeHealthScore(lastViolations, scannedProject.files.size)
        ArchitectureDriftTracker.saveBaseline(basePath, pattern.name, healthScore, lastViolations)
    }

    /**
     * Auto-detect the best matching pattern based on project structure.
     * Looks for common folder names / conventions to guess the architecture.
     */
    fun autoDetectPattern() {
        val basePath = project.basePath ?: return
        val projectDir = java.io.File(basePath)
        val topDirs = projectDir.listFiles()?.filter { it.isDirectory }?.map { it.name.lowercase() }?.toSet() ?: return

        // Heuristic: look for common architecture markers
        val patternScores = mutableMapOf<String, Int>()

        // MVC markers
        if (topDirs.containsAny("controllers", "views", "models") ||
            projectDir.walkTopDown().maxDepth(3).any { it.isDirectory && it.name.lowercase() in setOf("controller", "view", "model") }) {
            patternScores["MVC"] = (patternScores["MVC"] ?: 0) + 3
        }

        // Clean Architecture markers
        if (topDirs.containsAny("domain", "usecases", "use_cases", "entities", "infrastructure")) {
            patternScores["Clean Architecture"] = (patternScores["Clean Architecture"] ?: 0) + 3
        }

        // DDD markers
        if (topDirs.containsAny("domain", "aggregate", "valueobject", "value_objects", "bounded_context")) {
            patternScores["Domain-Driven Design"] = (patternScores["Domain-Driven Design"] ?: 0) + 2
        }

        // Hexagonal markers
        if (topDirs.containsAny("ports", "adapters", "hexagonal")) {
            patternScores["Hexagonal Architecture"] = (patternScores["Hexagonal Architecture"] ?: 0) + 3
        }

        // Layered markers
        if (topDirs.containsAny("presentation", "business", "data", "persistence")) {
            patternScores["Layered Architecture"] = (patternScores["Layered Architecture"] ?: 0) + 3
        }

        // Repository pattern
        if (projectDir.walkTopDown().maxDepth(3).any { it.isDirectory && it.name.lowercase().contains("repository") }) {
            patternScores["Repository Pattern"] = (patternScores["Repository Pattern"] ?: 0) + 2
        }

        // CQRS markers
        if (topDirs.containsAny("commands", "queries", "command", "query")) {
            patternScores["CQRS"] = (patternScores["CQRS"] ?: 0) + 3
        }

        // If we have a match, select the best one
        val bestMatch = patternScores.maxByOrNull { it.value }
        if (bestMatch != null && bestMatch.value >= 2) {
            selectedPattern = availablePatterns.find {
                it.name.equals(bestMatch.key, ignoreCase = true)
            }
            if (selectedPattern != null) {
                log.info("Auto-detected pattern: ${selectedPattern!!.name}")
            }
        }

        // Fallback: always enable SOLID + Code Quality as they apply universally
        if (selectedPattern == null) {
            selectedPattern = availablePatterns.find { it.name.equals("SOLID Principles", ignoreCase = true) }
                ?: availablePatterns.find { it.name.contains("Code Quality", ignoreCase = true) }
        }
    }

    private fun Set<String>.containsAny(vararg values: String): Boolean = values.any { it in this }

    fun analyzeFile(psiFile: PsiFile): List<Violation> {
        val pattern = selectedPattern ?: return emptyList()
        val basePath = project.basePath ?: return emptyList()
        val scannedFile = scanner.scanSingleFile(psiFile, basePath)
        return matcher.analyzeFile(scannedFile, pattern)
    }

    fun getViolationsForFile(filePath: String): List<Violation> {
        val basePath = project.basePath ?: return emptyList()
        val relativePath = filePath.removePrefix(basePath).removePrefix("/")
        return lastViolations.filter { it.filePath == relativePath }
    }

    fun classifyProjectFiles(): Map<String, List<ScannedFile>> {
        val pattern = selectedPattern ?: return emptyMap()
        val scannedProject = lastScanResult ?: return emptyMap()
        return matcher.classifyFiles(scannedProject, pattern)
    }

    fun addScanListener(listener: () -> Unit) {
        scanListeners.add(listener)
    }

    fun removeScanListener(listener: () -> Unit) {
        scanListeners.remove(listener)
    }

    private fun notifyListeners() {
        scanListeners.forEach { it() }
    }
}
