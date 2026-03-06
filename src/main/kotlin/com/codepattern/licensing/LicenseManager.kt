package com.codepattern.licensing

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

enum class LicenseTier {
    FREE  // Open source — all features available
}

data class LicenseInfo(
    val tier: LicenseTier,
    val email: String,
    val expiresAt: java.time.Instant?,
    val maxProjects: Int,
    val features: Set<String>
)

/**
 * License manager — open source edition.
 * All features are available to everyone. This class exists for API compatibility.
 */
@State(
    name = "CodePatternAnalyzerLicense",
    storages = [Storage("CodePatternAnalyzerLicense.xml")]
)
class LicenseManager : PersistentStateComponent<LicenseManager.LicenseState> {

    data class LicenseState(
        var licenseKey: String = "",
        var email: String = "",
        var activatedAt: Long = 0,
        var lastValidated: Long = 0
    )

    private var myState = LicenseState()

    override fun getState(): LicenseState = myState

    override fun loadState(state: LicenseState) {
        myState = state
    }

    fun getCurrentTier(): LicenseTier = LicenseTier.FREE

    fun getLicenseInfo(): LicenseInfo = LicenseInfo(
        tier = LicenseTier.FREE,
        email = "",
        expiresAt = null,
        maxProjects = Int.MAX_VALUE,
        features = ALL_FEATURES
    )

    fun isFeatureAvailable(feature: String): Boolean = true

    data class ActivationResult(
        val success: Boolean,
        val message: String,
        val tier: LicenseTier = LicenseTier.FREE
    )

    companion object {
        fun getInstance(): LicenseManager {
            return ApplicationManager.getApplication().getService(LicenseManager::class.java)
        }

        const val FEATURE_BASIC_PATTERNS = "basic_patterns"
        const val FEATURE_ALL_PATTERNS = "all_patterns"
        const val FEATURE_COMPLEXITY_ANALYSIS = "complexity_analysis"
        const val FEATURE_CODE_SMELLS = "code_smells"
        const val FEATURE_CIRCULAR_DEPS = "circular_deps"
        const val FEATURE_COUPLING_ANALYSIS = "coupling_analysis"
        const val FEATURE_METRICS_DASHBOARD = "metrics_dashboard"
        const val FEATURE_EXPORT_JSON = "export_json"
        const val FEATURE_EXPORT_HTML = "export_html"
        const val FEATURE_CLI = "cli"
        const val FEATURE_LSP = "lsp"
        const val FEATURE_CUSTOM_PATTERNS = "custom_patterns"
        const val FEATURE_TEAM_ANALYTICS = "team_analytics"
        const val FEATURE_API_ACCESS = "api_access"

        val ALL_FEATURES = setOf(
            FEATURE_BASIC_PATTERNS, FEATURE_ALL_PATTERNS,
            FEATURE_COMPLEXITY_ANALYSIS, FEATURE_CODE_SMELLS,
            FEATURE_CIRCULAR_DEPS, FEATURE_COUPLING_ANALYSIS,
            FEATURE_METRICS_DASHBOARD, FEATURE_EXPORT_JSON,
            FEATURE_EXPORT_HTML, FEATURE_CLI, FEATURE_LSP,
            FEATURE_CUSTOM_PATTERNS, FEATURE_TEAM_ANALYTICS,
            FEATURE_API_ACCESS
        )

        val FREE_FEATURES = ALL_FEATURES
        val PRO_FEATURES = ALL_FEATURES
        val ENTERPRISE_FEATURES = ALL_FEATURES
        val FREE_PATTERN_NAMES: Set<String>? = null // null = all patterns
    }
}
