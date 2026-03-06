package com.codepattern.plugin

import com.codepattern.models.Violation
import com.codepattern.models.ViolationSeverity
import com.codepattern.scanner.ProjectScannerService
import com.codepattern.scanner.ScannedFile
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel

class PatternToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentFactory = ContentFactory.getInstance()

        // Tab 1: Violations
        val violationsPanel = ViolationsPanel(project)
        val violationsContent = contentFactory.createContent(violationsPanel, "Violations", false)
        toolWindow.contentManager.addContent(violationsContent)

        // Tab 2: Project Structure
        val structurePanel = ProjectStructurePanel(project)
        val structureContent = contentFactory.createContent(structurePanel, "Structure Overview", false)
        toolWindow.contentManager.addContent(structureContent)

        // Tab 3: Metrics Dashboard
        val metricsPanel = MetricsDashboardPanel(project)
        val metricsContent = contentFactory.createContent(metricsPanel, "Metrics", false)
        toolWindow.contentManager.addContent(metricsContent)

        // Tab 4: Dependency Graph
        val graphPanel = DependencyGraphPanel(project)
        val graphContent = contentFactory.createContent(graphPanel, "Dependency Graph", false)
        toolWindow.contentManager.addContent(graphContent)

        // Tab 5: Drift & Fitness
        val driftPanel = DriftFitnessPanel(project)
        val driftContent = contentFactory.createContent(driftPanel, "Drift & Fitness", false)
        toolWindow.contentManager.addContent(driftContent)

        // Listen for scan updates
        val service = project.getService(ProjectScannerService::class.java)
        service.addScanListener {
            ApplicationManager.getApplication().invokeLater {
                violationsPanel.refreshFromService()
                structurePanel.refreshFromService()
                metricsPanel.refreshFromService()
                graphPanel.refreshFromService()
                driftPanel.refreshFromService()
            }
        }
    }
}

// ========== Violations Panel ==========

class ViolationsPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val tableModel = object : DefaultTableModel(
        arrayOf("Severity", "Confidence", "Category", "Pattern", "Rule", "File", "Line", "Message"), 0
    ) {
        override fun isCellEditable(row: Int, column: Int) = false
    }

    private val table = JTable(tableModel)
    private var violations: List<Violation> = emptyList()
    private val statusLabel = JLabel("  No scan results yet")

    init {
        val headerPanel = JPanel(BorderLayout())
        val scanButton = JButton("Scan Project")
        scanButton.addActionListener { runScan() }
        headerPanel.add(scanButton, BorderLayout.WEST)
        headerPanel.add(statusLabel, BorderLayout.CENTER)
        add(headerPanel, BorderLayout.NORTH)

        table.autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        table.columnModel.getColumn(0).preferredWidth = 60
        table.columnModel.getColumn(1).preferredWidth = 50
        table.columnModel.getColumn(2).preferredWidth = 80
        table.columnModel.getColumn(3).preferredWidth = 90
        table.columnModel.getColumn(4).preferredWidth = 130
        table.columnModel.getColumn(5).preferredWidth = 200
        table.columnModel.getColumn(6).preferredWidth = 35
        table.columnModel.getColumn(7).preferredWidth = 350

        table.getColumnModel().getColumn(0).cellRenderer = SeverityCellRenderer()

        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val row = table.selectedRow
                    if (row in violations.indices) {
                        navigateToViolation(violations[row])
                    }
                }
            }
        })

        add(JBScrollPane(table), BorderLayout.CENTER)
    }

    private fun runScan() {
        val service = project.getService(ProjectScannerService::class.java)
        if (service.selectedPattern == null) {
            JOptionPane.showMessageDialog(
                this,
                "No pattern selected. Go to Tools > Code Patterns > Select Pattern first.",
                "No Pattern Selected",
                JOptionPane.WARNING_MESSAGE
            )
            return
        }

        statusLabel.text = "  Scanning..."

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Scanning for Pattern Violations") {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Scanning project files..."
                service.scanAndAnalyze()
            }
        })
    }

    fun refreshFromService() {
        val service = project.getService(ProjectScannerService::class.java)
        violations = service.lastViolations
        refreshTable()
    }

    private fun refreshTable() {
        tableModel.rowCount = 0
        val settings = PatternSettingsState.getInstance()
        val minConfidence = settings.state.minConfidence
        val filtered = violations.filter { it.confidence >= minConfidence }

        for (v in filtered) {
            tableModel.addRow(
                arrayOf(
                    v.severity.name,
                    "${"%.0f".format(v.confidence * 100)}%",
                    v.category.name,
                    v.patternName,
                    v.ruleName,
                    v.filePath,
                    v.lineNumber,
                    v.message
                )
            )
        }
        val errors = filtered.count { it.severity == ViolationSeverity.ERROR }
        val warnings = filtered.count { it.severity == ViolationSeverity.WARNING }
        val infos = filtered.count { it.severity == ViolationSeverity.INFO }
        val healthScore = com.codepattern.report.ReportGenerator.computeHealthScore(
            filtered,
            project.getService(ProjectScannerService::class.java).lastScanResult?.files?.size ?: 0
        )
        statusLabel.text = "  ${filtered.size} violations ($errors errors, $warnings warnings, $infos info) | Health: $healthScore/100"
    }

    private fun navigateToViolation(violation: Violation) {
        val basePath = project.basePath ?: return
        val fullPath = "$basePath/${violation.filePath}"
        val vFile = LocalFileSystem.getInstance().findFileByPath(fullPath) ?: return
        val descriptor = OpenFileDescriptor(project, vFile, violation.lineNumber - 1, 0)
        FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
    }
}

