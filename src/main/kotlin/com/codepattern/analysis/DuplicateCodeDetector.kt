package com.codepattern.analysis

import com.codepattern.models.*
import com.codepattern.scanner.ScannedFile
import com.codepattern.scanner.ScannedProject
import java.io.File

data class DuplicateBlock(
    val file1Path: String,
    val file1StartLine: Int,
    val file1EndLine: Int,
    val file2Path: String,
    val file2StartLine: Int,
    val file2EndLine: Int,
    val lineCount: Int,
    val similarity: Double
)

data class DuplicateReport(
    val totalDuplicateBlocks: Int,
    val totalDuplicateLines: Int,
    val duplicationPercentage: Double,
    val blocks: List<DuplicateBlock>
)

object DuplicateCodeDetector {

    private val KEYWORDS = setOf(
        // Kotlin
        "fun", "val", "var", "class", "object", "interface", "enum", "sealed",
        "if", "else", "when", "for", "while", "do", "return", "break", "continue",
        "try", "catch", "finally", "throw", "import", "package", "is", "as", "in",
        "null", "true", "false", "this", "super", "it", "override", "open", "abstract",
        "private", "protected", "public", "internal", "companion", "data", "suspend",
        "inline", "noinline", "crossinline", "reified", "typealias", "lateinit", "by",
        "constructor", "init", "annotation", "const", "tailrec", "operator", "infix",
        // Java
        "static", "final", "void", "int", "long", "double", "float", "boolean",
        "char", "byte", "short", "new", "extends", "implements", "instanceof",
        "switch", "case", "default", "synchronized", "volatile", "transient",
        "throws", "native", "assert", "strictfp"
    )

    private val LINE_COMMENT_REGEX = Regex("//.*$")
    private val BLOCK_COMMENT_REGEX = Regex("/\\*.*?\\*/")
    private val STRING_LITERAL_REGEX = Regex("\"\"\"[\\s\\S]*?\"\"\"|\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)'")
    private val NUMBER_LITERAL_REGEX = Regex("\\b\\d+[.\\d]*[fFLlDd]?\\b")
    private val IDENTIFIER_REGEX = Regex("\\b[a-zA-Z_][a-zA-Z0-9_]*\\b")

    /**
     * Analyze a scanned project for duplicate code blocks.
     */
    fun analyze(project: ScannedProject, basePath: String, minLines: Int = 6): DuplicateReport {
        val filesWithContent = project.files.mapNotNull { scannedFile ->
            val file = File(basePath, scannedFile.relativePath)
            if (file.exists() && file.isFile) {
                val lines = file.readLines()
                Pair(scannedFile, lines)
            } else {
                null
            }
        }
        return analyzeFiles(filesWithContent, minLines)
    }

    /**
     * Analyze a list of files (with their line contents) for duplicate code blocks.
     */
    fun analyzeFiles(files: List<Pair<ScannedFile, List<String>>>, minLines: Int = 6): DuplicateReport {
        val hashIndex = HashMap<Long, MutableList<Pair<String, Int>>>()
        val totalLinesByFile = mutableMapOf<String, Int>()

        // Phase 1: Tokenize and build hash windows
        for ((scannedFile, lines) in files) {
            val filePath = scannedFile.absolutePath
            totalLinesByFile[filePath] = lines.size
            val normalizedLines = normalizeLines(lines)

            if (normalizedLines.size < minLines) continue

            for (i in 0..normalizedLines.size - minLines) {
                val window = normalizedLines.subList(i, i + minLines)

                // Skip windows that are all blank after normalization
                if (window.all { it.isBlank() }) continue

                val hash = hashWindow(window)
                hashIndex.getOrPut(hash) { mutableListOf() }
                    .add(Pair(filePath, i + 1)) // 1-based line numbers
            }
        }

        // Phase 2: Find duplicate pairs from hash collisions
        val rawDuplicates = mutableListOf<DuplicateBlock>()
        val seenPairs = mutableSetOf<String>()

        for ((_, locations) in hashIndex) {
            if (locations.size < 2) continue

            for (i in locations.indices) {
                for (j in i + 1 until locations.size) {
                    val (file1, line1) = locations[i]
                    val (file2, line2) = locations[j]

                    // Skip self-overlapping ranges within the same file
                    if (file1 == file2 && kotlin.math.abs(line1 - line2) < minLines) continue

                    val pairKey = if (file1 < file2 || (file1 == file2 && line1 <= line2)) {
                        "$file1:$line1-$file2:$line2"
                    } else {
                        "$file2:$line2-$file1:$line1"
                    }

                    if (pairKey in seenPairs) continue
                    seenPairs.add(pairKey)

                    rawDuplicates.add(
                        DuplicateBlock(
                            file1Path = file1,
                            file1StartLine = line1,
                            file1EndLine = line1 + minLines - 1,
                            file2Path = file2,
                            file2StartLine = line2,
                            file2EndLine = line2 + minLines - 1,
                            lineCount = minLines,
                            similarity = 1.0
                        )
                    )
                }
            }
        }

        // Phase 3: Merge adjacent duplicate blocks
        val merged = mergeAdjacentBlocks(rawDuplicates, minLines)

        // Phase 4: Compute duplication statistics
        val duplicateLineSet = mutableSetOf<String>()
        for (block in merged) {
            for (line in block.file1StartLine..block.file1EndLine) {
                duplicateLineSet.add("${block.file1Path}:$line")
            }
            for (line in block.file2StartLine..block.file2EndLine) {
                duplicateLineSet.add("${block.file2Path}:$line")
            }
        }

        val totalLines = totalLinesByFile.values.sum()
        val duplicationPercentage = if (totalLines > 0) {
            (duplicateLineSet.size.toDouble() / totalLines) * 100.0
        } else {
            0.0
        }

        return DuplicateReport(
            totalDuplicateBlocks = merged.size,
            totalDuplicateLines = duplicateLineSet.size,
            duplicationPercentage = duplicationPercentage,
            blocks = merged.sortedByDescending { it.lineCount }
        )
    }

