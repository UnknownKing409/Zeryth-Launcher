/*
 * Zalith Launcher 2 — Zeryth Fork
 * Crash Analyzer: ViewModel
 */

package com.movtery.zalithlauncher.viewmodel

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.movtery.zalithlauncher.game.crash.CrashDataCollector
import com.movtery.zalithlauncher.game.crash.CrashDiagnosticEngine
import com.movtery.zalithlauncher.game.crash.CrashHistoryManager
import com.movtery.zalithlauncher.game.crash.CrashRepairExecutor
import com.movtery.zalithlauncher.game.crash.model.CrashDiagnosis
import com.movtery.zalithlauncher.game.crash.model.CrashHistoryEntry
import com.movtery.zalithlauncher.game.crash.model.CrashSession
import com.movtery.zalithlauncher.game.crash.model.RepairAction
import com.movtery.zalithlauncher.utils.logging.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "CrashAnalyzerViewModel"

@HiltViewModel
class CrashAnalyzerViewModel @Inject constructor() : ViewModel() {

    // ── Analysis state ────────────────────────────────────────────────────────
    var analysisState: AnalysisState by mutableStateOf(AnalysisState.Idle)
        private set

    var session: CrashSession? by mutableStateOf(null)
        private set

    var diagnosis: CrashDiagnosis? by mutableStateOf(null)
        private set

    // ── UI state ──────────────────────────────────────────────────────────────
    /** Toggle between plain-language and technical view */
    var showTechnicalView: Boolean by mutableStateOf(false)

    /** Currently expanded card indices */
    var expandedCards: Set<String> by mutableStateOf(setOf("summary", "evidence", "repairs"))

    // ── Repair state ──────────────────────────────────────────────────────────
    var pendingRepair: RepairAction? by mutableStateOf(null)
    var repairResult: CrashRepairExecutor.RepairResult? by mutableStateOf(null)

    // ── History ───────────────────────────────────────────────────────────────
    var history: List<CrashHistoryEntry> by mutableStateOf(emptyList())
        private set

    // ── States ────────────────────────────────────────────────────────────────
    sealed class AnalysisState {
        object Idle : AnalysisState()
        object Analyzing : AnalysisState()
        object Complete : AnalysisState()
        data class Error(val message: String) : AnalysisState()
    }

    /**
     * Run the full diagnostic pipeline on the provided crash parameters.
     * Called from ErrorActivity immediately after it receives a game crash.
     */
    fun analyze(
        context: Context,
        exitCode: Int,
        isSignal: Boolean,
        logPath: String,
        gameHome: String,
        allocatedRamMb: Int,
        renderer: String,
        javaVersion: String
    ) {
        if (analysisState is AnalysisState.Analyzing) return
        analysisState = AnalysisState.Analyzing

        viewModelScope.launch(Dispatchers.IO) {
            try {
                Logger.info(TAG, "Starting crash analysis…")

                // 1. Collect all crash artifacts
                val collectedSession = CrashDataCollector.collect(
                    context = context,
                    exitCode = exitCode,
                    isSignal = isSignal,
                    logPath = logPath,
                    gameHome = gameHome,
                    allocatedRamMb = allocatedRamMb,
                    renderer = renderer,
                    javaVersion = javaVersion
                )
                session = collectedSession

                // 2. Run the rule-based diagnostic engine
                val result = CrashDiagnosticEngine.diagnose(context, collectedSession)
                diagnosis = result

                // 3. Save to history
                CrashHistoryManager.save(collectedSession, result)

                Logger.info(TAG, "Analysis complete. Category=${result.category}, Confidence=${result.confidence}%")
                analysisState = AnalysisState.Complete

            } catch (e: Exception) {
                Logger.error(TAG, "Analysis failed.", e)
                analysisState = AnalysisState.Error(e.localizedMessage ?: "Analysis failed")
            }
        }
    }

    /** Toggle expansion of a named card section */
    fun toggleCard(cardKey: String) {
        expandedCards = if (cardKey in expandedCards) {
            expandedCards - cardKey
        } else {
            expandedCards + cardKey
        }
    }

    /** User confirmed a repair — execute it */
    fun executeRepair(context: Context, action: RepairAction) {
        val currentSession = session ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val result = CrashRepairExecutor.execute(context, action, currentSession)
            repairResult = result
            pendingRepair = null
            // Record in history
            val currentDiagnosis = diagnosis
            if (currentDiagnosis != null) {
                val historyEntries = CrashHistoryManager.load()
                val latest = historyEntries.firstOrNull()
                if (latest != null) {
                    CrashHistoryManager.recordRepairAttempt(
                        id = latest.id,
                        repairType = action.type.name,
                        succeeded = result is CrashRepairExecutor.RepairResult.Success
                    )
                }
            }
        }
    }

    /** Dismiss the repair result message */
    fun dismissRepairResult() {
        repairResult = null
    }

    /** Load crash history for the history tab */
    fun loadHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            history = CrashHistoryManager.load()
        }
    }

    /** Delete a history entry */
    fun deleteHistoryEntry(id: String) {
        CrashHistoryManager.delete(id)
        history = history.filterNot { it.id == id }
    }

    /** Clear all history */
    fun clearHistory() {
        CrashHistoryManager.clearAll()
        history = emptyList()
    }
}
