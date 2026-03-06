package com.codepattern.analysis

import com.codepattern.models.Violation
import com.codepattern.models.ViolationCategory
import com.codepattern.models.ViolationSeverity
import com.codepattern.scanner.ScannedFile
import com.codepattern.scanner.ScannedProject
import java.io.File

/**
 * Detects OWASP Top 10 and common security vulnerabilities by analyzing source code lines.
 *
 * Supported checks:
 *  1.  SQL Injection (CWE-89)
 *  2.  Cross-Site Scripting / XSS (CWE-79)
 *  3.  Hardcoded Secrets (CWE-798)
 *  4.  Path Traversal (CWE-22)
 *  5.  Command Injection (CWE-78)
 *  6.  Insecure Deserialization (CWE-502)
 *  7.  Weak Cryptography (CWE-327)
 *  8.  Hardcoded IP Addresses (CWE-1051)
 *  9.  Open Redirect (CWE-601)
 * 10.  Missing Authentication (CWE-306)
 * 11.  Insecure Random (CWE-330)
 * 12.  Log Injection (CWE-117)
 * 13.  XML External Entities / XXE (CWE-611)
 * 14.  Sensitive Data Exposure (CWE-532)
 */
object SecurityScanner {

    // ------------------------------------------------------------------ //
    //  Public types                                                       //
    // ------------------------------------------------------------------ //

    enum class SecurityIssueType {
        SQL_INJECTION,
        XSS,
        HARDCODED_SECRET,
        PATH_TRAVERSAL,
        COMMAND_INJECTION,
        INSECURE_DESERIALIZATION,
        WEAK_CRYPTO,
        HARDCODED_IP,
        OPEN_REDIRECT,
        MISSING_AUTH,
        INSECURE_RANDOM,
        LOG_INJECTION,
        XXE,
        SENSITIVE_DATA_EXPOSURE
    }

    data class SecurityIssue(
        val type: SecurityIssueType,
        val filePath: String,
        val lineNumber: Int,
        val line: String,
        val message: String,
        val severity: ViolationSeverity,
        val cweId: String,
        val owaspCategory: String,
        val confidence: Double,
        val suggestedFix: String
    )

    // ------------------------------------------------------------------ //
    //  Metadata per issue type                                            //
    // ------------------------------------------------------------------ //

    private data class IssueMetadata(
        val cweId: String,
        val owaspCategory: String,
        val defaultSeverity: ViolationSeverity
    )

    private val metadata = mapOf(
        SecurityIssueType.SQL_INJECTION to IssueMetadata("CWE-89", "A03:2021-Injection", ViolationSeverity.ERROR),
        SecurityIssueType.XSS to IssueMetadata("CWE-79", "A03:2021-Injection", ViolationSeverity.ERROR),
        SecurityIssueType.HARDCODED_SECRET to IssueMetadata("CWE-798", "A07:2021-Identification and Authentication Failures", ViolationSeverity.ERROR),
        SecurityIssueType.PATH_TRAVERSAL to IssueMetadata("CWE-22", "A01:2021-Broken Access Control", ViolationSeverity.ERROR),
        SecurityIssueType.COMMAND_INJECTION to IssueMetadata("CWE-78", "A03:2021-Injection", ViolationSeverity.ERROR),
        SecurityIssueType.INSECURE_DESERIALIZATION to IssueMetadata("CWE-502", "A08:2021-Software and Data Integrity Failures", ViolationSeverity.ERROR),
        SecurityIssueType.WEAK_CRYPTO to IssueMetadata("CWE-327", "A02:2021-Cryptographic Failures", ViolationSeverity.WARNING),
        SecurityIssueType.HARDCODED_IP to IssueMetadata("CWE-1051", "A05:2021-Security Misconfiguration", ViolationSeverity.WARNING),
        SecurityIssueType.OPEN_REDIRECT to IssueMetadata("CWE-601", "A01:2021-Broken Access Control", ViolationSeverity.ERROR),
        SecurityIssueType.MISSING_AUTH to IssueMetadata("CWE-306", "A07:2021-Identification and Authentication Failures", ViolationSeverity.WARNING),
        SecurityIssueType.INSECURE_RANDOM to IssueMetadata("CWE-330", "A02:2021-Cryptographic Failures", ViolationSeverity.WARNING),
        SecurityIssueType.LOG_INJECTION to IssueMetadata("CWE-117", "A09:2021-Security Logging and Monitoring Failures", ViolationSeverity.WARNING),
        SecurityIssueType.XXE to IssueMetadata("CWE-611", "A05:2021-Security Misconfiguration", ViolationSeverity.ERROR),
        SecurityIssueType.SENSITIVE_DATA_EXPOSURE to IssueMetadata("CWE-532", "A04:2021-Insecure Design", ViolationSeverity.ERROR)
    )

