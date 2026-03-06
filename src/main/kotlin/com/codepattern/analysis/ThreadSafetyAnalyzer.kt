package com.codepattern.analysis

import com.codepattern.models.Violation
import com.codepattern.models.ViolationCategory
import com.codepattern.models.ViolationSeverity
import com.codepattern.scanner.ScannedFile
import com.codepattern.scanner.ScannedProject
import java.io.File

/**
 * Static analysis for thread safety and concurrency problems:
 *
 * 1. Shared mutable state without synchronization
 * 2. Double-checked locking done incorrectly (missing volatile)
 * 3. Non-atomic compound operations (check-then-act)
 * 4. Mutable state in singletons
 * 5. Unsafe lazy initialization
 * 6. Synchronized on non-final fields
 * 7. Thread pool misuse (unbounded thread creation)
 * 8. Race condition patterns
 */
object ThreadSafetyAnalyzer {

    data class ThreadIssue(
        val type: ThreadIssueType,
        val filePath: String,
        val lineNumber: Int,
        val description: String,
        val severity: ViolationSeverity,
        val confidence: Double
    )

    enum class ThreadIssueType {
        SHARED_MUTABLE_STATE,
        MISSING_VOLATILE,
        CHECK_THEN_ACT,
        MUTABLE_SINGLETON,
        UNSAFE_LAZY_INIT,
        SYNC_ON_NONFINAL,
        UNBOUNDED_THREADS,
        NESTED_LOCKS,
        UNSAFE_PUBLICATION,
        COROUTINE_BLOCKING
    }

    fun analyze(project: ScannedProject, basePath: String): List<ThreadIssue> {
        val issues = mutableListOf<ThreadIssue>()
        for (file in project.files) {
            try {
                val lines = File("$basePath/${file.relativePath}").readLines()
                issues += analyzeFile(file, lines)
            } catch (_: Exception) {}
        }
        return issues
    }

    fun analyzeFile(file: ScannedFile, lines: List<String>): List<ThreadIssue> {
        val issues = mutableListOf<ThreadIssue>()

        issues += detectSharedMutableState(file, lines)
        issues += detectMissingVolatile(file, lines)
        issues += detectCheckThenAct(file, lines)
        issues += detectMutableSingleton(file, lines)
        issues += detectSyncOnNonFinal(file, lines)
        issues += detectUnboundedThreads(file, lines)
        issues += detectNestedLocks(file, lines)
        issues += detectCoroutineBlocking(file, lines)

        return issues
    }