// ========== Project Structure Panel ==========

class ProjectStructurePanel(private val project: Project) : JPanel(BorderLayout()) {

    private val rootNode = DefaultMutableTreeNode("Project Structure")
    private val treeModel = DefaultTreeModel(rootNode)
    private val tree = Tree(treeModel)
    private val summaryArea = JTextArea()

    init {
        tree.cellRenderer = LayerTreeCellRenderer()
        tree.isRootVisible = true

        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
                    val userObj = node.userObject
                    if (userObj is FileNode) {
                        navigateToFile(userObj.absolutePath)
                    }
                }
            }
        })

        val splitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT)

        splitPane.topComponent = JBScrollPane(tree)

        summaryArea.isEditable = false
        summaryArea.font = summaryArea.font.deriveFont(12f)
        summaryArea.border = BorderFactory.createTitledBorder("Architecture Summary")
        splitPane.bottomComponent = JBScrollPane(summaryArea)
        splitPane.resizeWeight = 0.7

        add(splitPane, BorderLayout.CENTER)

        val refreshButton = JButton("Refresh Structure")
        refreshButton.addActionListener { triggerRefresh() }
        val topPanel = JPanel(BorderLayout())
        topPanel.add(refreshButton, BorderLayout.WEST)
        add(topPanel, BorderLayout.NORTH)
    }

    private fun triggerRefresh() {
        val service = project.getService(ProjectScannerService::class.java)
        if (service.selectedPattern == null) {
            JOptionPane.showMessageDialog(
                this,
                "No pattern selected. Go to Tools > Code Patterns > Select Pattern first.",
                "No Pattern Selected",
                JOptionPane.WARNING_MESSAGE
            )
            return
        }

        if (service.lastScanResult == null) {
            ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Scanning Project Structure") {
                override fun run(indicator: ProgressIndicator) {
                    service.scanAndAnalyze()
                }
            })
        } else {
            refreshFromService()
        }
    }

    fun refreshFromService() {
        val service = project.getService(ProjectScannerService::class.java)
        val pattern = service.selectedPattern ?: return
        val classified = service.classifyProjectFiles()
        val violations = service.lastViolations

        rootNode.removeAllChildren()
        rootNode.userObject = "Project Structure (${pattern.name})"

        val violationsByFile = violations.groupBy { it.filePath }

        for (layer in pattern.layers) {
            val files = classified[layer.name] ?: emptyList()
            val layerViolationCount = files.sumOf { f -> violationsByFile[f.relativePath]?.size ?: 0 }
            val layerLabel = buildString {
                append("${layer.name} (${files.size} files)")
                if (layerViolationCount > 0) append(" - $layerViolationCount violations")
            }
            val layerNode = DefaultMutableTreeNode(LayerNode(layer.name, layerLabel, files.size, layerViolationCount))

            for (file in files.sortedBy { it.relativePath }) {
                val fileViolations = violationsByFile[file.relativePath]?.size ?: 0
                val fileLabel = buildString {
                    append(file.relativePath.substringAfterLast("/"))
                    if (fileViolations > 0) append(" ($fileViolations violations)")
                }
                layerNode.add(DefaultMutableTreeNode(FileNode(fileLabel, file.absolutePath, fileViolations)))
            }

            rootNode.add(layerNode)
        }

        // Unassigned files
        val unassigned = classified["Unassigned"] ?: emptyList()
        if (unassigned.isNotEmpty()) {
            val unassignedNode = DefaultMutableTreeNode(
                LayerNode("Unassigned", "Unassigned (${unassigned.size} files)", unassigned.size, 0)
            )
            for (file in unassigned.sortedBy { it.relativePath }) {
                unassignedNode.add(DefaultMutableTreeNode(FileNode(
                    file.relativePath.substringAfterLast("/"), file.absolutePath, 0
                )))
            }
            rootNode.add(unassignedNode)
        }

        treeModel.reload()
        expandAllNodes()
        updateSummary(pattern, classified, violations)
    }

    private fun updateSummary(
        pattern: com.codepattern.models.PatternSpec,
        classified: Map<String, List<ScannedFile>>,
        violations: List<Violation>
    ) {
        val sb = StringBuilder()
        sb.appendLine("Pattern: ${pattern.name}")
        sb.appendLine("Description: ${pattern.description}")
        sb.appendLine()

        val totalFiles = classified.values.sumOf { it.size }
        val assignedFiles = totalFiles - (classified["Unassigned"]?.size ?: 0)
        sb.appendLine("Total files scanned: $totalFiles")
        sb.appendLine("Files assigned to layers: $assignedFiles")
        sb.appendLine("Unassigned files: ${classified["Unassigned"]?.size ?: 0}")
        sb.appendLine("Total violations: ${violations.size}")
        sb.appendLine()

        // Layer dependency diagram
        if (pattern.layers.isNotEmpty()) {
            sb.appendLine("--- Layer Dependencies ---")
            sb.appendLine()
            for (layer in pattern.layers) {
                val deps = layer.allowedDependencies
                if (deps.isEmpty()) {
                    sb.appendLine("  ${layer.name} (no dependencies - innermost layer)")
                } else {
                    sb.appendLine("  ${layer.name} --> ${deps.joinToString(", ")}")
                }
            }
            sb.appendLine()

            // Show actual violations by type
            val violationsByType = violations.groupBy { it.severity }
            sb.appendLine("--- Violation Summary ---")
            sb.appendLine()
            sb.appendLine("  Errors:   ${violationsByType[ViolationSeverity.ERROR]?.size ?: 0}")
            sb.appendLine("  Warnings: ${violationsByType[ViolationSeverity.WARNING]?.size ?: 0}")
            sb.appendLine("  Info:     ${violationsByType[ViolationSeverity.INFO]?.size ?: 0}")

            // Show layer dependency violations specifically
            val depViolations = violations.filter { it.ruleName.contains("Dependency", ignoreCase = true) }
            if (depViolations.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine("--- Dependency Violations ---")
                sb.appendLine()
                for (v in depViolations.take(20)) {
                    sb.appendLine("  [${v.severity}] ${v.filePath}: ${v.message}")
                }
                if (depViolations.size > 20) {
                    sb.appendLine("  ... and ${depViolations.size - 20} more")
                }
            }
        }

        summaryArea.text = sb.toString()
        summaryArea.caretPosition = 0
    }

    private fun navigateToFile(absolutePath: String) {
        val vFile = LocalFileSystem.getInstance().findFileByPath(absolutePath) ?: return
        val descriptor = OpenFileDescriptor(project, vFile, 0, 0)
        FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
    }

    private fun expandAllNodes() {
        var row = 0
        while (row < tree.rowCount) {
            tree.expandRow(row)
            row++
        }
    }
}

