package com.codepattern.analysis

import com.codepattern.models.Violation
import com.codepattern.models.ViolationCategory
import com.codepattern.models.ViolationSeverity
import com.codepattern.scanner.ScannedFile
import com.codepattern.scanner.ScannedProject
import java.io.File

/**
 * Static analysis detector for memory-related problems:
 *
 * 1. Resource leaks — streams, connections, readers opened but never closed
 * 2. Static collection growth — static/companion Lists, Maps that only add, never clear
 * 3. Unbounded caches — maps used as caches without eviction/size limit
 * 4. Large allocation hotspots — creating big arrays/collections in loops
 * 5. GC pressure — excessive object creation in hot paths (loops, callbacks)
 * 6. String concatenation in loops — creates garbage on every iteration
 * 7. Listener/callback leaks — registering listeners without unregistering
 */
object MemoryLeakDetector {

    data class MemoryIssue(
        val type: MemoryIssueType,
        val filePath: String,
        val lineNumber: Int,
        val description: String,
        val severity: ViolationSeverity,
        val confidence: Double
    )

    enum class MemoryIssueType {
        RESOURCE_LEAK,
        STATIC_COLLECTION_GROWTH,
        UNBOUNDED_CACHE,
        ALLOCATION_IN_LOOP,
        GC_PRESSURE,
        STRING_CONCAT_IN_LOOP,
        LISTENER_LEAK,
        LARGE_ALLOCATION,
        THREAD_LOCAL_LEAK,
        FINALIZE_USAGE
    }

    fun analyze(project: ScannedProject, basePath: String): List<MemoryIssue> {
        val issues = mutableListOf<MemoryIssue>()
        for (file in project.files) {
            try {
                val lines = File("$basePath/${file.relativePath}").readLines()
                issues += analyzeFile(file, lines)
            } catch (_: Exception) {}
        }
        return issues
    }

    fun analyzeFile(file: ScannedFile, lines: List<String>): List<MemoryIssue> {
        val issues = mutableListOf<MemoryIssue>()

        issues += detectResourceLeaks(file, lines)
        issues += detectStaticCollectionGrowth(file, lines)
        issues += detectUnboundedCaches(file, lines)
        issues += detectAllocationInLoops(file, lines)
        issues += detectStringConcatInLoops(file, lines)
        issues += detectListenerLeaks(file, lines)
        issues += detectGcPressure(file, lines)
        issues += detectThreadLocalLeaks(file, lines)
        issues += detectFinalizeUsage(file, lines)

        return issues
    }

