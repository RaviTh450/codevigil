package com.codepattern.analysis

import com.codepattern.scanner.ScannedFile
import org.junit.Assert.*
import org.junit.Test

class MemoryLeakDetectorTest {

    private fun scannedFile(path: String = "Test.java", lang: String = "java") = ScannedFile(
        relativePath = path, absolutePath = "/test/$path", language = lang,
        imports = emptyList(), importLineNumbers = emptyMap(), className = "Test",
        methodCount = 1, lineCount = 50, isInterface = false, concreteClassDependencies = emptyList()
    )

    @Test
    fun `detects unclosed resource`() {
        val lines = listOf(
            "public class Test {",
            "    void read() {",
            "        InputStream stream = new FileInputStream(\"file.txt\");",
            "        stream.read();",
            "        // never closed!",
            "    }",
            "}"
        )
        val issues = MemoryLeakDetector.analyzeFile(scannedFile(), lines)
        assertTrue("Should detect unclosed resource", issues.any { it.type == MemoryLeakDetector.MemoryIssueType.RESOURCE_LEAK })
    }

    @Test
    fun `no false positive for try-with-resources`() {
        val lines = listOf(
            "public class Test {",
            "    void read() {",
            "        try (InputStream stream = new FileInputStream(\"file.txt\")) {",
            "            stream.read();",
            "        }",
            "    }",
            "}"
        )
        val issues = MemoryLeakDetector.analyzeFile(scannedFile(), lines)
        val resourceLeaks = issues.filter { it.type == MemoryLeakDetector.MemoryIssueType.RESOURCE_LEAK }
        assertTrue("Should not flag try-with-resources", resourceLeaks.isEmpty())
    }

    @Test
    fun `detects string concatenation in loop`() {
        val lines = listOf(
            "public class Test {",
            "    void build() {",
            "        String result = \"\";",
            "        for (int i = 0; i < 100; i++) {",
            "            result += \"item\" + i;",
            "        }",
            "    }",
            "}"
        )
        val issues = MemoryLeakDetector.analyzeFile(scannedFile(), lines)
        assertTrue("Should detect string concat in loop",
            issues.any { it.type == MemoryLeakDetector.MemoryIssueType.STRING_CONCAT_IN_LOOP })
    }

    @Test
    fun `detects static collection growth`() {
        val lines = listOf(
            "public class EventBus {",
            "    companion object {",
            "        val listeners = mutableListOf<Listener>()",
            "    }",
            "    fun register(l: Listener) {",
            "        listeners.add(l)",
            "    }",
            "}"
        )
        val issues = MemoryLeakDetector.analyzeFile(scannedFile("EventBus.kt", "kotlin"), lines)
        assertTrue("Should detect static collection that only grows",
            issues.any { it.type == MemoryLeakDetector.MemoryIssueType.STATIC_COLLECTION_GROWTH })
    }

    @Test
    fun `detects ThreadLocal without remove`() {
        val lines = listOf(
            "public class RequestContext {",
            "    private static final ThreadLocal<User> currentUser = new ThreadLocal<>();",
            "    public static void set(User u) { currentUser.set(u); }",
            "    public static User get() { return currentUser.get(); }",
            "}"
        )
        val issues = MemoryLeakDetector.analyzeFile(scannedFile(), lines)
        assertTrue("Should detect ThreadLocal leak",
            issues.any { it.type == MemoryLeakDetector.MemoryIssueType.THREAD_LOCAL_LEAK })
    }

    @Test
    fun `converts issues to violations`() {
        val issues = listOf(
            MemoryLeakDetector.MemoryIssue(
                type = MemoryLeakDetector.MemoryIssueType.RESOURCE_LEAK,
                filePath = "Test.java", lineNumber = 5,
                description = "Resource leak", severity = com.codepattern.models.ViolationSeverity.ERROR,
                confidence = 0.80
            )
        )
        val violations = MemoryLeakDetector.toViolations(issues)
        assertEquals(1, violations.size)
        assertEquals("Memory & GC Analysis", violations[0].patternName)
        assertEquals(com.codepattern.models.ViolationCategory.MEMORY, violations[0].category)
    }
}
