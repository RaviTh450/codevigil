package com.codepattern.plugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(
    name = "CodePatternAnalyzerSettings",
    storages = [Storage("CodePatternAnalyzer.xml")]
)
class PatternSettingsState : PersistentStateComponent<PatternSettingsState.State> {

    data class State(
        var selectedPatternName: String = "",
        var enabledPatterns: MutableList<String> = mutableListOf(),
        var excludedPaths: MutableList<String> = mutableListOf(
            "node_modules", ".git", "build", "dist", "out", "target", "__pycache__"
        ),
        var scanOnStartup: Boolean = false,
        var showNotifications: Boolean = true,
        var enableComplexityAnalysis: Boolean = true,
        var enableCodeSmells: Boolean = true,
        var minConfidence: Double = 0.5,  // minimum confidence threshold for displaying violations
        var showConfidenceScores: Boolean = true,
        var enableRealTimeAnalysis: Boolean = true,  // auto-check as you type
        var autoDetectPattern: Boolean = true         // auto-select pattern from .codevigil.yml
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        fun getInstance(): PatternSettingsState {
            return ApplicationManager.getApplication().getService(PatternSettingsState::class.java)
        }
    }
}