// ========== Metrics Dashboard Panel ==========

class MetricsDashboardPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val metricsArea = JTextArea()

    init {
        metricsArea.isEditable = false
        metricsArea.font = metricsArea.font.deriveFont(12f)
        metricsArea.text = "Run a scan to see project metrics."

        val refreshButton = JButton("Refresh Metrics")
        refreshButton.addActionListener { refreshFromService() }
        val topPanel = JPanel(BorderLayout())
        topPanel.add(refreshButton, BorderLayout.WEST)
        add(topPanel, BorderLayout.NORTH)
        add(JBScrollPane(metricsArea), BorderLayout.CENTER)
    }

    fun refreshFromService() {
        val service = project.getService(ProjectScannerService::class.java)
        val scannedProject = service.lastScanResult ?: run {
            metricsArea.text = "No scan data. Run a project scan first."
            return
        }
        val pattern = service.selectedPattern
        val violations = service.lastViolations
        val settings = PatternSettingsState.getInstance()
        val filtered = violations.filter { it.confidence >= settings.state.minConfidence }

        val totalFiles = scannedProject.files.size
        val healthScore = com.codepattern.report.ReportGenerator.computeHealthScore(filtered, totalFiles)

        val sb = StringBuilder()
        sb.appendLine("=== Project Metrics Dashboard ===")
        sb.appendLine()
        sb.appendLine("Health Score: $healthScore / 100  ${healthEmoji(healthScore)}")
        sb.appendLine()

        // File statistics
        sb.appendLine("--- File Statistics ---")
        sb.appendLine("Total files scanned: $totalFiles")
        val langDist = scannedProject.files.groupBy { it.language }.mapValues { it.value.size }
            .toList().sortedByDescending { it.second }
        sb.appendLine("Languages:")
        for ((lang, count) in langDist) {
            val pct = "%.1f".format(count.toDouble() / totalFiles * 100)
            sb.appendLine("  $lang: $count files ($pct%)")
        }
        sb.appendLine()

        val totalLines = scannedProject.files.sumOf { it.lineCount }
        sb.appendLine("Total lines: $totalLines")
        sb.appendLine("Average lines per file: ${if (totalFiles > 0) totalLines / totalFiles else 0}")
        sb.appendLine()

        // Violation summary
        sb.appendLine("--- Violation Summary ---")
        sb.appendLine("Total violations: ${filtered.size}")
        sb.appendLine("  Errors:   ${filtered.count { it.severity == ViolationSeverity.ERROR }}")
        sb.appendLine("  Warnings: ${filtered.count { it.severity == ViolationSeverity.WARNING }}")
        sb.appendLine("  Info:     ${filtered.count { it.severity == ViolationSeverity.INFO }}")
        sb.appendLine()

        // By category
        val byCat = filtered.groupBy { it.category }
        if (byCat.isNotEmpty()) {
            sb.appendLine("By category:")
            for ((cat, vs) in byCat.toList().sortedByDescending { it.second.size }) {
                sb.appendLine("  ${cat.name}: ${vs.size}")
            }
            sb.appendLine()
        }

        // Top violated files
        val byFile = filtered.groupBy { it.filePath }.toList().sortedByDescending { it.second.size }
        if (byFile.isNotEmpty()) {
            sb.appendLine("--- Top Violated Files ---")
            for ((path, vs) in byFile.take(10)) {
                sb.appendLine("  ${vs.size} violations: $path")
            }
            sb.appendLine()
        }

        // Complexity stats
        val methodCounts = scannedProject.files.map { it.methodCount }
        if (methodCounts.isNotEmpty()) {
            sb.appendLine("--- Complexity Overview ---")
            sb.appendLine("Total methods: ${methodCounts.sum()}")
            sb.appendLine("Max methods in a file: ${methodCounts.max()}")
            sb.appendLine("Avg methods per file: ${"%.1f".format(methodCounts.average())}")

            val largeFiles = scannedProject.files.filter { it.lineCount > 300 }
            sb.appendLine("Files > 300 lines: ${largeFiles.size}")
            val bigInterfaces = scannedProject.files.filter { it.isInterface && it.methodCount > 5 }
            sb.appendLine("Large interfaces (>5 methods): ${bigInterfaces.size}")
            sb.appendLine()
        }

        // Pattern info
        if (pattern != null) {
            sb.appendLine("--- Active Pattern: ${pattern.name} ---")
            sb.appendLine("${pattern.description}")
            sb.appendLine("Layers: ${pattern.layers.size}")
            sb.appendLine("Rules: ${pattern.rules.size}")
            for (rule in pattern.rules) {
                sb.appendLine("  [${rule.severity}] ${rule.name}: ${rule.type}")
            }
        }

        metricsArea.text = sb.toString()
        metricsArea.caretPosition = 0
    }

    private fun healthEmoji(score: Int): String {
        return when {
            score >= 90 -> "(Excellent)"
            score >= 70 -> "(Good)"
            score >= 50 -> "(Needs Work)"
            score >= 30 -> "(Poor)"
            else -> "(Critical)"
        }
    }
}