    // ------------------------------------------------------------------ //
    //  Public API                                                         //
    // ------------------------------------------------------------------ //

    /**
     * Scans every file in [project], reading source lines from disk relative to [basePath].
     */
    fun analyze(project: ScannedProject, basePath: String): List<SecurityIssue> {
        val issues = mutableListOf<SecurityIssue>()
        for (file in project.files) {
            try {
                val lines = File("$basePath/${file.relativePath}").readLines()
                issues += analyzeFile(file, lines)
            } catch (_: Exception) {
                // skip unreadable files
            }
        }
        return issues
    }

    /**
     * Scans a single [file] given its source [lines].
     */
    fun analyzeFile(file: ScannedFile, lines: List<String>): List<SecurityIssue> {
        val issues = mutableListOf<SecurityIssue>()
        issues += detectSqlInjection(file, lines)
        issues += detectXss(file, lines)
        issues += detectHardcodedSecrets(file, lines)
        issues += detectPathTraversal(file, lines)
        issues += detectCommandInjection(file, lines)
        issues += detectInsecureDeserialization(file, lines)
        issues += detectWeakCrypto(file, lines)
        issues += detectHardcodedIp(file, lines)
        issues += detectOpenRedirect(file, lines)
        issues += detectMissingAuth(file, lines)
        issues += detectInsecureRandom(file, lines)
        issues += detectLogInjection(file, lines)
        issues += detectXxe(file, lines)
        issues += detectSensitiveDataExposure(file, lines)
        return issues
    }

    /**
     * Converts [SecurityIssue] instances to the project-standard [Violation] type.
     */
    fun toViolations(issues: List<SecurityIssue>): List<Violation> = issues.map { issue ->
        Violation(
            ruleName = "SecurityScanner",
            patternName = "security",
            message = "[${issue.cweId}] [${issue.owaspCategory}] ${issue.message}",
            severity = issue.severity,
            filePath = issue.filePath,
            lineNumber = issue.lineNumber,
            suggestedFix = issue.suggestedFix,
            category = ViolationCategory.SECURITY,
            confidence = issue.confidence,
            ruleId = "security-${issue.type.name.lowercase().replace("_", "-")}"
        )
    }

    /**
     * Produces a human-readable security report from a list of [issues].
     */
    fun generateReport(issues: List<SecurityIssue>): String = buildString {
        appendLine("=== Security Vulnerability Report ===")
        appendLine()

        if (issues.isEmpty()) {
            appendLine("No security issues detected.")
            return@buildString
        }

        // Summary table by type
        appendLine("--- Summary by Issue Type ---")
        appendLine(String.format("%-30s %6s %6s %6s %6s", "Issue Type", "ERROR", "WARN", "INFO", "Total"))
        appendLine("-".repeat(60))
        val byType = issues.groupBy { it.type }
        for (type in SecurityIssueType.entries) {
            val group = byType[type] ?: continue
            val error = group.count { it.severity == ViolationSeverity.ERROR }
            val warn = group.count { it.severity == ViolationSeverity.WARNING }
            val info = group.count { it.severity == ViolationSeverity.INFO }
            appendLine(String.format("%-30s %6d %6d %6d %6d", type.name, error, warn, info, group.size))
        }
        appendLine("-".repeat(60))
        val totalError = issues.count { it.severity == ViolationSeverity.ERROR }
        val totalWarn = issues.count { it.severity == ViolationSeverity.WARNING }
        val totalInfo = issues.count { it.severity == ViolationSeverity.INFO }
        appendLine(String.format("%-30s %6d %6d %6d %6d", "TOTAL", totalError, totalWarn, totalInfo, issues.size))
        appendLine()

        // Summary table by severity
        appendLine("--- Summary by Severity ---")
        appendLine("  ERROR:   $totalError")
        appendLine("  WARNING: $totalWarn")
        appendLine("  INFO:    $totalInfo")
        appendLine()

        // Detailed listing
        appendLine("--- Detailed Findings ---")
        for ((index, issue) in issues.withIndex()) {
            appendLine()
            appendLine("[${index + 1}] ${issue.type.name} (${issue.severity})")
            appendLine("    File:       ${issue.filePath}:${issue.lineNumber}")
            appendLine("    CWE:        ${issue.cweId}")
            appendLine("    OWASP:      ${issue.owaspCategory}")
            appendLine("    Confidence: ${"%.0f".format(issue.confidence * 100)}%")
            appendLine("    Message:    ${issue.message}")
            appendLine("    Line:       ${issue.line.trim()}")
            appendLine("    Fix:        ${issue.suggestedFix}")
        }
    }