    fun toViolations(issues: List<ThreadIssue>): List<Violation> {
        return issues.map { issue ->
            Violation(
                ruleName = "Thread: ${issue.type.name.replace("_", " ").lowercase()
                    .replaceFirstChar { it.uppercase() }}",
                patternName = "Thread Safety Analysis",
                message = issue.description,
                severity = issue.severity,
                filePath = issue.filePath,
                lineNumber = issue.lineNumber,
                suggestedFix = suggestedFixFor(issue.type),
                category = ViolationCategory.THREAD_SAFETY,
                confidence = issue.confidence,
                ruleId = "thread-${issue.type.name.lowercase()}"
            )
        }
    }

    // ── Shared Mutable State ──

    private fun detectSharedMutableState(file: ScannedFile, lines: List<String>): List<ThreadIssue> {
        val issues = mutableListOf<ThreadIssue>()
        val fullText = lines.joinToString("\n")
        val hasThreadUsage = fullText.contains("Thread") || fullText.contains("Runnable") ||
                fullText.contains("Executor") || fullText.contains("coroutine") ||
                fullText.contains("suspend ") || fullText.contains("async") ||
                fullText.contains("synchronized") || fullText.contains("@Synchronized")

        if (!hasThreadUsage) return issues

        for ((i, line) in lines.withIndex()) {
            val trimmed = line.trim()

            // Mutable var fields without volatile/atomic/synchronized access
            val mutableFieldPattern = Regex("""^\s*(?:var|(?:private|public|protected)?\s+\w+)\s+(\w+)\s*[=:]""")
            val match = mutableFieldPattern.find(trimmed)
            if (match != null && !trimmed.contains("@Volatile") && !trimmed.contains("volatile") &&
                !trimmed.contains("Atomic") && !trimmed.contains("val ") &&
                !trimmed.contains("const ") && !trimmed.contains("final ") &&
                !trimmed.startsWith("//") && !trimmed.startsWith("*")) {

                val varName = match.groupValues[1]
                // Check if accessed from synchronized block
                val hasSyncAccess = fullText.contains("synchronized") &&
                        Regex("""synchronized\s*\([^)]*\)\s*\{[^}]*$varName""").containsMatchIn(fullText)
                val hasAtomicWrapper = fullText.contains("Atomic") && fullText.contains(varName)

                if (!hasSyncAccess && !hasAtomicWrapper && !trimmed.contains("private val") &&
                    !varName.startsWith("_") && varName != "it" && varName.length > 1) {
                    // Only report fields (not local vars) - heuristic: not indented much
                    val indentLevel = line.length - line.trimStart().length
                    if (indentLevel <= 8) {
                        issues += ThreadIssue(
                            type = ThreadIssueType.SHARED_MUTABLE_STATE,
                            filePath = file.relativePath,
                            lineNumber = i + 1,
                            description = "Mutable field '$varName' in a class with concurrent access but no synchronization (@Volatile, Atomic*, or synchronized).",
                            severity = ViolationSeverity.WARNING,
                            confidence = 0.60
                        )
                    }
                }
            }
        }

        return issues
    }

    // ── Missing Volatile (Double-Checked Locking) ──

    private fun detectMissingVolatile(file: ScannedFile, lines: List<String>): List<ThreadIssue> {
        val issues = mutableListOf<ThreadIssue>()

        for ((i, line) in lines.withIndex()) {
            val trimmed = line.trim()

            // Detect double-checked locking pattern
            if (trimmed.contains("if (") && trimmed.contains("== null")) {
                // Look ahead for synchronized + another null check
                val context = lines.subList(i, minOf(lines.size, i + 10)).joinToString("\n")
                if (context.contains("synchronized") && Regex("""== null.*synchronized.*== null""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(context)) {
                    // Check if the field is volatile
                    val fieldPattern = Regex("""if\s*\(\s*(\w+)\s*==""")
                    val fieldMatch = fieldPattern.find(trimmed)
                    if (fieldMatch != null) {
                        val fieldName = fieldMatch.groupValues[1]
                        val fullText = lines.joinToString("\n")
                        if (!fullText.contains("@Volatile") || !Regex("""@Volatile\s+.*$fieldName""").containsMatchIn(fullText)) {
                            issues += ThreadIssue(
                                type = ThreadIssueType.MISSING_VOLATILE,
                                filePath = file.relativePath,
                                lineNumber = i + 1,
                                description = "Double-checked locking on '$fieldName' without @Volatile. The JVM may reorder writes, allowing other threads to see a partially-constructed object.",
                                severity = ViolationSeverity.ERROR,
                                confidence = 0.85
                            )
                        }
                    }
                }
            }
        }

        return issues
    }

    // ── Check-Then-Act (TOCTOU) ──

    private fun detectCheckThenAct(file: ScannedFile, lines: List<String>): List<ThreadIssue> {
        val issues = mutableListOf<ThreadIssue>()
        val fullText = lines.joinToString("\n")
        if (!fullText.contains("Thread") && !fullText.contains("synchronized") &&
            !fullText.contains("concurrent") && !fullText.contains("suspend ")) return issues

        for ((i, line) in lines.withIndex()) {
            val trimmed = line.trim()

            // Pattern: if (map.containsKey(x)) { map.get(x) } — should use computeIfAbsent
            if (trimmed.contains(".containsKey(")) {
                val keyPattern = Regex("""(\w+)\.containsKey\((\w+)\)""")
                val match = keyPattern.find(trimmed)
                if (match != null) {
                    val mapName = match.groupValues[1]
                    val context = lines.subList(i, minOf(lines.size, i + 5)).joinToString("\n")
                    if (context.contains("$mapName.get(") || context.contains("$mapName.put(") ||
                        context.contains("$mapName[")) {
                        issues += ThreadIssue(
                            type = ThreadIssueType.CHECK_THEN_ACT,
                            filePath = file.relativePath,
                            lineNumber = i + 1,
                            description = "Check-then-act on '$mapName': containsKey() + get()/put() is not atomic. Another thread can modify the map between the two calls.",
                            severity = ViolationSeverity.WARNING,
                            confidence = 0.75
                        )
                    }
                }
            }

            // Pattern: if (file.exists()) { file.delete() }
            if (trimmed.contains(".exists()") || trimmed.contains(".isFile")) {
                val context = lines.subList(i, minOf(lines.size, i + 3)).joinToString("\n")
                if (context.contains(".delete()") || context.contains(".createNewFile()") ||
                    context.contains(".mkdir")) {
                    issues += ThreadIssue(
                        type = ThreadIssueType.CHECK_THEN_ACT,
                        filePath = file.relativePath,
                        lineNumber = i + 1,
                        description = "TOCTOU race: checking file existence then acting on it is not atomic. The file state can change between check and action.",
                        severity = ViolationSeverity.WARNING,
                        confidence = 0.65
                    )
                }
            }
        }

        return issues
    }

    // ── Mutable Singleton ──

    private fun detectMutableSingleton(file: ScannedFile, lines: List<String>): List<ThreadIssue> {
        val issues = mutableListOf<ThreadIssue>()
        val fullText = lines.joinToString("\n")

        val isSingleton = fullText.contains("object ") || fullText.contains("@Singleton") ||
                fullText.contains("getInstance()") || fullText.contains("INSTANCE")

        if (!isSingleton) return issues

        for ((i, line) in lines.withIndex()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("var ") && !trimmed.contains("@Volatile") &&
                !trimmed.contains("private") && !trimmed.startsWith("//")) {
                val varPattern = Regex("""var\s+(\w+)""")
                val match = varPattern.find(trimmed)
                if (match != null) {
                    issues += ThreadIssue(
                        type = ThreadIssueType.MUTABLE_SINGLETON,
                        filePath = file.relativePath,
                        lineNumber = i + 1,
                        description = "Mutable field '${match.groupValues[1]}' in singleton/object. Multiple threads can access this concurrently without synchronization.",
                        severity = ViolationSeverity.WARNING,
                        confidence = 0.75
                    )
                }
            }
        }

        return issues
    }

    // ── Synchronized on Non-Final Field ──

    private fun detectSyncOnNonFinal(file: ScannedFile, lines: List<String>): List<ThreadIssue> {
        val issues = mutableListOf<ThreadIssue>()

        for ((i, line) in lines.withIndex()) {
            val trimmed = line.trim()
            val syncPattern = Regex("""synchronized\s*\(\s*(\w+)\s*\)""")
            val match = syncPattern.find(trimmed) ?: continue
            val lockObj = match.groupValues[1]

            if (lockObj == "this" || lockObj == "javaClass" || lockObj.all { it.isUpperCase() || it == '_' }) continue

            // Check if lock object is val/final
            val fullText = lines.joinToString("\n")
            val isImmutable = Regex("""(?:val|final)\s+$lockObj\b""").containsMatchIn(fullText)
            if (!isImmutable) {
                issues += ThreadIssue(
                    type = ThreadIssueType.SYNC_ON_NONFINAL,
                    filePath = file.relativePath,
                    lineNumber = i + 1,
                    description = "Synchronizing on non-final field '$lockObj'. If this reference changes, threads will synchronize on different objects — no mutual exclusion.",
                    severity = ViolationSeverity.ERROR,
                    confidence = 0.80
                )
            }
        }

        return issues
    }

    // ── Unbounded Thread Creation ──

    private fun detectUnboundedThreads(file: ScannedFile, lines: List<String>): List<ThreadIssue> {
        val issues = mutableListOf<ThreadIssue>()

        for ((i, line) in lines.withIndex()) {
            val trimmed = line.trim()

            // Detect Thread().start() inside loops or repeated calls
            if ((trimmed.contains("Thread(") || trimmed.contains("Thread {") || trimmed.contains("Thread{")) && trimmed.contains(".start()")) {
                issues += ThreadIssue(
                    type = ThreadIssueType.UNBOUNDED_THREADS,
                    filePath = file.relativePath,
                    lineNumber = i + 1,
                    description = "Direct Thread creation and start. This bypasses thread pooling, potentially creating thousands of OS threads and causing OOM.",
                    severity = ViolationSeverity.WARNING,
                    confidence = 0.75
                )
            }

            // Detect newCachedThreadPool (unbounded)
            if (trimmed.contains("newCachedThreadPool")) {
                issues += ThreadIssue(
                    type = ThreadIssueType.UNBOUNDED_THREADS,
                    filePath = file.relativePath,
                    lineNumber = i + 1,
                    description = "CachedThreadPool creates unlimited threads on demand. Under load, this can exhaust memory. Use newFixedThreadPool or a bounded pool.",
                    severity = ViolationSeverity.WARNING,
                    confidence = 0.80
                )
            }
        }

        return issues
    }

    // ── Nested Locks (Deadlock Risk) ──

    private fun detectNestedLocks(file: ScannedFile, lines: List<String>): List<ThreadIssue> {
        val issues = mutableListOf<ThreadIssue>()
        var syncDepth = 0
        var outerSyncLine = 0

        for ((i, line) in lines.withIndex()) {
            val trimmed = line.trim()

            if (trimmed.contains("synchronized(") || trimmed.contains("synchronized (")) {
                syncDepth++
                if (syncDepth == 1) outerSyncLine = i + 1
                if (syncDepth >= 2) {
                    issues += ThreadIssue(
                        type = ThreadIssueType.NESTED_LOCKS,
                        filePath = file.relativePath,
                        lineNumber = i + 1,
                        description = "Nested synchronized block (outer lock at line $outerSyncLine). If two threads acquire these locks in different order, it causes deadlock.",
                        severity = ViolationSeverity.ERROR,
                        confidence = 0.80
                    )
                }
            }

            // Also detect lock.lock() nesting
            if (trimmed.matches(Regex(""".*\w+\.lock\(\).*"""))) {
                syncDepth++
                if (syncDepth == 1) outerSyncLine = i + 1
                if (syncDepth >= 2) {
                    issues += ThreadIssue(
                        type = ThreadIssueType.NESTED_LOCKS,
                        filePath = file.relativePath,
                        lineNumber = i + 1,
                        description = "Nested lock acquisition (outer lock at line $outerSyncLine). This is a deadlock risk if locks are acquired in inconsistent order.",
                        severity = ViolationSeverity.ERROR,
                        confidence = 0.80
                    )
                }
            }

            if (trimmed == "}" && syncDepth > 0) syncDepth--
            if (trimmed.contains(".unlock()") && syncDepth > 0) syncDepth--
        }

        return issues
    }

    // ── Coroutine Blocking ──

    private fun detectCoroutineBlocking(file: ScannedFile, lines: List<String>): List<ThreadIssue> {
        if (file.language != "kotlin") return emptyList()
        val issues = mutableListOf<ThreadIssue>()
        var inSuspendFun = false

        for ((i, line) in lines.withIndex()) {
            val trimmed = line.trim()

            if (trimmed.contains("suspend fun")) inSuspendFun = true
            if (inSuspendFun && trimmed == "}") inSuspendFun = false

            if (inSuspendFun) {
                val blockingCalls = listOf("Thread.sleep", ".wait()", "BlockingQueue", ".get()",
                    "CountDownLatch", "Semaphore", "synchronized(")
                for (call in blockingCalls) {
                    if (trimmed.contains(call)) {
                        issues += ThreadIssue(
                            type = ThreadIssueType.COROUTINE_BLOCKING,
                            filePath = file.relativePath,
                            lineNumber = i + 1,
                            description = "Blocking call '$call' inside suspend function. This blocks the coroutine dispatcher thread, reducing parallelism and potentially causing deadlocks.",
                            severity = ViolationSeverity.ERROR,
                            confidence = 0.85
                        )
                        break
                    }
                }
            }
        }

        return issues
    }

    private fun suggestedFixFor(type: ThreadIssueType): String {
        return when (type) {
            ThreadIssueType.SHARED_MUTABLE_STATE -> "Use @Volatile, AtomicReference, or synchronized access for fields shared between threads."
            ThreadIssueType.MISSING_VOLATILE -> "Add @Volatile annotation to the field, or use lazy { } / by lazy { } for singleton initialization."
            ThreadIssueType.CHECK_THEN_ACT -> "Use atomic operations: ConcurrentHashMap.computeIfAbsent(), AtomicReference.compareAndSet(), or Files.createFile() with NIO."
            ThreadIssueType.MUTABLE_SINGLETON -> "Make the field val with AtomicReference, or protect access with synchronized/ReentrantLock."
            ThreadIssueType.UNSAFE_LAZY_INIT -> "Use Kotlin's by lazy { } (thread-safe by default) or Java's Holder pattern."
            ThreadIssueType.SYNC_ON_NONFINAL -> "Synchronize on a private val lock = Any() instead of a mutable field."
            ThreadIssueType.UNBOUNDED_THREADS -> "Use Executors.newFixedThreadPool(n) or Kotlin coroutines with a limited dispatcher."
            ThreadIssueType.NESTED_LOCKS -> "Always acquire locks in a consistent global order, or use tryLock() with timeout to detect deadlocks."
            ThreadIssueType.UNSAFE_PUBLICATION -> "Use volatile, AtomicReference, or final fields to ensure safe publication."
            ThreadIssueType.COROUTINE_BLOCKING -> "Use withContext(Dispatchers.IO) { } for blocking calls, or use non-blocking alternatives (delay(), Channel, Mutex)."
        }
    }
}