// ========== Dependency Graph Panel ==========

class DependencyGraphPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val graphArea = JTextArea()
    private var formatCombo: JComboBox<String>

    init {
        graphArea.isEditable = false
        graphArea.font = java.awt.Font("Monospaced", java.awt.Font.PLAIN, 12)
        graphArea.text = "Run a scan to see the dependency graph."

        formatCombo = JComboBox(arrayOf("ASCII", "Mermaid", "DOT (Graphviz)"))
        formatCombo.addActionListener { refreshFromService() }

        val copyButton = JButton("Copy to Clipboard")
        copyButton.addActionListener {
            val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
            clipboard.setContents(java.awt.datatransfer.StringSelection(graphArea.text), null)
        }

        val topPanel = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT))
        topPanel.add(JLabel("Format:"))
        topPanel.add(formatCombo)
        topPanel.add(copyButton)

        add(topPanel, BorderLayout.NORTH)
        add(JBScrollPane(graphArea), BorderLayout.CENTER)
    }

    fun refreshFromService() {
        val service = project.getService(ProjectScannerService::class.java)
        val pattern = service.selectedPattern ?: return
        val edges = service.lastDependencyGraph

        if (edges == null) {
            graphArea.text = "No graph data. Run a project scan first."
            return
        }

        val format = formatCombo.selectedItem as? String ?: "ASCII"
        graphArea.text = when (format) {
            "Mermaid" -> com.codepattern.analysis.DependencyGraphGenerator.generateMermaid(pattern, edges)
            "DOT (Graphviz)" -> com.codepattern.analysis.DependencyGraphGenerator.generateDot(pattern, edges)
            else -> com.codepattern.analysis.DependencyGraphGenerator.generateAscii(pattern, edges)
        }
        graphArea.caretPosition = 0
    }
}