    fun toViolations(issues: List<MemoryIssue>): List<Violation> {
        return issues.map { issue ->
            Violation(
                ruleName = "Memory: ${issue.type.name.replace("_", " ").lowercase()
                    .replaceFirstChar { it.uppercase() }}",
                patternName = "Memory & GC Analysis",
                message = issue.description,
                severity = issue.severity,
                filePath = issue.filePath,
                lineNumber = issue.lineNumber,
                suggestedFix = suggestedFixFor(issue.type),
                category = ViolationCategory.MEMORY,
                confidence = issue.confidence,
                ruleId = "memory-${issue.type.name.lowercase()}"
            )
        }
    }

    // ── Resource Leak Detection ──

    private val RESOURCE_TYPES = listOf(
        "InputStream", "OutputStream", "Reader", "Writer", "BufferedReader", "BufferedWriter",
        "FileInputStream", "FileOutputStream", "FileReader", "FileWriter",
        "Connection", "Statement", "PreparedStatement", "ResultSet",
        "Socket", "ServerSocket", "Channel", "RandomAccessFile",
        "Scanner", "PrintWriter", "DataInputStream", "DataOutputStream",
        "ObjectInputStream", "ObjectOutputStream", "ZipInputStream", "GZIPInputStream",
        "HttpURLConnection", "HttpClient", "CloseableHttpClient", "Response"
    )

    private fun detectResourceLeaks(file: ScannedFile, lines: List<String>): List<MemoryIssue> {
        val issues = mutableListOf<MemoryIssue>()
        val openedResources = mutableMapOf<String, Int>() // varName -> line number
        var inTryWithResources = false
        var inUseBlock = false // Kotlin .use { }

        for ((i, line) in lines.withIndex()) {
            val trimmed = line.trim()
            val lineNum = i + 1

            // Skip comments
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) continue

            // Detect try-with-resources / use blocks
            if (trimmed.contains("try (") || trimmed.contains("try(")) inTryWithResources = true
            if (trimmed.contains(".use {") || trimmed.contains(".use{")) inUseBlock = true

            if (inTryWithResources || inUseBlock) {
                if (trimmed == "}") {
                    inTryWithResources = false
                    inUseBlock = false
                }
                continue
            }

            // Detect resource creation
            for (resType in RESOURCE_TYPES) {
                val createPattern = Regex("""(\w+)\s*=\s*(?:new\s+)?$resType\s*\(""")
                val match = createPattern.find(trimmed)
                if (match != null) {
                    val varName = match.groupValues[1]
                    openedResources[varName] = lineNum
                }

                // Also detect: val x = SomeMethod() that returns a resource (heuristic)
                val returnPattern = Regex("""(\w+)\s*=\s*\w+\.(?:get|open|create|new)$resType\s*\(""")
                val returnMatch = returnPattern.find(trimmed)
                if (returnMatch != null) {
                    openedResources[returnMatch.groupValues[1]] = lineNum
                }
            }

            // Detect close calls
            val closePattern = Regex("""(\w+)\.close\s*\(""")
            val closeMatch = closePattern.find(trimmed)
            if (closeMatch != null) {
                openedResources.remove(closeMatch.groupValues[1])
            }

            // Detect try-finally close (also counts as closing)
            if (trimmed.startsWith("finally")) {
                // Look ahead for close calls in finally block
                for (j in (i + 1)..minOf(i + 10, lines.size - 1)) {
                    val finallyLine = lines[j].trim()
                    val finallyClose = closePattern.find(finallyLine)
                    if (finallyClose != null) {
                        openedResources.remove(finallyClose.groupValues[1])
                    }
                    if (finallyLine == "}") break
                }
            }
        }

        // Report unclosed resources
        for ((varName, lineNum) in openedResources) {
            issues += MemoryIssue(
                type = MemoryIssueType.RESOURCE_LEAK,
                filePath = file.relativePath,
                lineNumber = lineNum,
                description = "Resource '$varName' opened but may not be closed. This can cause memory leaks and file descriptor exhaustion.",
                severity = ViolationSeverity.ERROR,
                confidence = 0.80
            )
        }

        return issues
    }

    // ── Static Collection Growth ──

    private fun detectStaticCollectionGrowth(file: ScannedFile, lines: List<String>): List<MemoryIssue> {
        val issues = mutableListOf<MemoryIssue>()
        val staticCollections = mutableMapOf<String, Int>()
        var hasAdd = mutableSetOf<String>()
        var hasClearOrRemove = mutableSetOf<String>()
        var inStaticBlock = false

        for ((i, line) in lines.withIndex()) {
            val trimmed = line.trim()

            // Track companion/static blocks
            if (trimmed.contains("companion object") || trimmed.contains("static {") || trimmed.contains("static final")) {
                inStaticBlock = true
            }

            // Detect static/companion mutable collections
            val staticPattern = Regex("""(?:static|companion).*(?:val|var|final)\s+(\w+)\s*[=:]\s*(?:mutable(?:List|Map|Set)Of|ArrayList|HashMap|HashSet|LinkedList|ConcurrentHashMap|CopyOnWriteArrayList)\b""")
            val match = staticPattern.find(trimmed)

            // Also detect collections inside companion/static blocks
            val inBlockPattern = Regex("""(?:val|var)\s+(\w+)\s*=\s*(?:mutable(?:List|Map|Set)Of|ArrayList|HashMap|HashSet|LinkedList|ConcurrentHashMap|CopyOnWriteArrayList)\b""")
            val blockMatch = if (inStaticBlock && match == null) inBlockPattern.find(trimmed) else null
            if (match != null) {
                staticCollections[match.groupValues[1]] = i + 1
            } else if (blockMatch != null) {
                staticCollections[blockMatch.groupValues[1]] = i + 1
            }

            // Also detect: private static final List<> = new ArrayList<>()
            val javaStaticPattern = Regex("""static\s+.*(?:List|Map|Set|Collection)<.*>\s+(\w+)\s*=\s*new\s+""")
            val javaMatch = javaStaticPattern.find(trimmed)
            if (javaMatch != null) {
                staticCollections[javaMatch.groupValues[1]] = i + 1
            }

            // Track end of static blocks
            if (inStaticBlock && trimmed == "}" && i > 0) {
                val context = lines.subList(maxOf(0, i - 20), i + 1).joinToString("\n")
                if (context.count { it == '{' } <= context.count { it == '}' }) {
                    inStaticBlock = false
                }
            }

            // Track add/put calls
            for (name in staticCollections.keys) {
                if (trimmed.contains("$name.add(") || trimmed.contains("$name.put(") ||
                    trimmed.contains("$name +=") || trimmed.contains("$name.addAll(")) {
                    hasAdd.add(name)
                }
                if (trimmed.contains("$name.clear()") || trimmed.contains("$name.remove(") ||
                    trimmed.contains("$name.removeAll(") || trimmed.contains("$name.removeIf(")) {
                    hasClearOrRemove.add(name)
                }
            }
        }

        // Report static collections that grow but never shrink
        for ((name, lineNum) in staticCollections) {
            if (name in hasAdd && name !in hasClearOrRemove) {
                issues += MemoryIssue(
                    type = MemoryIssueType.STATIC_COLLECTION_GROWTH,
                    filePath = file.relativePath,
                    lineNumber = lineNum,
                    description = "Static collection '$name' has items added but never removed/cleared. This will grow unbounded and cause heap memory to increase continuously.",
                    severity = ViolationSeverity.ERROR,
                    confidence = 0.85
                )
            }
        }

        return issues
    }

    // ── Unbounded Cache Detection ──

    private fun detectUnboundedCaches(file: ScannedFile, lines: List<String>): List<MemoryIssue> {
        val issues = mutableListOf<MemoryIssue>()

        for ((i, line) in lines.withIndex()) {
            val trimmed = line.trim()

            // Detect maps used as caches without size limit
            val cachePattern = Regex("""(\w*[Cc]ache\w*)\s*[=:]\s*(?:mutable)?(?:Map|HashMap|ConcurrentHashMap|LinkedHashMap)""")
            val match = cachePattern.find(trimmed)
            if (match != null) {
                // Check if there's a size limit in surrounding lines
                val context = lines.subList(maxOf(0, i - 5), minOf(lines.size, i + 20)).joinToString("\n")
                val hasEviction = context.contains("maximumSize") || context.contains("expireAfter") ||
                        context.contains("removeEldest") || context.contains("maxSize") ||
                        context.contains("evict") || context.contains("LRU") ||
                        context.contains("Caffeine") || context.contains("Guava")

                if (!hasEviction) {
                    issues += MemoryIssue(
                        type = MemoryIssueType.UNBOUNDED_CACHE,
                        filePath = file.relativePath,
                        lineNumber = i + 1,
                        description = "Cache '${match.groupValues[1]}' has no eviction policy or size limit. It will grow until OutOfMemoryError.",
                        severity = ViolationSeverity.ERROR,
                        confidence = 0.75
                    )
                }
            }
        }

        return issues
    }

    // ── Allocation in Loops ──

    private fun detectAllocationInLoops(file: ScannedFile, lines: List<String>): List<MemoryIssue> {
        val issues = mutableListOf<MemoryIssue>()
        var loopDepth = 0
        var loopStartLine = 0

        for ((i, line) in lines.withIndex()) {
            val trimmed = line.trim()

            // Track loop entry
            if (trimmed.matches(Regex("""^(?:for|while|do)\s*[\({].*""")) ||
                trimmed.contains(".forEach") || trimmed.contains(".map {") ||
                trimmed.contains(".flatMap") || trimmed.contains(".filter {")) {
                if (loopDepth == 0) loopStartLine = i + 1
                loopDepth++
            }

            if (loopDepth > 0) {
                // Detect large allocations inside loops
                val allocPatterns = listOf(
                    Regex("""new\s+\w+\[(\d+)\]"""),           // new byte[1024]
                    Regex("""(?:Array|ByteArray|IntArray)\((\d+)\)"""),  // ByteArray(4096)
                    Regex("""StringBuilder\s*\("""),
                    Regex("""StringBuffer\s*\("""),
                    Regex("""new\s+(?:ArrayList|HashMap|HashSet|LinkedList)\s*\("""),
                    Regex("""(?:mutableListOf|mutableMapOf|mutableSetOf|arrayListOf)\s*\(""")
                )

                for (pattern in allocPatterns) {
                    if (pattern.containsMatchIn(trimmed)) {
                        issues += MemoryIssue(
                            type = MemoryIssueType.ALLOCATION_IN_LOOP,
                            filePath = file.relativePath,
                            lineNumber = i + 1,
                            description = "Object allocation inside loop (started at line $loopStartLine). Creates GC pressure — consider reusing or allocating outside the loop.",
                            severity = ViolationSeverity.WARNING,
                            confidence = 0.80
                        )
                        break
                    }
                }
            }

            // Track braces for loop depth
            loopDepth += trimmed.count { it == '{' } - trimmed.count { it == '}' }
            if (loopDepth < 0) loopDepth = 0
        }

        return issues
    }

    // ── String Concatenation in Loops ──

    private fun detectStringConcatInLoops(file: ScannedFile, lines: List<String>): List<MemoryIssue> {
        val issues = mutableListOf<MemoryIssue>()
        var inLoop = false
        var braceDepth = 0

        for ((i, line) in lines.withIndex()) {
            val trimmed = line.trim()

            if (trimmed.matches(Regex("""^(?:for|while)\s*[\({].*""")) ||
                trimmed.contains(".forEach")) {
                inLoop = true
                braceDepth = 0
            }

            if (inLoop) {
                braceDepth += trimmed.count { it == '{' } - trimmed.count { it == '}' }

                // Detect string += "..." pattern
                if (trimmed.matches(Regex(""".*\w+\s*\+=\s*".*""")) ||
                    trimmed.matches(Regex(""".*\w+\s*=\s*\w+\s*\+\s*".*"""))) {
                    issues += MemoryIssue(
                        type = MemoryIssueType.STRING_CONCAT_IN_LOOP,
                        filePath = file.relativePath,
                        lineNumber = i + 1,
                        description = "String concatenation inside loop creates a new String object on every iteration, generating garbage. Use StringBuilder instead.",
                        severity = ViolationSeverity.WARNING,
                        confidence = 0.90
                    )
                }

                if (braceDepth <= 0) inLoop = false
            }
        }

        return issues
    }

    // ── Listener/Callback Leaks ──

    private fun detectListenerLeaks(file: ScannedFile, lines: List<String>): List<MemoryIssue> {
        val issues = mutableListOf<MemoryIssue>()
        val registeredListeners = mutableMapOf<String, Int>() // pattern -> lineNum

        val addPatterns = listOf(
            "addListener", "addEventListener", "addObserver", "addCallback",
            "subscribe", "register", "on(", "addHandler", "addPropertyChangeListener",
            "addActionListener", "addChangeListener", "addDocumentListener"
        )
        val removePatterns = listOf(
            "removeListener", "removeEventListener", "removeObserver", "removeCallback",
            "unsubscribe", "unregister", "off(", "removeHandler", "removePropertyChangeListener",
            "removeActionListener", "removeChangeListener", "removeDocumentListener", "dispose"
        )

        val fullText = lines.joinToString("\n")

        for ((i, line) in lines.withIndex()) {
            val trimmed = line.trim()
            for (pattern in addPatterns) {
                if (trimmed.contains(pattern)) {
                    registeredListeners[pattern] = i + 1
                }
            }
        }

        for (pattern in removePatterns) {
            if (fullText.contains(pattern)) {
                // Find corresponding add pattern and remove it
                val addPattern = pattern.replace("remove", "add").replace("unsubscribe", "subscribe")
                    .replace("unregister", "register").replace("off(", "on(").replace("dispose", "")
                registeredListeners.remove(addPattern)
            }
        }

        // Also check if the class implements Disposable/AutoCloseable (framework cleanup)
        val hasCleanup = fullText.contains("Disposable") || fullText.contains("AutoCloseable") ||
                fullText.contains("onDestroy") || fullText.contains("onCleared") ||
                fullText.contains("dealloc") || fullText.contains("componentWillUnmount")

        if (!hasCleanup) {
            for ((pattern, lineNum) in registeredListeners) {
                issues += MemoryIssue(
                    type = MemoryIssueType.LISTENER_LEAK,
                    filePath = file.relativePath,
                    lineNumber = lineNum,
                    description = "Listener registered via '$pattern' without corresponding removal. This prevents garbage collection of the listener and its enclosing object.",
                    severity = ViolationSeverity.WARNING,
                    confidence = 0.70
                )
            }
        }

        return issues
    }

    // ── GC Pressure (excessive short-lived objects) ──

    private fun detectGcPressure(file: ScannedFile, lines: List<String>): List<MemoryIssue> {
        val issues = mutableListOf<MemoryIssue>()
        var inLoop = false
        var loopBraceDepth = 0
        var allocCount = 0
        var loopStartLine = 0

        for ((i, line) in lines.withIndex()) {
            val trimmed = line.trim()

            if (trimmed.matches(Regex("""^(?:for|while)\s*[\({].*""")) ||
                trimmed.contains(".forEach") || trimmed.contains(".map {")) {
                inLoop = true
                loopBraceDepth = 0
                allocCount = 0
                loopStartLine = i + 1
            }

            if (inLoop) {
                loopBraceDepth += trimmed.count { it == '{' } - trimmed.count { it == '}' }

                // Count allocations
                if (trimmed.contains("new ") || trimmed.contains("listOf(") ||
                    trimmed.contains("mapOf(") || trimmed.contains("Pair(") ||
                    trimmed.contains("Triple(") || trimmed.contains("arrayOf(") ||
                    trimmed.matches(Regex(""".*\w+\(.*\)\s*$""")) && trimmed[0].isUpperCase()) {
                    allocCount++
                }

                if (loopBraceDepth <= 0) {
                    if (allocCount >= 5) {
                        issues += MemoryIssue(
                            type = MemoryIssueType.GC_PRESSURE,
                            filePath = file.relativePath,
                            lineNumber = loopStartLine,
                            description = "Loop starting at line $loopStartLine creates ~$allocCount allocations per iteration. This causes significant GC pressure and heap churn.",
                            severity = ViolationSeverity.WARNING,
                            confidence = 0.70
                        )
                    }
                    inLoop = false
                }
            }
        }

        return issues
    }

    // ── ThreadLocal Leaks ──

    private fun detectThreadLocalLeaks(file: ScannedFile, lines: List<String>): List<MemoryIssue> {
        val issues = mutableListOf<MemoryIssue>()
        val fullText = lines.joinToString("\n")
        val hasRemove = fullText.contains(".remove()")

        for ((i, line) in lines.withIndex()) {
            if (line.contains("ThreadLocal") && (line.contains("new ThreadLocal") || line.contains("ThreadLocal<"))) {
                if (!hasRemove) {
                    issues += MemoryIssue(
                        type = MemoryIssueType.THREAD_LOCAL_LEAK,
                        filePath = file.relativePath,
                        lineNumber = i + 1,
                        description = "ThreadLocal created without .remove() call. In thread pool environments, this causes memory leaks as values persist across request boundaries.",
                        severity = ViolationSeverity.ERROR,
                        confidence = 0.85
                    )
                }
            }
        }

        return issues
    }

    // ── finalize() Usage ──

    private fun detectFinalizeUsage(file: ScannedFile, lines: List<String>): List<MemoryIssue> {
        val issues = mutableListOf<MemoryIssue>()

        for ((i, line) in lines.withIndex()) {
            if (line.trim().matches(Regex(""".*(?:protected|override)?\s*fun(?:ction)?\s+finalize\s*\(.*"""))) {
                issues += MemoryIssue(
                    type = MemoryIssueType.FINALIZE_USAGE,
                    filePath = file.relativePath,
                    lineNumber = i + 1,
                    description = "Using finalize() delays garbage collection and can cause memory pressure. Objects with finalizers go through an extra GC cycle. Use AutoCloseable/try-with-resources instead.",
                    severity = ViolationSeverity.WARNING,
                    confidence = 0.95
                )
            }
        }

        return issues
    }

    private fun suggestedFixFor(type: MemoryIssueType): String {
        return when (type) {
            MemoryIssueType.RESOURCE_LEAK -> "Use try-with-resources (Java) or .use { } (Kotlin) to ensure the resource is closed."
            MemoryIssueType.STATIC_COLLECTION_GROWTH -> "Add a cleanup mechanism: periodic clear(), bounded size with eviction, or use WeakReference."
            MemoryIssueType.UNBOUNDED_CACHE -> "Use Caffeine/Guava cache with maximumSize() and expireAfterWrite(), or implement LRU eviction."
            MemoryIssueType.ALLOCATION_IN_LOOP -> "Move allocation outside the loop and reuse the object, or use object pooling for expensive objects."
            MemoryIssueType.GC_PRESSURE -> "Reduce allocations in the loop body: reuse objects, use primitive arrays, or pre-allocate collections."
            MemoryIssueType.STRING_CONCAT_IN_LOOP -> "Use StringBuilder.append() inside the loop instead of string concatenation."
            MemoryIssueType.LISTENER_LEAK -> "Unregister the listener in onDestroy/dispose/close or use WeakReference-based listeners."
            MemoryIssueType.LARGE_ALLOCATION -> "Consider streaming or chunked processing instead of loading everything into memory."
            MemoryIssueType.THREAD_LOCAL_LEAK -> "Always call threadLocal.remove() in a finally block, especially in thread pool / servlet environments."
            MemoryIssueType.FINALIZE_USAGE -> "Implement AutoCloseable and use try-with-resources. Register a Cleaner if you need release semantics."
        }
    }
}
