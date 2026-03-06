package com.codepattern.analysis

import com.codepattern.scanner.ScannedFile
import org.junit.Assert.*
import org.junit.Test

class SecurityScannerTest {

    private fun scannedFile(path: String = "Test.java", lang: String = "java") = ScannedFile(
        relativePath = path, absolutePath = "/test/$path", language = lang,
        imports = emptyList(), importLineNumbers = emptyMap(), className = "Test",
        methodCount = 1, lineCount = 50, isInterface = false, concreteClassDependencies = emptyList()
    )

    @Test
    fun `detects SQL injection via string concatenation`() {
        val lines = listOf(
            "public class UserDao {",
            "    public User findUser(String name) {",
            "        String query = \"SELECT * FROM users WHERE name = '\" + name + \"'\";",
            "        return db.execute(query);",
            "    }",
            "}"
        )
        val issues = SecurityScanner.analyzeFile(scannedFile(), lines)
        assertTrue("Should detect SQL injection", issues.any { it.type == SecurityScanner.SecurityIssueType.SQL_INJECTION })
    }

    @Test
    fun `detects hardcoded password`() {
        val lines = listOf(
            "public class Config {",
            "    private String password = \"super_secret_123\";",
            "}"
        )
        val issues = SecurityScanner.analyzeFile(scannedFile(), lines)
        assertTrue("Should detect hardcoded secret", issues.any { it.type == SecurityScanner.SecurityIssueType.HARDCODED_SECRET })
    }

    @Test
    fun `ignores placeholder passwords`() {
        val lines = listOf(
            "public class Config {",
            "    private String password = \"changeme\";",
            "}"
        )
        val issues = SecurityScanner.analyzeFile(scannedFile(), lines)
        val secrets = issues.filter { it.type == SecurityScanner.SecurityIssueType.HARDCODED_SECRET }
        assertTrue("Should not flag placeholder", secrets.isEmpty() || secrets.all { it.confidence < 0.5 })
    }

    @Test
    fun `detects weak crypto`() {
        val lines = listOf(
            "import java.security.MessageDigest;",
            "public class HashUtil {",
            "    public String hash(String input) {",
            "        MessageDigest md = MessageDigest.getInstance(\"MD5\");",
            "        return new String(md.digest(input.getBytes()));",
            "    }",
            "}"
        )
        val issues = SecurityScanner.analyzeFile(scannedFile(), lines)
        assertTrue("Should detect weak crypto", issues.any { it.type == SecurityScanner.SecurityIssueType.WEAK_CRYPTO })
    }

    @Test
    fun `detects command injection`() {
        val lines = listOf(
            "public class Runner {",
            "    public void exec(String cmd) {",
            "        Runtime.getRuntime().exec(\"ls \" + cmd);",
            "    }",
            "}"
        )
        val issues = SecurityScanner.analyzeFile(scannedFile(), lines)
        assertTrue("Should detect command injection", issues.any { it.type == SecurityScanner.SecurityIssueType.COMMAND_INJECTION })
    }

    @Test
    fun `toViolations converts correctly`() {
        val issues = listOf(
            SecurityScanner.SecurityIssue(
                type = SecurityScanner.SecurityIssueType.SQL_INJECTION,
                filePath = "Dao.java", lineNumber = 5, line = "query + input",
                message = "SQL injection risk", severity = com.codepattern.models.ViolationSeverity.ERROR,
                cweId = "CWE-89", owaspCategory = "A03:2021-Injection",
                confidence = 0.85, suggestedFix = "Use parameterized queries"
            )
        )
        val violations = SecurityScanner.toViolations(issues)
        assertEquals(1, violations.size)
        assertEquals("Dao.java", violations[0].filePath)
        assertTrue(violations[0].message.contains("CWE-89"))
    }

    @Test
    fun `generates report`() {
        val issues = listOf(
            SecurityScanner.SecurityIssue(
                type = SecurityScanner.SecurityIssueType.HARDCODED_SECRET,
                filePath = "Config.java", lineNumber = 3, line = "password = \"abc\"",
                message = "Hardcoded password", severity = com.codepattern.models.ViolationSeverity.ERROR,
                cweId = "CWE-798", owaspCategory = "A07:2021",
                confidence = 0.9, suggestedFix = "Use environment variables"
            )
        )
        val report = SecurityScanner.generateReport(issues)
        assertTrue(report.contains("Security"))
        assertTrue(report.contains("Config.java"))
    }
}