// ========== Drift & Fitness Panel ==========

class DriftFitnessPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val reportArea = JTextArea()

    init {
        reportArea.isEditable = false
        reportArea.font = java.awt.Font("Monospaced", java.awt.Font.PLAIN, 12)
        reportArea.text = "Run a scan to see drift and fitness reports.\n\nSave a baseline first via Tools > Code Patterns > Save Baseline."

        val topPanel = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT))

        val saveBaselineBtn = JButton("Save Baseline")
        saveBaselineBtn.addActionListener {
            val service = project.getService(ProjectScannerService::class.java)
            if (service.lastScanResult != null && service.selectedPattern != null) {
                service.saveBaseline()
                JOptionPane.showMessageDialog(this, "Baseline saved.", "Baseline", JOptionPane.INFORMATION_MESSAGE)
                refreshFromService()
            } else {
                JOptionPane.showMessageDialog(this, "Run a scan first.", "No Data", JOptionPane.WARNING_MESSAGE)
            }
        }
        topPanel.add(saveBaselineBtn)

        val refreshBtn = JButton("Refresh")
        refreshBtn.addActionListener { refreshFromService() }
        topPanel.add(refreshBtn)

        add(topPanel, BorderLayout.NORTH)
        add(JBScrollPane(reportArea), BorderLayout.CENTER)
    }

    fun refreshFromService() {
        val service = project.getService(ProjectScannerService::class.java)
        val sb = StringBuilder()

        // Fitness functions
        val fitnessResults = service.lastFitnessResults
        if (fitnessResults != null) {
            sb.appendLine(com.codepattern.analysis.FitnessFunction.generateReport(fitnessResults))
            sb.appendLine()
        }

        // Drift report
        val driftReport = service.lastDriftReport
        if (driftReport != null) {
            sb.appendLine(driftReport.summary)
        } else {
            val basePath = project.basePath
            val patternName = service.selectedPattern?.name
            if (basePath != null && patternName != null) {
                val baseline = com.codepattern.analysis.ArchitectureDriftTracker.loadBaseline(basePath, patternName)
                if (baseline != null) {
                    sb.appendLine("Baseline found: ${baseline.timestamp}")
                    sb.appendLine("Baseline health score: ${baseline.healthScore}")
                    sb.appendLine("Baseline violations: ${baseline.totalViolations}")
                    sb.appendLine()
                    sb.appendLine("Run a new scan to see drift comparison.")
                } else {
                    sb.appendLine("No baseline saved yet.")
                    sb.appendLine()
                    sb.appendLine("Click 'Save Baseline' after running a scan to start tracking drift.")
                    sb.appendLine("Future scans will compare against this baseline to detect architecture degradation.")
                }
            }
        }

        // Git info
        val basePath = project.basePath
        if (basePath != null) {
            val branch = com.codepattern.analysis.IncrementalScanner.getCurrentBranch(basePath)
            val commit = com.codepattern.analysis.IncrementalScanner.getCurrentCommitHash(basePath)
            if (branch != null || commit != null) {
                sb.appendLine()
                sb.appendLine("--- Git Info ---")
                if (branch != null) sb.appendLine("Branch: $branch")
                if (commit != null) sb.appendLine("Commit: $commit")
            }
        }

        reportArea.text = if (sb.isEmpty()) "Run a scan to see drift and fitness reports." else sb.toString()
        reportArea.caretPosition = 0
    }
}

