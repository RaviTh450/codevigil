package com.codepattern.plugin

import com.codepattern.scanner.ProjectScannerService
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.util.Alarm
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages real-time code analysis. When enabled, watches for document changes
 * and re-analyzes files after a short debounce delay, providing immediate
 * inline feedback on pattern violations as you type.
 */
@Service(Service.Level.PROJECT)
class RealTimeAnalysisManager(private val project: Project) : Disposable {

    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val pendingFiles = ConcurrentHashMap<String, Long>()
    private var documentListener: DocumentListener? = null
    private var isActive = false

    /** Debounce delay in ms before re-analyzing after a change */
    private val debounceMs = 800L

    val isEnabled: Boolean get() = isActive

    fun start() {
        if (isActive) return
        isActive = true

        val listener = object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (!isActive) return
                val document = event.document
                val file = FileDocumentManager.getInstance().getFile(document) ?: return
                if (!isSourceFile(file)) return

                scheduleAnalysis(document, file)
            }
        }

        documentListener = listener
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(listener, this)
    }

    fun stop() {
        isActive = false
        alarm.cancelAllRequests()
        pendingFiles.clear()
    }

    fun toggle(): Boolean {
        if (isActive) stop() else start()
        return isActive
    }

    private fun scheduleAnalysis(document: Document, file: VirtualFile) {
        val path = file.path
        val now = System.currentTimeMillis()
        pendingFiles[path] = now

        alarm.cancelAllRequests()
        alarm.addRequest({
            // Only analyze if this is still the latest change for this file
            val lastChange = pendingFiles[path]
            if (lastChange != null && now >= lastChange) {
                pendingFiles.remove(path)
                analyzeFile(document)
            }
        }, debounceMs)
    }

    private fun analyzeFile(document: Document) {
        try {
            ReadAction.run<Throwable> {
                val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document) ?: return@run
                val service = project.getService(ProjectScannerService::class.java) ?: return@run
                if (service.selectedPattern == null) return@run

                // Analyze the file — results are picked up by PatternAnnotator
                service.analyzeFile(psiFile)

                // Request re-highlighting so annotations update
                com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project).restart(psiFile)
            }
        } catch (_: Exception) {
            // Don't crash on analysis errors
        }
    }

    private fun isSourceFile(file: VirtualFile): Boolean {
        val ext = file.extension?.lowercase() ?: return false
        return ext in SOURCE_EXTENSIONS
    }

    override fun dispose() {
        stop()
    }

    companion object {
        private val SOURCE_EXTENSIONS = setOf(
            "java", "kt", "kts", "py", "ts", "tsx", "js", "jsx",
            "cs", "go", "rs", "rb", "php", "swift", "dart",
            "scala", "groovy", "vue", "svelte"
        )

        fun getInstance(project: Project): RealTimeAnalysisManager {
            return project.getService(RealTimeAnalysisManager::class.java)
        }
    }
}
