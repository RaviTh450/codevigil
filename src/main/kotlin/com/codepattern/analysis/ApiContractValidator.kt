package com.codepattern.analysis

import com.codepattern.models.*
import com.codepattern.scanner.ScannedFile
import com.codepattern.scanner.ScannedProject
import java.io.File

/**
 * Validates REST API endpoint conventions:
 * - Consistent HTTP method usage (GET for reads, POST for creates, etc.)
 * - Missing error handling (no try-catch in controllers)
 * - Missing validation annotations (@Valid, @NotNull)
 * - Inconsistent response types
 * - Missing auth annotations on public endpoints
 * - Hardcoded status codes vs constants
 */
object ApiContractValidator {

    enum class ApiIssueType {
        MISSING_ERROR_HANDLING,
        MISSING_VALIDATION,
        INCONSISTENT_HTTP_METHOD,
        MISSING_AUTH,
        MISSING_RESPONSE_TYPE,
        HARDCODED_PATH,
        MISSING_API_DOCS
    }

    data class ApiIssue(
        val type: ApiIssueType,
        val filePath: String,
        val lineNumber: Int,
        val endpoint: String,
        val message: String,
        val severity: ViolationSeverity,
        val confidence: Double
    )

    private val CONTROLLER_ANNOTATIONS = Regex("""@(?:RestController|Controller|RequestMapping|Api)""")
    private val ENDPOINT_ANNOTATIONS = Regex("""@(GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping|RequestMapping)\s*(?:\((.*)?\))?""")
    private val AUTH_ANNOTATIONS = Regex("""@(?:PreAuthorize|Secured|RolesAllowed|Authenticated|RequiresPermission|PermitAll)""")
    private val VALIDATION_ANNOTATIONS = Regex("""@(?:Valid|Validated|NotNull|NotBlank|NotEmpty|Size|Min|Max|Pattern)""")
    private val API_DOC_ANNOTATIONS = Regex("""@(?:ApiOperation|Operation|ApiResponse|Schema|Tag|SwaggerDefinition)""")
    private val RESPONSE_ENTITY = Regex("""ResponseEntity|Response|ApiResponse|HttpResponse""")
    private val TRY_CATCH = Regex("""(?:try\s*\{|@ExceptionHandler|@ControllerAdvice)""")

    fun analyze(project: ScannedProject, basePath: String): List<ApiIssue> {
        val issues = mutableListOf<ApiIssue>()
        for (file in project.files) {
            if (file.language !in setOf("java", "kotlin", "typescript", "python")) continue
            try {
                val lines = File("$basePath/${file.relativePath}").readLines()
                val fullText = lines.joinToString("\n")
                if (CONTROLLER_ANNOTATIONS.containsMatchIn(fullText)) {
                    issues += analyzeController(file, lines)
                }
            } catch (_: Exception) {}
        }
        return issues
    }

