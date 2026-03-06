package com.codepattern.models

data class CodeMetrics(
    val filePath: String,
    val language: String,
    val lineCount: Int,
    val codeLineCount: Int,
    val commentLineCount: Int,
    val blankLineCount: Int,
    val methodCount: Int,
    val classCount: Int,
    val interfaceCount: Int,
    val importCount: Int,
    val maxCyclomaticComplexity: Int,
    val avgCyclomaticComplexity: Double,
    val maxCognitiveComplexity: Int,
    val maxMethodLength: Int,
    val maxParameterCount: Int,
    val maxNestingDepth: Int,
    val afferentCoupling: Int,   // # of external modules that depend on this
    val efferentCoupling: Int,   // # of external modules this depends on
    val instability: Double      // efferent / (afferent + efferent)
)

data class ProjectMetrics(
    val totalFiles: Int,
    val totalLines: Int,
    val totalCodeLines: Int,
    val totalCommentLines: Int,
    val totalBlankLines: Int,
    val languageDistribution: Map<String, Int>,
    val avgComplexity: Double,
    val maxComplexity: Int,
    val highComplexityFiles: List<String>,
    val fileMetrics: List<CodeMetrics>,
    val healthScore: Int  // 0-100 overall project health
)