// ========== Data classes for tree nodes ==========

data class LayerNode(val name: String, val label: String, val fileCount: Int, val violationCount: Int) {
    override fun toString(): String = label
}

data class FileNode(val label: String, val absolutePath: String, val violationCount: Int) {
    override fun toString(): String = label
}

// ========== Custom renderers ==========

private class SeverityCellRenderer : DefaultTableCellRenderer() {
    override fun getTableCellRendererComponent(
        table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
    ): Component {
        val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
        if (!isSelected) {
            foreground = when (value) {
                "ERROR" -> Color(220, 50, 50)
                "WARNING" -> Color(200, 150, 0)
                else -> Color(100, 100, 200)
            }
        }
        return component
    }
}

private class LayerTreeCellRenderer : DefaultTreeCellRenderer() {
    override fun getTreeCellRendererComponent(
        tree: JTree, value: Any?, sel: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean
    ): Component {
        val component = super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)
        val node = value as? DefaultMutableTreeNode ?: return component
        when (val userObj = node.userObject) {
            is LayerNode -> {
                foreground = if (userObj.violationCount > 0) Color(200, 100, 0) else Color(60, 120, 60)
                if (userObj.name == "Unassigned") foreground = Color(150, 150, 150)
            }
            is FileNode -> {
                if (userObj.violationCount > 0) foreground = Color(200, 100, 0)
            }
        }
        return component
    }
}

