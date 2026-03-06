package com.codepattern.plugin

import com.codepattern.patterns.PatternLoader
import com.intellij.openapi.options.Configurable
import javax.swing.*
import java.awt.BorderLayout
import java.awt.GridLayout

class PatternConfigurable : Configurable {

    private var mainPanel: JPanel? = null
    private var patternComboBox: JComboBox<String>? = null
    private var scanOnStartupCheckBox: JCheckBox? = null
    private var showNotificationsCheckBox: JCheckBox? = null
    private var enableComplexityCheckBox: JCheckBox? = null
    private var enableCodeSmellsCheckBox: JCheckBox? = null
    private var showConfidenceCheckBox: JCheckBox? = null
    private var minConfidenceSpinner: JSpinner? = null
    private var enableRealTimeCheckBox: JCheckBox? = null
    private var autoDetectPatternCheckBox: JCheckBox? = null
    private var excludedPathsArea: JTextArea? = null

    override fun getDisplayName(): String = "CodeVigil"

    override fun createComponent(): JComponent {
        val panel = JPanel(BorderLayout(10, 10))
        val contentPanel = JPanel()
        contentPanel.layout = BoxLayout(contentPanel, BoxLayout.Y_AXIS)

        // -- Pattern selection --
        val topPanel = JPanel(GridLayout(0, 2, 5, 5))
        topPanel.border = BorderFactory.createTitledBorder("Pattern Settings")

        topPanel.add(JLabel("Active Pattern:"))
        val patterns = PatternLoader().loadBuiltInPatterns()
        val patternNames = patterns.map { it.name }.toTypedArray()
        patternComboBox = JComboBox(patternNames)
        topPanel.add(patternComboBox)

        scanOnStartupCheckBox = JCheckBox("Scan project on startup")
        topPanel.add(scanOnStartupCheckBox)
        topPanel.add(JLabel())

        showNotificationsCheckBox = JCheckBox("Show violation notifications")
        topPanel.add(showNotificationsCheckBox)
        topPanel.add(JLabel())

        contentPanel.add(topPanel)

        // -- Analysis settings --
        val analysisPanel = JPanel(GridLayout(0, 2, 5, 5))
        analysisPanel.border = BorderFactory.createTitledBorder("Analysis Settings")

        enableComplexityCheckBox = JCheckBox("Enable complexity analysis")
        analysisPanel.add(enableComplexityCheckBox)
        analysisPanel.add(JLabel())

        enableCodeSmellsCheckBox = JCheckBox("Enable code smell detection")
        analysisPanel.add(enableCodeSmellsCheckBox)
        analysisPanel.add(JLabel())

        showConfidenceCheckBox = JCheckBox("Show confidence scores")
        analysisPanel.add(showConfidenceCheckBox)
        analysisPanel.add(JLabel())

        analysisPanel.add(JLabel("Minimum confidence threshold:"))
        minConfidenceSpinner = JSpinner(SpinnerNumberModel(0.5, 0.0, 1.0, 0.1))
        analysisPanel.add(minConfidenceSpinner)

        enableRealTimeCheckBox = JCheckBox("Enable real-time analysis (check as you type)")
        analysisPanel.add(enableRealTimeCheckBox)
        analysisPanel.add(JLabel())

        autoDetectPatternCheckBox = JCheckBox("Auto-detect pattern from project structure")
        analysisPanel.add(autoDetectPatternCheckBox)
        analysisPanel.add(JLabel())

        contentPanel.add(analysisPanel)

        panel.add(contentPanel, BorderLayout.NORTH)

        // -- Excluded paths --
        val excludePanel = JPanel(BorderLayout(5, 5))
        excludePanel.border = BorderFactory.createTitledBorder("Excluded Paths (one per line)")
        excludedPathsArea = JTextArea(6, 40)
        excludePanel.add(JScrollPane(excludedPathsArea), BorderLayout.CENTER)
        panel.add(excludePanel, BorderLayout.CENTER)

        mainPanel = panel
        reset()
        return panel
    }

    override fun isModified(): Boolean {
        val settings = PatternSettingsState.getInstance()
        return patternComboBox?.selectedItem != settings.state.selectedPatternName ||
                scanOnStartupCheckBox?.isSelected != settings.state.scanOnStartup ||
                showNotificationsCheckBox?.isSelected != settings.state.showNotifications ||
                enableComplexityCheckBox?.isSelected != settings.state.enableComplexityAnalysis ||
                enableCodeSmellsCheckBox?.isSelected != settings.state.enableCodeSmells ||
                showConfidenceCheckBox?.isSelected != settings.state.showConfidenceScores ||
                (minConfidenceSpinner?.value as? Double) != settings.state.minConfidence ||
                enableRealTimeCheckBox?.isSelected != settings.state.enableRealTimeAnalysis ||
                autoDetectPatternCheckBox?.isSelected != settings.state.autoDetectPattern ||
                excludedPathsArea?.text != settings.state.excludedPaths.joinToString("\n")
    }

    override fun apply() {
        val settings = PatternSettingsState.getInstance()
        settings.state.selectedPatternName = patternComboBox?.selectedItem as? String ?: ""
        settings.state.scanOnStartup = scanOnStartupCheckBox?.isSelected ?: false
        settings.state.showNotifications = showNotificationsCheckBox?.isSelected ?: true
        settings.state.enableComplexityAnalysis = enableComplexityCheckBox?.isSelected ?: true
        settings.state.enableCodeSmells = enableCodeSmellsCheckBox?.isSelected ?: true
        settings.state.showConfidenceScores = showConfidenceCheckBox?.isSelected ?: true
        settings.state.minConfidence = (minConfidenceSpinner?.value as? Double) ?: 0.5
        settings.state.enableRealTimeAnalysis = enableRealTimeCheckBox?.isSelected ?: true
        settings.state.autoDetectPattern = autoDetectPatternCheckBox?.isSelected ?: true
        settings.state.excludedPaths = excludedPathsArea?.text?.lines()?.filter { it.isNotBlank() }?.toMutableList()
            ?: mutableListOf()
    }

    override fun reset() {
        val settings = PatternSettingsState.getInstance()
        patternComboBox?.selectedItem = settings.state.selectedPatternName
        scanOnStartupCheckBox?.isSelected = settings.state.scanOnStartup
        showNotificationsCheckBox?.isSelected = settings.state.showNotifications
        enableComplexityCheckBox?.isSelected = settings.state.enableComplexityAnalysis
        enableCodeSmellsCheckBox?.isSelected = settings.state.enableCodeSmells
        showConfidenceCheckBox?.isSelected = settings.state.showConfidenceScores
        minConfidenceSpinner?.value = settings.state.minConfidence
        enableRealTimeCheckBox?.isSelected = settings.state.enableRealTimeAnalysis
        autoDetectPatternCheckBox?.isSelected = settings.state.autoDetectPattern
        excludedPathsArea?.text = settings.state.excludedPaths.joinToString("\n")
    }
}
