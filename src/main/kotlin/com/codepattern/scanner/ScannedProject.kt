package com.codepattern.scanner

data class ScannedProject(
    val basePath: String,
    val files: List<ScannedFile>
)

data class ScannedFile(
    val relativePath: String,
    val absolutePath: String,
    val language: String,              // detected language (java, kotlin, python, typescript, etc.)
    val imports: List<String>,         // import/require/include statements
    val importLineNumbers: Map<String, Int>, // import -> line number
    val className: String?,            // primary class/type name if applicable
    val methodCount: Int,
    val lineCount: Int,
    val isInterface: Boolean,
    val concreteClassDependencies: List<String> // constructor params / field types that are concrete
)