    /**
     * Convert a duplicate report into a list of standard Violation objects.
     */
    fun toViolations(report: DuplicateReport): List<Violation> {
        return report.blocks.flatMap { block ->
            val severity = when {
                block.lineCount > 20 -> ViolationSeverity.ERROR
                block.lineCount > 10 -> ViolationSeverity.WARNING
                else -> ViolationSeverity.INFO
            }

            val message = "Duplicate code block (${block.lineCount} lines, " +
                "${"%.0f".format(block.similarity * 100)}% similar): " +
                "${block.file1Path}:${block.file1StartLine}-${block.file1EndLine} " +
                "and ${block.file2Path}:${block.file2StartLine}-${block.file2EndLine}"

            listOf(
                Violation(
                    ruleName = "duplicate-code",
                    patternName = "DuplicateCodeDetection",
                    message = message,
                    severity = severity,
                    filePath = block.file1Path,
                    lineNumber = block.file1StartLine,
                    suggestedFix = "Extract common code into a shared method/class",
                    category = ViolationCategory.DUPLICATION,
                    confidence = block.similarity,
                    ruleId = "duplicate-code"
                ),
                Violation(
                    ruleName = "duplicate-code",
                    patternName = "DuplicateCodeDetection",
                    message = message,
                    severity = severity,
                    filePath = block.file2Path,
                    lineNumber = block.file2StartLine,
                    suggestedFix = "Extract common code into a shared method/class",
                    category = ViolationCategory.DUPLICATION,
                    confidence = block.similarity,
                    ruleId = "duplicate-code"
                )
            )
        }
    }

    /**
     * Generate a formatted text report of duplicate code findings.
     */
    fun generateReport(report: DuplicateReport): String {
        val sb = StringBuilder()

        sb.appendLine("=== Duplicate Code Analysis Report ===")
        sb.appendLine()
        sb.appendLine("Summary:")
        sb.appendLine("  Duplicate blocks: ${report.totalDuplicateBlocks}")
        sb.appendLine("  Duplicate lines:  ${report.totalDuplicateLines}")
        sb.appendLine("  Duplication:      ${"%.1f".format(report.duplicationPercentage)}%")
        sb.appendLine()

        if (report.blocks.isEmpty()) {
            sb.appendLine("No duplicate code blocks detected.")
        } else {
            sb.appendLine("Duplicate Blocks:")
            sb.appendLine("-".repeat(80))

            for ((index, block) in report.blocks.withIndex()) {
                val similarityPct = "%.0f".format(block.similarity * 100)
                sb.appendLine(
                    "  ${index + 1}. ${block.file1Path}:${block.file1StartLine}-${block.file1EndLine}" +
                        " \u2194 ${block.file2Path}:${block.file2StartLine}-${block.file2EndLine}" +
                        " (${block.lineCount} lines, ${similarityPct}%)"
                )
            }

            sb.appendLine("-".repeat(80))
        }

        return sb.toString()
    }

    // ---- Internal helpers ----