    // ------------------------------------------------------------------ //
    //  Private helpers                                                     //
    // ------------------------------------------------------------------ //

    private fun isTestFile(file: ScannedFile): Boolean {
        val path = file.relativePath.lowercase()
        return path.contains("/test/") || path.contains("/tests/") ||
            path.contains("test/") || path.endsWith("test.kt") ||
            path.endsWith("test.java") || path.endsWith(".spec.ts") ||
            path.endsWith(".test.ts") || path.endsWith("_test.go") ||
            path.endsWith("_test.py")
    }

    private fun issue(
        type: SecurityIssueType,
        file: ScannedFile,
        lineNumber: Int,
        line: String,
        message: String,
        confidence: Double,
        suggestedFix: String,
        severityOverride: ViolationSeverity? = null
    ): SecurityIssue {
        val meta = metadata.getValue(type)
        return SecurityIssue(
            type = type,
            filePath = file.relativePath,
            lineNumber = lineNumber,
            line = line,
            message = message,
            severity = severityOverride ?: meta.defaultSeverity,
            cweId = meta.cweId,
            owaspCategory = meta.owaspCategory,
            confidence = confidence,
            suggestedFix = suggestedFix
        )
    }

    // ------------------------------------------------------------------ //
    //  1. SQL Injection                                                    //
    // ------------------------------------------------------------------ //

    private val sqlKeywords = Regex(
        """(?i)\b(?:SELECT|INSERT|UPDATE|DELETE|DROP|CREATE|ALTER|EXEC|EXECUTE|UNION)\b"""
    )
    private val stringConcatInSql = Regex(
        """(?i)(?:"[^"]*(?:SELECT|INSERT|UPDATE|DELETE|WHERE|FROM|SET)\b[^"]*"\s*\+)|""" +
            """(?:\+\s*"[^"]*(?:SELECT|INSERT|UPDATE|DELETE|WHERE|FROM|SET)\b)"""
    )
    private val stringInterpolationInSql = Regex(
        """(?i)(?:"[^"]*(?:SELECT|INSERT|UPDATE|DELETE|WHERE|FROM|SET)[^"]*\$\{)|""" +
            """(?:\$"[^"]*(?:SELECT|INSERT|UPDATE|DELETE|WHERE|FROM|SET))"""
    )
    private val parameterizedQueryIndicators = Regex(
        """(?i)(?:PreparedStatement|@Query\s*\(.*[:?]\w+|setString|setInt|setLong|setParameter|\?\s*,)"""
    )

    private fun detectSqlInjection(file: ScannedFile, lines: List<String>): List<SecurityIssue> {
        val issues = mutableListOf<SecurityIssue>()
        for ((idx, line) in lines.withIndex()) {
            if (!sqlKeywords.containsMatchIn(line)) continue
            if (parameterizedQueryIndicators.containsMatchIn(line)) continue

            val lineNum = idx + 1
            if (stringConcatInSql.containsMatchIn(line)) {
                issues += issue(
                    type = SecurityIssueType.SQL_INJECTION,
                    file = file,
                    lineNumber = lineNum,
                    line = line,
                    message = "String concatenation in SQL query detected. This may allow SQL injection.",
                    confidence = 0.9,
                    suggestedFix = "Use parameterized queries (PreparedStatement, named parameters, or an ORM) instead of string concatenation."
                )
            } else if (stringInterpolationInSql.containsMatchIn(line)) {
                issues += issue(
                    type = SecurityIssueType.SQL_INJECTION,
                    file = file,
                    lineNumber = lineNum,
                    line = line,
                    message = "String interpolation in SQL query detected. This may allow SQL injection.",
                    confidence = 0.9,
                    suggestedFix = "Use parameterized queries instead of string interpolation in SQL statements."
                )
            } else if (line.contains("+") && sqlKeywords.containsMatchIn(line)) {
                // Weaker heuristic: any concatenation on a line containing SQL keywords
                val trimmed = line.trim()
                if (trimmed.contains("\"") && trimmed.contains("+")) {
                    issues += issue(
                        type = SecurityIssueType.SQL_INJECTION,
                        file = file,
                        lineNumber = lineNum,
                        line = line,
                        message = "Possible string concatenation near SQL keyword. Verify query is parameterized.",
                        confidence = 0.5,
                        suggestedFix = "Use parameterized queries to prevent SQL injection."
                    )
                }
            }
        }
        return issues
    }

