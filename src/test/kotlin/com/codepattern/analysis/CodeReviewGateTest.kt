package com.codepattern.analysis

import com.codepattern.models.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class CodeReviewGateTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    @Test
    fun `clean project gets PASS verdict`() {
        val projectDir = tempDir.newFolder("clean")
        val file = java.io.File(projectDir, "Service.kt")
        file.writeText("""
            class Service {
                fun process(input: String): String {
                    return input.uppercase()
                }
            }
        """.trimIndent())

        val patterns = emptyList<PatternSpec>()
        val result = CodeReviewGate.review(
            projectDir.absolutePath,
            patterns,
            CodeReviewGate.ReviewOptions(checkArchitecture = false)
        )

        assertTrue("Clean project should score >= 80", result.overallScore >= 80)
    }

    @Test
    fun `review result has all categories`() {
        val projectDir = tempDir.newFolder("test")
        val file = java.io.File(projectDir, "App.java")
        file.writeText("""
            public class App {
                public void run() {
                    System.out.println("hello");
                }
            }
        """.trimIndent())

        val result = CodeReviewGate.review(
            projectDir.absolutePath,
            emptyList(),
            CodeReviewGate.ReviewOptions(checkArchitecture = false)
        )

        assertTrue("Should have category results", result.categories.isNotEmpty())
        assertNotNull("Should have timestamp", result.timestamp)
    }

    @Test
    fun `review produces valid JSON`() {
        val projectDir = tempDir.newFolder("json")
        val file = java.io.File(projectDir, "Main.kt")
        file.writeText("""
            fun main() { println("hello") }
        """.trimIndent())

        val result = CodeReviewGate.review(
            projectDir.absolutePath,
            emptyList(),
            CodeReviewGate.ReviewOptions(checkArchitecture = false)
        )

        val json = result.toJson()
        assertTrue("JSON should contain verdict", json.contains("\"verdict\""))
        assertTrue("JSON should contain score", json.contains("\"score\""))
        assertTrue("JSON should contain categories", json.contains("\"categories\""))
    }

    @Test
    fun `review produces readable text`() {
        val projectDir = tempDir.newFolder("text")
        val file = java.io.File(projectDir, "Main.kt")
        file.writeText("fun main() { println(\"hello\") }")

        val result = CodeReviewGate.review(
            projectDir.absolutePath,
            emptyList(),
            CodeReviewGate.ReviewOptions(checkArchitecture = false)
        )

        val text = result.toText()
        assertTrue("Text should contain verdict header", text.contains("CODE REVIEW GATE"))
        assertTrue("Text should contain score", text.contains("/100"))
    }

    @Test
    fun `FAIL verdict for code with errors`() {
        val projectDir = tempDir.newFolder("bad")
        val file = java.io.File(projectDir, "Bad.java")

        // Create a God class with many methods and resource leaks
        val methods = (1..25).joinToString("\n") { "    public void method$it() { System.out.println($it); }" }
        val fields = (1..20).joinToString("\n") { "    private String field$it;" }
        file.writeText("""
            import java.io.*;
            import java.util.*;
            import java.net.*;
            import java.sql.*;
            import javax.swing.*;
            import org.apache.*;
            import org.spring.*;
            import com.google.*;
            import com.fasterxml.*;
            import io.netty.*;
            import reactor.core.*;
            import kotlin.io.*;
            import java.lang.*;
            import java.math.*;
            import java.nio.*;
            import java.security.*;
            public class Bad {
$fields
$methods
                public void leak() {
                    InputStream stream = new FileInputStream("file.txt");
                    stream.read();
                }
            }
        """.trimIndent())

        val result = CodeReviewGate.review(
            projectDir.absolutePath,
            emptyList(),
            CodeReviewGate.ReviewOptions(checkArchitecture = false)
        )

        assertTrue("Should have violations", result.totalViolations > 0)
    }
}
