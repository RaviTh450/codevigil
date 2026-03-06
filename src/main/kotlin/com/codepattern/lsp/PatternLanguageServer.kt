package com.codepattern.lsp

import com.codepattern.analysis.CircularDependencyDetector
import com.codepattern.analysis.ComplexityAnalyzer
import com.codepattern.models.*
import com.codepattern.patterns.PatternMatcher
import com.codepattern.report.ReportGenerator
import com.codepattern.standalone.StandaloneScanner
import org.yaml.snakeyaml.Yaml
import java.io.*
import java.net.ServerSocket
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Lightweight LSP-like server for cross-IDE support.
 *
 * Communicates via JSON-RPC 2.0 over stdin/stdout (standard LSP transport).
 * Supports:
 *   - textDocument/didOpen → triggers analysis
 *   - textDocument/didSave → re-analyze the file
 *   - textDocument/publishDiagnostics → sends violations as diagnostics
 *   - codePatternAnalyzer/scan → full project scan
 *   - codePatternAnalyzer/setPattern → change active pattern
 *
 * For VS Code: use a thin extension wrapper that launches this JAR.
 * For Vim/Neovim: configure in nvim-lspconfig with cmd = {"java", "-jar", "codevigil.jar", "--lsp"}
 * For Emacs: configure in lsp-mode.
 * For Sublime Text: configure in LSP package settings.
 */
class PatternLanguageServer {

    private val scanner = StandaloneScanner()
    private val matcher = PatternMatcher()
    private val yaml = Yaml()

    private var workspaceRoot: String? = null
    private var selectedPattern: PatternSpec? = null
    private var availablePatterns: List<PatternSpec> = emptyList()
    private val diagnosticsCache = ConcurrentHashMap<String, List<Violation>>()
    private val debounceScheduler = Executors.newSingleThreadScheduledExecutor()
    private val pendingAnalysis = ConcurrentHashMap<String, ScheduledFuture<*>>()

    private lateinit var input: BufferedReader
    private lateinit var output: OutputStream

    fun start(inputStream: InputStream, outputStream: OutputStream) {
        input = BufferedReader(InputStreamReader(inputStream))
        output = outputStream
        loadBuiltInPatterns()

        while (true) {
            try {
                val message = readMessage() ?: break
                handleMessage(message)
            } catch (e: Exception) {
                logError("Error processing message: ${e.message}")
            }
        }
    }

    fun startOnPort(port: Int) {
        val server = ServerSocket(port)
        logInfo("LSP server listening on port $port")
        while (true) {
            val socket = server.accept()
            Thread {
                try {
                    start(socket.getInputStream(), socket.getOutputStream())
                } finally {
                    socket.close()
                }
            }.start()
        }
    }