    /**
     * Normalize all lines in a file, stripping comments, literals, and generalizing identifiers.
     * Returns a list of normalized line strings (preserving line indices for mapping).
     */
    internal fun normalizeLines(lines: List<String>): List<String> {
        val result = mutableListOf<String>()
        var inBlockComment = false

        for (line in lines) {
            var normalized = line

            // Handle multi-line block comments
            if (inBlockComment) {
                val endIdx = normalized.indexOf("*/")
                if (endIdx >= 0) {
                    normalized = normalized.substring(endIdx + 2)
                    inBlockComment = false
                } else {
                    result.add("")
                    continue
                }
            }

            // Remove block comments that start and end on this line
            normalized = BLOCK_COMMENT_REGEX.replace(normalized, " ")

            // Check for block comment that starts but doesn't end on this line
            val blockStart = normalized.indexOf("/*")
            if (blockStart >= 0) {
                normalized = normalized.substring(0, blockStart)
                inBlockComment = true
            }

            // Remove line comments
            normalized = LINE_COMMENT_REGEX.replace(normalized, "")

            // Replace string literals
            normalized = STRING_LITERAL_REGEX.replace(normalized, "STR")

            // Replace number literals
            normalized = NUMBER_LITERAL_REGEX.replace(normalized, "NUM")

            // Replace identifiers (non-keywords) with generic token
            normalized = IDENTIFIER_REGEX.replace(normalized) { match ->
                if (match.value in KEYWORDS) match.value else "ID"
            }

            // Collapse whitespace and trim
            normalized = normalized.replace(Regex("\\s+"), " ").trim()

            result.add(normalized)
        }

        return result
    }

    /**
     * Normalize a single line (convenience wrapper).
     */
    internal fun normalizeLine(line: String): String {
        return normalizeLines(listOf(line)).first()
    }

    /**
     * Compute a hash for a window of consecutive normalized lines.
     */
    private fun hashWindow(window: List<String>): Long {
        val combined = window.joinToString("\n")
        // Simple polynomial rolling hash
        var hash = 0L
        for (ch in combined) {
            hash = hash * 31 + ch.code
        }
        return hash
    }

    /**
     * Merge adjacent or overlapping duplicate blocks that share the same file pair.
     */
    private fun mergeAdjacentBlocks(blocks: List<DuplicateBlock>, minLines: Int): List<DuplicateBlock> {
        if (blocks.isEmpty()) return emptyList()

        // Group by canonical file pair
        val grouped = blocks.groupBy { block ->
            val (f1, f2) = if (block.file1Path <= block.file2Path) {
                block.file1Path to block.file2Path
            } else {
                block.file2Path to block.file1Path
            }
            "$f1<>$f2"
        }

        val merged = mutableListOf<DuplicateBlock>()

        for ((_, group) in grouped) {
            // Normalize direction so file1Path <= file2Path for consistent merging
            val normalized = group.map { block ->
                if (block.file1Path <= block.file2Path) block
                else DuplicateBlock(
                    file1Path = block.file2Path,
                    file1StartLine = block.file2StartLine,
                    file1EndLine = block.file2EndLine,
                    file2Path = block.file1Path,
                    file2StartLine = block.file1StartLine,
                    file2EndLine = block.file1EndLine,
                    lineCount = block.lineCount,
                    similarity = block.similarity
                )
            }.sortedWith(compareBy({ it.file1StartLine }, { it.file2StartLine }))

            val pending = mutableListOf(normalized.first())

            for (i in 1 until normalized.size) {
                val current = normalized[i]
                val last = pending.last()

                // Check if blocks are adjacent or overlapping in both files
                val overlapInFile1 = current.file1StartLine <= last.file1EndLine + 1
                val overlapInFile2 = current.file2StartLine <= last.file2EndLine + 1

                if (overlapInFile1 && overlapInFile2) {
                    // Merge: extend the last block
                    val newFile1End = maxOf(last.file1EndLine, current.file1EndLine)
                    val newFile2End = maxOf(last.file2EndLine, current.file2EndLine)
                    val newLineCount1 = newFile1End - last.file1StartLine + 1
                    val newLineCount2 = newFile2End - last.file2StartLine + 1
                    val longerBlock = maxOf(newLineCount1, newLineCount2)
                    val shorterBlock = minOf(newLineCount1, newLineCount2)
                    val similarity = shorterBlock.toDouble() / longerBlock.toDouble()

                    pending[pending.lastIndex] = DuplicateBlock(
                        file1Path = last.file1Path,
                        file1StartLine = last.file1StartLine,
                        file1EndLine = newFile1End,
                        file2Path = last.file2Path,
                        file2StartLine = last.file2StartLine,
                        file2EndLine = newFile2End,
                        lineCount = maxOf(newLineCount1, newLineCount2),
                        similarity = similarity
                    )
                } else {
                    pending.add(current)
                }
            }

            // Only keep blocks that meet the minimum line threshold
            merged.addAll(pending.filter { it.lineCount >= minLines })
        }

        return merged
    }
}