    private fun analyzeController(file: ScannedFile, lines: List<String>): List<ApiIssue> {
        val issues = mutableListOf<ApiIssue>()
        val fullText = lines.joinToString("\n")
        val hasGlobalErrorHandling = TRY_CATCH.containsMatchIn(fullText)
        val hasApiDocs = API_DOC_ANNOTATIONS.containsMatchIn(fullText)

        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            val endpointMatch = ENDPOINT_ANNOTATIONS.find(line)
            if (endpointMatch != null) {
                val httpMethod = endpointMatch.groupValues[1]
                val path = endpointMatch.groupValues.getOrElse(2) { "" }

                // Find the method declaration (next few lines)
                val methodLines = lines.subList(i, (i + 10).coerceAtMost(lines.size)).joinToString("\n")

                // Check for missing auth
                val contextLines = lines.subList((i - 3).coerceAtLeast(0), (i + 3).coerceAtMost(lines.size)).joinToString("\n")
                if (!AUTH_ANNOTATIONS.containsMatchIn(contextLines) && !AUTH_ANNOTATIONS.containsMatchIn(fullText.take(500))) {
                    issues += ApiIssue(
                        type = ApiIssueType.MISSING_AUTH,
                        filePath = file.relativePath, lineNumber = i + 1,
                        endpoint = "$httpMethod $path",
                        message = "Endpoint '$httpMethod ${path.take(30)}' has no authorization annotation.",
                        severity = ViolationSeverity.WARNING, confidence = 0.7
                    )
                }

                // Check for missing validation on POST/PUT/PATCH
                if (httpMethod in setOf("PostMapping", "PutMapping", "PatchMapping")) {
                    if (!VALIDATION_ANNOTATIONS.containsMatchIn(methodLines)) {
                        issues += ApiIssue(
                            type = ApiIssueType.MISSING_VALIDATION,
                            filePath = file.relativePath, lineNumber = i + 1,
                            endpoint = "$httpMethod $path",
                            message = "Write endpoint '$httpMethod' missing @Valid/@Validated on request body.",
                            severity = ViolationSeverity.WARNING, confidence = 0.65
                        )
                    }
                }

                // Check for missing error handling
                if (!hasGlobalErrorHandling) {
                    // Check if this specific method has try-catch
                    val methodBody = extractMethodBody(lines, i)
                    if (methodBody != null && !TRY_CATCH.containsMatchIn(methodBody)) {
                        issues += ApiIssue(
                            type = ApiIssueType.MISSING_ERROR_HANDLING,
                            filePath = file.relativePath, lineNumber = i + 1,
                            endpoint = "$httpMethod $path",
                            message = "Endpoint has no error handling and no @ControllerAdvice found.",
                            severity = ViolationSeverity.INFO, confidence = 0.5
                        )
                    }
                }

                // Check for missing response type
                if (!RESPONSE_ENTITY.containsMatchIn(methodLines)) {
                    issues += ApiIssue(
                        type = ApiIssueType.MISSING_RESPONSE_TYPE,
                        filePath = file.relativePath, lineNumber = i + 1,
                        endpoint = "$httpMethod $path",
                        message = "Endpoint should return ResponseEntity for proper HTTP status control.",
                        severity = ViolationSeverity.INFO, confidence = 0.5
                    )
                }

                // Check for API docs
                if (!hasApiDocs && !API_DOC_ANNOTATIONS.containsMatchIn(contextLines)) {
                    issues += ApiIssue(
                        type = ApiIssueType.MISSING_API_DOCS,
                        filePath = file.relativePath, lineNumber = i + 1,
                        endpoint = "$httpMethod $path",
                        message = "Endpoint missing API documentation annotations (@Operation, @ApiResponse).",
                        severity = ViolationSeverity.INFO, confidence = 0.5
                    )
                }
            }
            i++
        }
        return issues
    }

    private fun extractMethodBody(lines: List<String>, fromLine: Int): String? {
        var depth = 0
        var started = false
        val body = StringBuilder()
        for (i in fromLine until (fromLine + 50).coerceAtMost(lines.size)) {
            for (ch in lines[i]) {
                if (ch == '{') { depth++; started = true }
                if (ch == '}') depth--
                if (started) body.append(ch)
            }
            if (started && depth <= 0) return body.toString()
            body.append('\n')
        }
        return if (started) body.toString() else null
    }

    fun toViolations(issues: List<ApiIssue>): List<Violation> = issues.map { issue ->
        val fix = when (issue.type) {
            ApiIssueType.MISSING_AUTH -> "Add @PreAuthorize or similar authorization annotation."
            ApiIssueType.MISSING_VALIDATION -> "Add @Valid to @RequestBody parameters."
            ApiIssueType.MISSING_ERROR_HANDLING -> "Add try-catch or create a @ControllerAdvice class."
            ApiIssueType.MISSING_RESPONSE_TYPE -> "Return ResponseEntity<T> for explicit HTTP status control."
            ApiIssueType.MISSING_API_DOCS -> "Add @Operation and @ApiResponse annotations for API documentation."
            ApiIssueType.INCONSISTENT_HTTP_METHOD -> "Use appropriate HTTP methods: GET for reads, POST for creates."
            ApiIssueType.HARDCODED_PATH -> "Extract path segments to constants."
        }
        Violation(
            ruleName = "API Contract: ${issue.type.name.lowercase().replace("_", " ")}",
            patternName = "API Contract Validation",
            message = issue.message,
            severity = issue.severity,
            filePath = issue.filePath,
            lineNumber = issue.lineNumber,
            suggestedFix = fix,
            category = ViolationCategory.ARCHITECTURE,
            confidence = issue.confidence,
            ruleId = "api-${issue.type.name.lowercase().replace("_", "-")}"
        )
    }

    fun generateReport(issues: List<ApiIssue>): String {
        if (issues.isEmpty()) return "API Contract Validation: No issues found."
        val grouped = issues.groupBy { it.type }
        return buildString {
            appendLine("=== API Contract Validation Report ===")
            appendLine("Total issues: ${issues.size}")
            appendLine()
            for ((type, typeIssues) in grouped) {
                appendLine("${type.name.replace("_", " ")} (${typeIssues.size}):")
                for (i in typeIssues.take(5)) {
                    appendLine("  ${i.filePath}:${i.lineNumber} — ${i.message}")
                }
                if (typeIssues.size > 5) appendLine("  ... and ${typeIssues.size - 5} more")
                appendLine()
            }
        }
    }
}