    private fun loadBuiltInPatterns() {
        val patternFiles = listOf(
            "patterns/mvc.yml", "patterns/clean-architecture.yml", "patterns/solid.yml",
            "patterns/ddd.yml", "patterns/repository.yml", "patterns/hexagonal.yml",
            "patterns/cqrs.yml", "patterns/microservices.yml", "patterns/layered.yml",
            "patterns/observer.yml", "patterns/factory.yml", "patterns/code-quality.yml"
        )
        availablePatterns = patternFiles.mapNotNull { path ->
            try {
                val stream = javaClass.classLoader.getResourceAsStream(path) ?: return@mapNotNull null
                parsePatternYaml(stream)
            } catch (_: Exception) { null }
        }
        if (availablePatterns.isNotEmpty()) {
            selectedPattern = availablePatterns[0]
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parsePatternYaml(input: InputStream): PatternSpec? {
        val data = yaml.load<Map<String, Any>>(input) ?: return null
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
                    "ERROR" -> ViolationSeverity.ERROR; "INFO" -> ViolationSeverity.INFO
                    else -> ViolationSeverity.WARNING
                },
                type = try { RuleType.valueOf((rd["type"] as? String)?.uppercase() ?: "FILE_ORGANIZATION") }
                catch (_: Exception) { RuleType.FILE_ORGANIZATION },
                config = (rd["config"] as? Map<String, Any>) ?: emptyMap()
            )
        }
        return PatternSpec(name = name, description = description, layers = layers, rules = rules)
    }

    // ── JSON-RPC message handling ──

    private fun readMessage(): String? {
        // Read Content-Length header
        var contentLength = -1
        while (true) {
            val line = input.readLine() ?: return null
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:")) {
                contentLength = line.substringAfter(":").trim().toIntOrNull() ?: -1
            }
        }
        if (contentLength <= 0) return null
        val buffer = CharArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = input.read(buffer, read, contentLength - read)
            if (n == -1) return null
            read += n
        }
        return String(buffer)
    }

    private fun sendMessage(json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val header = "Content-Length: ${bytes.size}\r\n\r\n"
        synchronized(output) {
            output.write(header.toByteArray(Charsets.UTF_8))
            output.write(bytes)
            output.flush()
        }
    }

    private fun handleMessage(json: String) {
        // Minimal JSON parsing (avoids adding a JSON library dependency)
        val method = extractJsonString(json, "method")
        val id = extractJsonValue(json, "id")

        when (method) {
            "initialize" -> handleInitialize(id, json)
            "initialized" -> {} // no-op
            "textDocument/didOpen" -> handleDidOpen(json)
            "textDocument/didChange" -> handleDidChange(json)
            "textDocument/didSave" -> handleDidSave(json)
            "textDocument/didClose" -> handleDidClose(json)
            "shutdown" -> sendResponse(id, "null")
            "exit" -> return
            "codePatternAnalyzer/scan" -> handleFullScan(id)
            "codePatternAnalyzer/setPattern" -> handleSetPattern(id, json)
            "codePatternAnalyzer/listPatterns" -> handleListPatterns(id)
        }
    }

    private fun handleInitialize(id: String?, json: String) {
        // Extract rootUri from params
        val rootUri = extractJsonString(json, "rootUri")
            ?: extractJsonString(json, "rootPath")
        workspaceRoot = rootUri?.removePrefix("file://")?.removePrefix("file:")

        val response = """
        {
            "capabilities": {
                "textDocumentSync": {"openClose": true, "change": 1, "save": true},
                "diagnosticProvider": { "interFileDependencies": false, "workspaceDiagnostics": false }
            },
            "serverInfo": {
                "name": "CodeVigil",
                "version": "1.0.0"
            }
        }
        """.trimIndent()
        sendResponse(id, response)
    }

    private fun handleDidOpen(json: String) {
        val uri = extractNestedJsonString(json, "textDocument", "uri") ?: return
        analyzeAndPublishDiagnostics(uri)
    }

    private fun handleDidChange(json: String) {
        val uri = extractNestedJsonString(json, "textDocument", "uri") ?: return
        // Debounce: cancel previous pending analysis for this file, schedule new one
        pendingAnalysis[uri]?.cancel(false)
        pendingAnalysis[uri] = debounceScheduler.schedule({
            analyzeAndPublishDiagnostics(uri)
            pendingAnalysis.remove(uri)
        }, 500, TimeUnit.MILLISECONDS)
    }

    private fun handleDidSave(json: String) {
        val uri = extractNestedJsonString(json, "textDocument", "uri") ?: return
        // On save, cancel debounce and analyze immediately
        pendingAnalysis[uri]?.cancel(false)
        pendingAnalysis.remove(uri)
        analyzeAndPublishDiagnostics(uri)
    }

    private fun handleDidClose(json: String) {
        val uri = extractNestedJsonString(json, "textDocument", "uri") ?: return
        diagnosticsCache.remove(uri)
        publishDiagnostics(uri, emptyList())
    }

    private fun handleFullScan(id: String?) {
        val root = workspaceRoot ?: run {
            sendResponse(id, """{"error": "No workspace root set"}""")
            return
        }
        val pattern = selectedPattern ?: run {
            sendResponse(id, """{"error": "No pattern selected"}""")
            return
        }

        val project = scanner.scan(root)
        val violations = matcher.analyze(project, pattern)
        val healthScore = ReportGenerator.computeHealthScore(violations, project.files.size)

        // Group by file and publish diagnostics
        val byFile = violations.groupBy { it.filePath }
        for ((filePath, fileViolations) in byFile) {
            val uri = "file://$root/$filePath"
            diagnosticsCache[uri] = fileViolations
            publishDiagnostics(uri, fileViolations)
        }

        sendResponse(id, """{"totalFiles": ${project.files.size}, "totalViolations": ${violations.size}, "healthScore": $healthScore}""")
    }

    private fun handleSetPattern(id: String?, json: String) {
        val patternName = extractJsonString(json, "patternName")
        selectedPattern = availablePatterns.find { it.name.equals(patternName, ignoreCase = true) }
        sendResponse(id, """{"pattern": "${selectedPattern?.name ?: "none"}"}""")
    }

    private fun handleListPatterns(id: String?) {
        val names = availablePatterns.joinToString(", ") { "\"${it.name}\"" }
        sendResponse(id, """{"patterns": [$names]}""")
    }

    private fun analyzeAndPublishDiagnostics(uri: String) {
        val pattern = selectedPattern ?: return
        val root = workspaceRoot ?: return
        val filePath = uri.removePrefix("file://")

        try {
            val scannedFile = scanner.scanSingleFile(filePath, root)
            val allViolations = mutableListOf<Violation>()

            // Pattern violations
            allViolations += matcher.analyzeFile(scannedFile, pattern)

            // File-level analysis: security, dead code, naming
            val lines = java.io.File(filePath).readLines()
            allViolations += com.codepattern.analysis.SecurityScanner.analyzeFile(scannedFile, lines)
                .let { com.codepattern.analysis.SecurityScanner.toViolations(it) }
            allViolations += com.codepattern.analysis.DeadCodeDetector.analyzeFile(scannedFile, lines)
                .let { com.codepattern.analysis.DeadCodeDetector.toViolations(it) }
            allViolations += com.codepattern.analysis.NamingConventionChecker.analyzeFile(scannedFile, lines)
                .let { com.codepattern.analysis.NamingConventionChecker.toViolations(it) }

            diagnosticsCache[uri] = allViolations
            publishDiagnostics(uri, allViolations)
        } catch (_: Exception) {
            // Ignore files that can't be analyzed
        }
    }

    private fun publishDiagnostics(uri: String, violations: List<Violation>) {
        val diagnostics = violations.joinToString(",\n") { v ->
            val severity = when (v.severity) {
                ViolationSeverity.ERROR -> 1
                ViolationSeverity.WARNING -> 2
                ViolationSeverity.INFO -> 3
            }
            """
            {
                "range": {"start": {"line": ${v.lineNumber - 1}, "character": 0}, "end": {"line": ${v.lineNumber - 1}, "character": 1000}},
                "severity": $severity,
                "source": "CodeVigil",
                "message": "[${escapeJson(v.patternName)}] ${escapeJson(v.message)}${if (v.suggestedFix != null) "\nSuggestion: ${escapeJson(v.suggestedFix)}" else ""}"
            }
            """.trimIndent()
        }

        val notification = """
        {
            "jsonrpc": "2.0",
            "method": "textDocument/publishDiagnostics",
            "params": {
                "uri": "${escapeJson(uri)}",
                "diagnostics": [$diagnostics]
            }
        }
        """.trimIndent()
        sendMessage(notification)
    }

    private fun sendResponse(id: String?, result: String) {
        val response = """{"jsonrpc": "2.0", "id": $id, "result": $result}"""
        sendMessage(response)
    }

    // Minimal JSON string extraction (avoids full JSON parser dependency)
    private fun extractJsonString(json: String, key: String): String? {
        val pattern = Regex(""""$key"\s*:\s*"([^"]*?)"""")
        return pattern.find(json)?.groupValues?.getOrNull(1)
    }

    private fun extractJsonValue(json: String, key: String): String? {
        val pattern = Regex(""""$key"\s*:\s*(\w+|"[^"]*?")""")
        return pattern.find(json)?.groupValues?.getOrNull(1)
    }

    private fun extractNestedJsonString(json: String, outer: String, inner: String): String? {
        val outerPattern = Regex(""""$outer"\s*:\s*\{([^}]*)\}""")
        val outerMatch = outerPattern.find(json) ?: return null
        return extractJsonString(outerMatch.groupValues[1], inner)
    }

    private fun escapeJson(s: String): String {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
    }

    private fun logInfo(msg: String) { System.err.println("[INFO] $msg") }
    private fun logError(msg: String) { System.err.println("[ERROR] $msg") }
}