    // ------------------------------------------------------------------ //
    //  2. XSS                                                              //
    // ------------------------------------------------------------------ //

    private val xssPatterns = listOf(
        Regex("""innerHTML\s*=""") to "Assignment to innerHTML can lead to XSS if the value is not sanitized.",
        Regex("""document\.write\s*\(""") to "document.write() can introduce XSS vulnerabilities.",
        Regex("""\.html\s*\((?!.*sanitize)""") to "jQuery .html() can introduce XSS if the value is not sanitized.",
        Regex("""outerHTML\s*=""") to "Assignment to outerHTML can lead to XSS.",
        Regex("""v-html\s*=""") to "Vue v-html directive renders raw HTML and can lead to XSS.",
        Regex("""dangerouslySetInnerHTML""") to "React dangerouslySetInnerHTML can introduce XSS vulnerabilities."
    )

    private fun detectXss(file: ScannedFile, lines: List<String>): List<SecurityIssue> {
        val issues = mutableListOf<SecurityIssue>()
        for ((idx, line) in lines.withIndex()) {
            for ((pattern, message) in xssPatterns) {
                if (pattern.containsMatchIn(line)) {
                    issues += issue(
                        type = SecurityIssueType.XSS,
                        file = file,
                        lineNumber = idx + 1,
                        line = line,
                        message = message,
                        confidence = 0.7,
                        suggestedFix = "Sanitize all user input before inserting into the DOM. Use textContent, safe templating, or a sanitization library."
                    )
                    break // one XSS finding per line
                }
            }
        }
        return issues
    }

    // ------------------------------------------------------------------ //
    //  3. Hardcoded Secrets                                                //
    // ------------------------------------------------------------------ //

