package com.codepattern.analysis

import java.io.File

/**
 * Incremental scanning — detects which files changed since the last scan
 * using git diff, and only re-analyzes those files.
 *
 * Falls back to full scan if git is unavailable or the project isn't a git repo.
 */
object IncrementalScanner {

    data class ChangedFiles(
        val modified: List<String>,   // relative paths
        val added: List<String>,
        val deleted: List<String>,
        val isFullScan: Boolean       // true if we couldn't determine changes
    ) {
        val allChanged: List<String> get() = modified + added
        val totalChanges: Int get() = modified.size + added.size + deleted.size
    }

    /**
     * Get list of files changed since the last commit (unstaged + staged + untracked).
     */
    fun getChangedFilesSinceLastCommit(projectPath: String): ChangedFiles {
        return try {
            val process = ProcessBuilder("git", "status", "--porcelain")
                .directory(File(projectPath))
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode != 0) return fullScanFallback()

            parseGitStatus(output)
        } catch (_: Exception) {
            fullScanFallback()
        }
    }

    /**
     * Get list of files changed between two commits/refs (e.g., "main..HEAD").
     */
    fun getChangedFilesBetween(projectPath: String, fromRef: String, toRef: String = "HEAD"): ChangedFiles {
        return try {
            val process = ProcessBuilder("git", "diff", "--name-status", "$fromRef..$toRef")
                .directory(File(projectPath))
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode != 0) return fullScanFallback()

            parseGitDiff(output)
        } catch (_: Exception) {
            fullScanFallback()
        }
    }

    /**
     * Get the last N commits' changed files (useful for baseline comparison).
     */
    fun getChangedFilesInLastNCommits(projectPath: String, n: Int = 1): ChangedFiles {
        return try {
            val process = ProcessBuilder("git", "diff", "--name-status", "HEAD~$n..HEAD")
                .directory(File(projectPath))
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode != 0) return fullScanFallback()

            parseGitDiff(output)
        } catch (_: Exception) {
            fullScanFallback()
        }
    }

    /**
     * Get current git branch name.
     */
    fun getCurrentBranch(projectPath: String): String? {
        return try {
            val process = ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD")
                .directory(File(projectPath))
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()

            if (exitCode == 0) output else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Get short hash of the current commit.
     */
    fun getCurrentCommitHash(projectPath: String): String? {
        return try {
            val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
                .directory(File(projectPath))
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()

            if (exitCode == 0) output else null
        } catch (_: Exception) {
            null
        }
    }

    private fun parseGitStatus(output: String): ChangedFiles {
        val modified = mutableListOf<String>()
        val added = mutableListOf<String>()
        val deleted = mutableListOf<String>()

        for (line in output.lines()) {
            if (line.length < 4) continue
            val status = line.substring(0, 2).trim()
            val path = line.substring(3).trim()

            when {
                status.contains("D") -> deleted.add(path)
                status.contains("A") || status == "??" -> added.add(path)
                else -> modified.add(path)
            }
        }

        return ChangedFiles(
            modified = modified,
            added = added,
            deleted = deleted,
            isFullScan = false
        )
    }

    private fun parseGitDiff(output: String): ChangedFiles {
        val modified = mutableListOf<String>()
        val added = mutableListOf<String>()
        val deleted = mutableListOf<String>()

        for (line in output.lines()) {
            if (line.isBlank()) continue
            val parts = line.split("\t", limit = 2)
            if (parts.size < 2) continue
            val status = parts[0].trim()
            val path = parts[1].trim()

            when (status.first()) {
                'D' -> deleted.add(path)
                'A' -> added.add(path)
                'M', 'R', 'C' -> modified.add(path)
                else -> modified.add(path)
            }
        }

        return ChangedFiles(
            modified = modified,
            added = added,
            deleted = deleted,
            isFullScan = false
        )
    }

    private fun fullScanFallback(): ChangedFiles {
        return ChangedFiles(
            modified = emptyList(),
            added = emptyList(),
            deleted = emptyList(),
            isFullScan = true
        )
    }
}
