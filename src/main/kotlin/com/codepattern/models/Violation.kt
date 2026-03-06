package com.codepattern.models

enum class ViolationSeverity {
    INFO, WARNING, ERROR;

    fun isAtLeast(threshold: ViolationSeverity): Boolean = this.ordinal >= threshold.ordinal
}

data class Violation(
    val ruleName: String,
    val patternName: String,
    val message: String,
    val severity: ViolationSeverity,
    val filePath: String,
    val lineNumber: Int,
    val suggestedFix: String? = null,
    val category: ViolationCategory = ViolationCategory.ARCHITECTURE,
    val confidence: Double = 1.0,  // 0.0..1.0 — how confident the detection is
    val ruleId: String = ""
)

enum class ViolationCategory {
    ARCHITECTURE,    // layer/dependency violations
    NAMING,          // naming convention issues
    COMPLEXITY,      // cyclomatic, cognitive, method length
    SOLID,           // SOLID principle violations
    CODE_SMELL,      // god class, feature envy, etc.
    COUPLING,        // tight coupling, circular deps
    ORGANIZATION,    // file placement issues
    MEMORY,          // resource leaks, GC pressure, unbounded caches
    THREAD_SAFETY,   // race conditions, deadlocks, shared mutable state
    SECURITY,        // OWASP Top 10, hardcoded secrets, injection flaws
    DEAD_CODE,       // unused imports, unreachable code, empty catch blocks
    DUPLICATION      // copy-paste code, duplicate blocks
}