    private val secretPatterns = listOf(
        Regex("""(?i)(?:password|passwd|pwd)\s*=\s*"[^"]{3,}"""") to "Hardcoded password detected.",
        Regex("""(?i)api[_-]?key\s*=\s*"[^"]{3,}"""") to "Hardcoded API key detected.",
        Regex("""(?i)(?:secret|client[_-]?secret)\s*=\s*"[^"]{3,}"""") to "Hardcoded secret detected.",
        Regex("""(?i)(?:token|auth[_-]?token|access[_-]?token)\s*=\s*"[^"]{3,}"""") to "Hardcoded token detected.",
        Regex("""(?:A3T[A-Z0-9]|AKIA|AGPA|AIDA|AROA|AIPA|ANPA|ANVA|ASIA)[A-Z0-9]{16}""") to "Possible AWS access key detected.",
        Regex("""(?i)private[_-]?key\s*=\s*"[^"]{3,}"""") to "Hardcoded private key detected."
    )
    private val secretFalsePositives = Regex(
        "(?i)(?:\"\"|\"xxx\"|\"TODO\"|\"changeme\"|\"password\"|\"change_me\"|\"your[_-]|\"example|\"placeholder|\"dummy|\"test|\"fake|\"sample|\\{[^}]+}|<[^>]+>)"
    )

    private fun detectHardcodedSecrets(file: ScannedFile, lines: List<String>): List<SecurityIssue> {
        val issues = mutableListOf<SecurityIssue>()
        val isTest = isTestFile(file)
        for ((idx, line) in lines.withIndex()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("#")) continue

            for ((pattern, message) in secretPatterns) {
                if (pattern.containsMatchIn(line)) {
                    // Check for common false positives
                    if (secretFalsePositives.containsMatchIn(line)) continue

                    val confidence = when {
                        isTest -> 0.4
                        line.contains("AKIA") -> 0.9
                        else -> 0.9
                    }
                    issues += issue(
                        type = SecurityIssueType.HARDCODED_SECRET,
                        file = file,
                        lineNumber = idx + 1,
                        line = line,
                        message = message,
                        confidence = confidence,
                        suggestedFix = "Move secrets to environment variables, a vault (e.g. HashiCorp Vault), or a secrets manager. Never commit credentials to source control."
                    )
                    break // one finding per line
                }
            }
        }
        return issues
    }

    // ------------------------------------------------------------------ //
    //  4. Path Traversal                                                   //
    // ------------------------------------------------------------------ //

    private val pathTraversalPatterns = listOf(
        Regex("""new\s+File\s*\(\s*(?:request\.|userInput|params|args|input)""") to "User input used directly in File constructor — potential path traversal.",
        Regex("""Paths\.get\s*\(\s*(?:request\.|userInput|params)""") to "User input used directly in Paths.get() — potential path traversal.",
        Regex("""(?:readFile|writeFile|createReadStream|createWriteStream|open)\s*\(\s*(?:req\.|request\.|params\.|args)""") to "User-controlled value used in file system operation — potential path traversal.",
        Regex("""\.\.(/|\\\\)""") to "Literal path traversal sequence detected."
    )

    private fun detectPathTraversal(file: ScannedFile, lines: List<String>): List<SecurityIssue> {
        val issues = mutableListOf<SecurityIssue>()
        for ((idx, line) in lines.withIndex()) {
            for ((pattern, message) in pathTraversalPatterns) {
                if (pattern.containsMatchIn(line)) {
                    val confidence = if (line.contains("request.") || line.contains("req.")) 0.9 else 0.7
                    issues += issue(
                        type = SecurityIssueType.PATH_TRAVERSAL,
                        file = file,
                        lineNumber = idx + 1,
                        line = line,
                        message = message,
                        confidence = confidence,
                        suggestedFix = "Validate and canonicalize file paths. Use an allow-list of permitted directories and reject paths containing '..'."
                    )
                    break
                }
            }
        }
        return issues
    }

    // ------------------------------------------------------------------ //
    //  5. Command Injection                                                //
    // ------------------------------------------------------------------ //

    private val commandInjectionPatterns = listOf(
        Regex("""Runtime\s*\.\s*(?:getRuntime\s*\(\s*\)\s*\.\s*)?exec\s*\(.*\+""") to "String concatenation in Runtime.exec() — potential command injection.",
        Regex("""ProcessBuilder\s*\(.*\+""") to "String concatenation in ProcessBuilder — potential command injection.",
        Regex("""Runtime\s*\.\s*(?:getRuntime\s*\(\s*\)\s*\.\s*)?exec\s*\(\s*(?:request|userInput|params|input)""") to "User input passed directly to Runtime.exec().",
        Regex("""ProcessBuilder\s*\(\s*(?:request|userInput|params|input)""") to "User input passed directly to ProcessBuilder.",
        Regex("""(?:child_process|exec|execSync|spawn)\s*\(.*\+""") to "String concatenation in shell execution — potential command injection."
    )

    private fun detectCommandInjection(file: ScannedFile, lines: List<String>): List<SecurityIssue> {
        val issues = mutableListOf<SecurityIssue>()
        for ((idx, line) in lines.withIndex()) {
            for ((pattern, message) in commandInjectionPatterns) {
                if (pattern.containsMatchIn(line)) {
                    issues += issue(
                        type = SecurityIssueType.COMMAND_INJECTION,
                        file = file,
                        lineNumber = idx + 1,
                        line = line,
                        message = message,
                        confidence = 0.9,
                        suggestedFix = "Avoid passing user input to shell commands. Use parameterized APIs, allow-lists, or input validation."
                    )
                    break
                }
            }
        }
        return issues
    }

    // ------------------------------------------------------------------ //
    //  6. Insecure Deserialization                                         //
    // ------------------------------------------------------------------ //

    private val deserializationPatterns = listOf(
        Regex("""ObjectInputStream""") to "ObjectInputStream usage detected — may allow insecure deserialization of untrusted data.",
        Regex("""\.readObject\s*\(""") to "readObject() call detected — ensure the input stream is from a trusted source.",
        Regex("""XMLDecoder""") to "XMLDecoder usage detected — may allow insecure deserialization.",
        Regex("""(?:readUnshared|readResolve)\s*\(""") to "Java deserialization method detected — verify data source is trusted."
    )

    private fun detectInsecureDeserialization(file: ScannedFile, lines: List<String>): List<SecurityIssue> {
        val issues = mutableListOf<SecurityIssue>()
        for ((idx, line) in lines.withIndex()) {
            for ((pattern, message) in deserializationPatterns) {
                if (pattern.containsMatchIn(line)) {
                    issues += issue(
                        type = SecurityIssueType.INSECURE_DESERIALIZATION,
                        file = file,
                        lineNumber = idx + 1,
                        line = line,
                        message = message,
                        confidence = 0.7,
                        suggestedFix = "Avoid deserializing untrusted data. Use allowlists (ObjectInputFilter), or switch to safe formats like JSON with schema validation."
                    )
                    break
                }
            }
        }
        return issues
    }

    // ------------------------------------------------------------------ //
    //  7. Weak Cryptography                                                //
    // ------------------------------------------------------------------ //

    private val weakCryptoPatterns = listOf(
        Regex("""MessageDigest\s*\.\s*getInstance\s*\(\s*"MD5"""") to "MD5 is cryptographically broken. Do not use for security purposes.",
        Regex("""MessageDigest\s*\.\s*getInstance\s*\(\s*"SHA-?1"""") to "SHA-1 is deprecated for security use. Use SHA-256 or stronger.",
        Regex("""Cipher\s*\.\s*getInstance\s*\(\s*"DES""") to "DES is insecure due to its short key length. Use AES instead.",
        Regex("""Cipher\s*\.\s*getInstance\s*\([^)]*ECB""") to "ECB mode does not provide semantic security. Use CBC, GCM, or another authenticated mode.",
        Regex("""(?i)\bDESede\b""") to "Triple DES (3DES) is deprecated. Use AES-256 instead.",
        Regex("""(?i)\bRC4\b""") to "RC4 is broken. Use AES-GCM or ChaCha20 instead."
    )

    private fun detectWeakCrypto(file: ScannedFile, lines: List<String>): List<SecurityIssue> {
        val issues = mutableListOf<SecurityIssue>()
        for ((idx, line) in lines.withIndex()) {
            for ((pattern, message) in weakCryptoPatterns) {
                if (pattern.containsMatchIn(line)) {
                    issues += issue(
                        type = SecurityIssueType.WEAK_CRYPTO,
                        file = file,
                        lineNumber = idx + 1,
                        line = line,
                        message = message,
                        confidence = 0.9,
                        suggestedFix = "Use strong cryptographic algorithms: AES-256-GCM for encryption, SHA-256+ for hashing, and bcrypt/scrypt/argon2 for passwords."
                    )
                    break
                }
            }
        }
        return issues
    }

    // ------------------------------------------------------------------ //
    //  8. Hardcoded IP Addresses                                           //
    // ------------------------------------------------------------------ //

    private val ipPattern = Regex(
        """(?<![.\d])(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})(?![.\d])"""
    )
    private val localhostIps = setOf("127.0.0.1", "0.0.0.0", "255.255.255.255")
    private val urlWithIpPattern = Regex(
        """(?:https?://)\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}"""
    )

    private fun detectHardcodedIp(file: ScannedFile, lines: List<String>): List<SecurityIssue> {
        val issues = mutableListOf<SecurityIssue>()
        for ((idx, line) in lines.withIndex()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("#")) continue

            for (match in ipPattern.findAll(line)) {
                val ip = match.groupValues[1]
                if (ip in localhostIps) continue
                // Skip version-like patterns (e.g., 1.0.0.0 in dependencies)
                if (ip.startsWith("0.") || ip == "1.0.0.0") continue

                val hasUrl = urlWithIpPattern.containsMatchIn(line)
                issues += issue(
                    type = SecurityIssueType.HARDCODED_IP,
                    file = file,
                    lineNumber = idx + 1,
                    line = line,
                    message = if (hasUrl) "Hardcoded URL with IP address '$ip' detected." else "Hardcoded IP address '$ip' detected.",
                    confidence = if (hasUrl) 0.9 else 0.7,
                    suggestedFix = "Move IP addresses and URLs to configuration files or environment variables."
                )
                break // one finding per line
            }
        }
        return issues
    }

    // ------------------------------------------------------------------ //
    //  9. Open Redirect                                                    //
    // ------------------------------------------------------------------ //

    private val openRedirectPatterns = listOf(
        Regex("""redirect\s*\(\s*request\s*\.\s*getParameter\s*\(""") to "Redirect uses user-supplied parameter directly — open redirect risk.",
        Regex("""sendRedirect\s*\(\s*(?:request\.|params\.|userInput|input)""") to "sendRedirect() with user input — open redirect risk.",
        Regex("""(?:redirect|sendRedirect|forward)\s*\(.*\+.*(?:request|params|getParameter)""") to "Redirect target built with user input — open redirect risk.",
        Regex("""(?:Location|location)\s*[:=]\s*(?:req\.|request\.|params\.)""") to "Location header set from user input — open redirect risk."
    )

    private fun detectOpenRedirect(file: ScannedFile, lines: List<String>): List<SecurityIssue> {
        val issues = mutableListOf<SecurityIssue>()
        for ((idx, line) in lines.withIndex()) {
            for ((pattern, message) in openRedirectPatterns) {
                if (pattern.containsMatchIn(line)) {
                    issues += issue(
                        type = SecurityIssueType.OPEN_REDIRECT,
                        file = file,
                        lineNumber = idx + 1,
                        line = line,
                        message = message,
                        confidence = 0.9,
                        suggestedFix = "Validate redirect targets against an allow-list of trusted URLs. Never redirect to a user-supplied URL directly."
                    )
                    break
                }
            }
        }
        return issues
    }

    // ------------------------------------------------------------------ //
    //  10. Missing Auth                                                    //
    // ------------------------------------------------------------------ //

    private val restControllerAnnotation = Regex("""@(?:RestController|Controller)\b""")
    private val endpointAnnotation = Regex(
        """@(?:GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping|RequestMapping)\b"""
    )
    private val authAnnotations = Regex(
        """@(?:PreAuthorize|Secured|RolesAllowed|Authenticated|RequiresAuthentication|RequiresPermissions|AuthenticationPrincipal|WithMockUser)\b"""
    )

    private fun detectMissingAuth(file: ScannedFile, lines: List<String>): List<SecurityIssue> {
        // Only flag files that are REST controllers
        val fullText = lines.joinToString("\n")
        if (!restControllerAnnotation.containsMatchIn(fullText)) return emptyList()

        // Check if there's a class-level auth annotation
        val classLevelAuth = lines.any { line ->
            val trimmed = line.trim()
            trimmed.startsWith("@") && authAnnotations.containsMatchIn(trimmed) &&
                !endpointAnnotation.containsMatchIn(trimmed)
        }
        if (classLevelAuth) return emptyList()

        val issues = mutableListOf<SecurityIssue>()
        for ((idx, line) in lines.withIndex()) {
            if (!endpointAnnotation.containsMatchIn(line)) continue

            // Look for auth annotation in the preceding 3 lines (method-level annotation)
            val startLookback = (idx - 3).coerceAtLeast(0)
            val hasMethodAuth = (startLookback until idx).any { i ->
                authAnnotations.containsMatchIn(lines[i])
            }
            if (!hasMethodAuth) {
                issues += issue(
                    type = SecurityIssueType.MISSING_AUTH,
                    file = file,
                    lineNumber = idx + 1,
                    line = line,
                    message = "Endpoint mapping without authentication annotation. Ensure this endpoint is intentionally public.",
                    confidence = 0.7,
                    suggestedFix = "Add @PreAuthorize, @Secured, or @RolesAllowed to restrict access, or document why this endpoint is public.",
                    severityOverride = ViolationSeverity.WARNING
                )
            }
        }
        return issues
    }

    // ------------------------------------------------------------------ //
    //  11. Insecure Random                                                 //
    // ------------------------------------------------------------------ //

    private val insecureRandomPattern = Regex("""(?:java\.util\.Random|new\s+Random\s*\()""")
    private val securityContextKeywords = Regex(
        """(?i)(?:token|password|secret|auth|session|nonce|salt|key|otp|csrf|captcha)"""
    )

    private fun detectInsecureRandom(file: ScannedFile, lines: List<String>): List<SecurityIssue> {
        val issues = mutableListOf<SecurityIssue>()
        for ((idx, line) in lines.withIndex()) {
            if (!insecureRandomPattern.containsMatchIn(line)) continue

            // Check surrounding context (current line and nearby lines) for security keywords
            val contextStart = (idx - 5).coerceAtLeast(0)
            val contextEnd = (idx + 5).coerceAtMost(lines.size - 1)
            val context = (contextStart..contextEnd).joinToString(" ") { lines[it] }

            if (securityContextKeywords.containsMatchIn(context)) {
                issues += issue(
                    type = SecurityIssueType.INSECURE_RANDOM,
                    file = file,
                    lineNumber = idx + 1,
                    line = line,
                    message = "java.util.Random used in a security-sensitive context. It is not cryptographically secure.",
                    confidence = 0.9,
                    suggestedFix = "Use java.security.SecureRandom for security-sensitive random number generation (tokens, passwords, keys, etc.)."
                )
            } else {
                issues += issue(
                    type = SecurityIssueType.INSECURE_RANDOM,
                    file = file,
                    lineNumber = idx + 1,
                    line = line,
                    message = "java.util.Random detected. If used for security purposes, replace with SecureRandom.",
                    confidence = 0.5,
                    suggestedFix = "Use java.security.SecureRandom if this random value is used for security (tokens, keys, etc.).",
                    severityOverride = ViolationSeverity.INFO
                )
            }
        }
        return issues
    }

    // ------------------------------------------------------------------ //
    //  12. Log Injection                                                   //
    // ------------------------------------------------------------------ //

    private val logInjectionPatterns = listOf(
        Regex("""(?:log|logger|LOG|LOGGER)\s*\.\s*(?:info|debug|warn|error|trace|fatal)\s*\(\s*(?:request\.|userInput|params\.|input\.|req\.)""")
            to "User input logged directly — may allow log injection or log forging.",
        Regex("""(?:log|logger|LOG|LOGGER)\s*\.\s*(?:info|debug|warn|error|trace|fatal)\s*\(.*\+\s*(?:request|userInput|params|input|req)\b""")
            to "User input concatenated into log statement — may allow log injection."
    )

    private fun detectLogInjection(file: ScannedFile, lines: List<String>): List<SecurityIssue> {
        val issues = mutableListOf<SecurityIssue>()
        for ((idx, line) in lines.withIndex()) {
            for ((pattern, message) in logInjectionPatterns) {
                if (pattern.containsMatchIn(line)) {
                    issues += issue(
                        type = SecurityIssueType.LOG_INJECTION,
                        file = file,
                        lineNumber = idx + 1,
                        line = line,
                        message = message,
                        confidence = 0.7,
                        suggestedFix = "Sanitize user input before logging: strip newlines and control characters, or use structured logging with parameterized messages."
                    )
                    break
                }
            }
        }
        return issues
    }

    // ------------------------------------------------------------------ //
    //  13. XXE                                                             //
    // ------------------------------------------------------------------ //

    private val xmlParserFactories = Regex(
        """(?:DocumentBuilderFactory|SAXParserFactory|XMLInputFactory|TransformerFactory|SchemaFactory)\s*\.\s*newInstance\s*\("""
    )
    private val xxeProtection = Regex(
        """(?:setFeature|setProperty|setAttribute)\s*\(.*(?:FEATURE_SECURE_PROCESSING|disallow-doctype-decl|external-general-entities|external-parameter-entities|XMLConstants\.ACCESS_EXTERNAL)"""
    )

    private fun detectXxe(file: ScannedFile, lines: List<String>): List<SecurityIssue> {
        val issues = mutableListOf<SecurityIssue>()
        for ((idx, line) in lines.withIndex()) {
            if (!xmlParserFactories.containsMatchIn(line)) continue

            // Look ahead up to 10 lines for XXE protection
            val lookAheadEnd = (idx + 10).coerceAtMost(lines.size - 1)
            val hasProtection = ((idx + 1)..lookAheadEnd).any { i ->
                xxeProtection.containsMatchIn(lines[i])
            }
            if (!hasProtection) {
                issues += issue(
                    type = SecurityIssueType.XXE,
                    file = file,
                    lineNumber = idx + 1,
                    line = line,
                    message = "XML parser factory created without disabling external entities. This may allow XXE attacks.",
                    confidence = 0.7,
                    suggestedFix = "Disable external entities: factory.setFeature(\"http://apache.org/xml/features/disallow-doctype-decl\", true) and disable external DTDs/schemas."
                )
            }
        }
        return issues
    }

    // ------------------------------------------------------------------ //
    //  14. Sensitive Data Exposure                                         //
    // ------------------------------------------------------------------ //

    private val sensitiveDataInLog = listOf(
        Regex("""(?:log|logger|LOG|LOGGER)\s*\.\s*(?:info|debug|warn|error|trace|fatal)\s*\(.*(?i)(?:password|passwd|pwd|creditCard|credit_card|ccNumber|ssn|socialSecurity)""")
            to "Sensitive data (password/credit card/SSN) may be written to logs.",
        Regex("""(?:log|logger|LOG|LOGGER)\s*\.\s*(?:info|debug|warn|error|trace|fatal)\s*\(.*(?i)(?:token|secret|apiKey|api_key|private_key|privateKey)""")
            to "Sensitive credential data may be written to logs.",
        Regex("""(?:println|print|printf|System\.out|System\.err|console\.log)\s*\(.*(?i)(?:password|secret|token|apiKey|creditCard|ssn)""")
            to "Sensitive data may be printed to standard output."
    )

    private fun detectSensitiveDataExposure(file: ScannedFile, lines: List<String>): List<SecurityIssue> {
        val issues = mutableListOf<SecurityIssue>()
        for ((idx, line) in lines.withIndex()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("#")) continue

            for ((pattern, message) in sensitiveDataInLog) {
                if (pattern.containsMatchIn(line)) {
                    issues += issue(
                        type = SecurityIssueType.SENSITIVE_DATA_EXPOSURE,
                        file = file,
                        lineNumber = idx + 1,
                        line = line,
                        message = message,
                        confidence = 0.7,
                        suggestedFix = "Never log sensitive data. Mask or redact passwords, tokens, credit card numbers, and PII before logging."
                    )
                    break
                }
            }
        }
        return issues
    }
}
