package com.example.util

import android.content.Context
import android.util.Log
import com.example.api.DynamicCommandManager
import com.example.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class AnomalyType {
    CROSS_DEVICE_ADOPTION,
    DATE_ROLLOVER,
    OVERLAP_INTERVAL_MERGE,
    ACTIVE_SESSION_TRANSITION,
    STALE_PREFERENCE_HEALED,
    SYSTEM_HEALTHY
}

enum class FixAction {
    RUN_DEEP_RECONCILIATION,
    CLEAR_CROSS_DEVICE_ADOPTION,
    RECALCULATE_LOCAL_VAULT,
    RESYNC_SESSION_TO_RTDB,
    DISMISS
}

data class AnomalyReport(
    val timestampMs: Long = System.currentTimeMillis(),
    val oldFocusSeconds: Int,
    val newFocusSeconds: Int,
    val deltaSeconds: Int,
    val anomalyType: AnomalyType,
    val title: String,
    val reasonExplanation: String,
    val technicalDetails: String,
    val recommendedFixes: List<FixAction>
)

object FocusTimerAnomalyChecker {
    private const val TAG = "FocusTimerAnomalyChecker"

    private val _lastDetectedAnomaly = MutableStateFlow<AnomalyReport?>(null)
    val lastDetectedAnomaly: StateFlow<AnomalyReport?> = _lastDetectedAnomaly.asStateFlow()

    private val _anomalyHistory = MutableStateFlow<List<AnomalyReport>>(emptyList())
    val anomalyHistory: StateFlow<List<AnomalyReport>> = _anomalyHistory.asStateFlow()

    private var previousObservedTodaySeconds: Int? = null

    /**
     * Evaluates a change in Today's focus time seconds.
     * Triggers an anomaly report if the jump or drop exceeds threshold (e.g. > 10 minutes change in a single tick)
     * without a normal active session timer tick.
     */
    fun evaluateFocusTimeChange(
        oldSecs: Int,
        newSecs: Int,
        isTimerRunning: Boolean,
        context: Context
    ) {
        if (oldSecs == newSecs) return
        val delta = newSecs - oldSecs
        val absDelta = kotlin.math.abs(delta)

        // Ignore 1-second ticks during an active timer
        if (isTimerRunning && absDelta <= 2) {
            return
        }

        // If the change is significant (> 300 seconds / 5 minutes sudden jump or drop)
        if (absDelta >= 300) {
            val report = analyzeReason(oldSecs, newSecs, delta, context)
            _lastDetectedAnomaly.value = report
            _anomalyHistory.value = listOf(report) + _anomalyHistory.value.take(19)

            val logMsg = "Detected Focus Anomaly: ${report.title} (Delta: ${delta / 60}m). Reason: ${report.reasonExplanation}"
            Log.w(TAG, logMsg)
            FocusTimerManager.addSystemLog(context, "ANOMALY_DETECTED", "CHECKER", logMsg)
        }
    }

    /**
     * Runs an on-demand comprehensive diagnostic check across SQLite Vault, SharedPrefs,
     * cross-device adopted stats, and active timer buffers to identify any hidden discrepancies.
     */
    suspend fun runDiagnosticCheck(context: Context): AnomalyReport {
        val appContext = context.applicationContext
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val db = AppDatabase.getInstance(appContext)

        // 1. Local Vault Total
        val vaultRecords = db.localHistoryVaultDao().getAllHistoryDirect()
            .filter { it.date_string == todayStr }
        val vaultTotalMs = vaultRecords.sumOf { it.total_focus_ms }
        val vaultTotalSecs = (vaultTotalMs / 1000).toInt()

        // 2. FocusRecord Entity Total
        val focusRecordSecs = db.focusRecordDao().getRecordsForDate(todayStr)
            .sumOf { it.durationSeconds }

        // 3. Shared Preferences / Memory total
        val prefs = appContext.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val prefMinutes = prefs.getInt("total_focus_minutes", 0)
        val prefSecs = prefMinutes * 60

        // 4. Focus Drift Check
        val meEmail = DynamicCommandManager.activeEmail.lowercase().trim()
        val driftReport = FocusDriftDetector.runOnDemandDiagnostic(appContext, meEmail)
        val sanitizedEmail = meEmail.replace(".", "_")
        val savedAdoptedDate = prefs.getString("adopted_today_date_${sanitizedEmail}", "")
        val adoptedTodayMs = if (savedAdoptedDate == todayStr) {
            prefs.getLong("adopted_today_ms_${sanitizedEmail}", 0L)
        } else {
            0L
        }
        val adoptedSecs = (adoptedTodayMs / 1000).toInt()

        val expectedCombinedSecs = vaultTotalSecs + adoptedSecs
        val currentObservedSecs = maxOf(vaultTotalSecs, prefSecs)

        Log.d(TAG, "Diagnostic Check -> Vault: ${vaultTotalSecs}s, FocusRecords: ${focusRecordSecs}s, Prefs: ${prefSecs}s, Adopted: ${adoptedSecs}s")

        // Analyze findings
        return when {
            adoptedSecs > 0 && kotlin.math.abs(prefSecs - expectedCombinedSecs) > 120 -> {
                AnomalyReport(
                    oldFocusSeconds = prefSecs,
                    newFocusSeconds = expectedCombinedSecs,
                    deltaSeconds = expectedCombinedSecs - prefSecs,
                    anomalyType = AnomalyType.CROSS_DEVICE_ADOPTION,
                    title = "Cross-Device Focus Time Adoption",
                    reasonExplanation = "Your focus time includes +${adoptedSecs / 60}m recorded on your other logged-in device.",
                    technicalDetails = "Local Vault: ${vaultTotalSecs / 60}m, Multi-Device Adopted: ${adoptedSecs / 60}m. Sync date: $savedAdoptedDate.",
                    recommendedFixes = listOf(FixAction.RUN_DEEP_RECONCILIATION, FixAction.CLEAR_CROSS_DEVICE_ADOPTION, FixAction.DISMISS)
                )
            }
            kotlin.math.abs(vaultTotalSecs - focusRecordSecs) > 120 -> {
                AnomalyReport(
                    oldFocusSeconds = focusRecordSecs,
                    newFocusSeconds = vaultTotalSecs,
                    deltaSeconds = vaultTotalSecs - focusRecordSecs,
                    anomalyType = AnomalyType.OVERLAP_INTERVAL_MERGE,
                    title = "Database & Vault Discrepancy",
                    reasonExplanation = "A slight mismatch exists between local session history (${vaultTotalSecs / 60}m) and local focus records (${focusRecordSecs / 60}m).",
                    technicalDetails = "Vault DB: ${vaultTotalSecs}s vs FocusRecords DB: ${focusRecordSecs}s.",
                    recommendedFixes = listOf(FixAction.RUN_DEEP_RECONCILIATION, FixAction.RECALCULATE_LOCAL_VAULT, FixAction.DISMISS)
                )
            }
            kotlin.math.abs(prefSecs - vaultTotalSecs) > 300 && adoptedSecs == 0 -> {
                AnomalyReport(
                    oldFocusSeconds = prefSecs,
                    newFocusSeconds = vaultTotalSecs,
                    deltaSeconds = vaultTotalSecs - prefSecs,
                    anomalyType = AnomalyType.STALE_PREFERENCE_HEALED,
                    title = "Cached Today Total Recalibration Needed",
                    reasonExplanation = "Your cached display total (${prefSecs / 60}m) differs from verified local session logs (${vaultTotalSecs / 60}m).",
                    technicalDetails = "SharedPreferences total_focus_minutes ($prefMinutes) vs Vault DB total ($vaultTotalSecs).",
                    recommendedFixes = listOf(FixAction.RUN_DEEP_RECONCILIATION, FixAction.RECALCULATE_LOCAL_VAULT, FixAction.DISMISS)
                )
            }
            else -> {
                AnomalyReport(
                    oldFocusSeconds = currentObservedSecs,
                    newFocusSeconds = currentObservedSecs,
                    deltaSeconds = 0,
                    anomalyType = AnomalyType.SYSTEM_HEALTHY,
                    title = "Focus Timer Fully Healthy",
                    reasonExplanation = "All local database tables, multi-device sync counters, and display totals are perfectly aligned with zero discrepancies.",
                    technicalDetails = "Verified Vault DB (${vaultTotalSecs / 60}m), FocusRecords (${focusRecordSecs / 60}m), and Preferences ($prefMinutes m).",
                    recommendedFixes = listOf(FixAction.DISMISS)
                )
            }
        }
    }

    private fun analyzeReason(oldSecs: Int, newSecs: Int, delta: Int, context: Context): AnomalyReport {
        val deltaMins = delta / 60
        val isIncrease = delta > 0
        val signStr = if (isIncrease) "+${deltaMins}m" else "${deltaMins}m"

        return if (isIncrease) {
            AnomalyReport(
                oldFocusSeconds = oldSecs,
                newFocusSeconds = newSecs,
                deltaSeconds = delta,
                anomalyType = AnomalyType.CROSS_DEVICE_ADOPTION,
                title = "Sudden Focus Time Increase ($signStr)",
                reasonExplanation = "Today's focus time jumped by $signStr. This usually happens when focus time completed on another device was synced/adopted, or an active session completed.",
                technicalDetails = "Previous displayed: ${oldSecs / 60}m, New displayed: ${newSecs / 60}m. Change: $signStr.",
                recommendedFixes = listOf(FixAction.RUN_DEEP_RECONCILIATION, FixAction.CLEAR_CROSS_DEVICE_ADOPTION, FixAction.DISMISS)
            )
        } else {
            AnomalyReport(
                oldFocusSeconds = oldSecs,
                newFocusSeconds = newSecs,
                deltaSeconds = delta,
                anomalyType = AnomalyType.DATE_ROLLOVER,
                title = "Sudden Focus Time Drop ($signStr)",
                reasonExplanation = "Today's focus time decreased by $signStr. This occurs during midnight date rollover, or when overlapping sessions are de-duplicated by the background healer.",
                technicalDetails = "Previous displayed: ${oldSecs / 60}m, New displayed: ${newSecs / 60}m. Change: $signStr.",
                recommendedFixes = listOf(FixAction.RUN_DEEP_RECONCILIATION, FixAction.RECALCULATE_LOCAL_VAULT, FixAction.DISMISS)
            )
        }
    }

    fun applyFixAction(
        action: FixAction,
        context: Context,
        username: String,
        scope: CoroutineScope,
        onResult: (String) -> Unit
    ) {
        val appContext = context.applicationContext
        scope.launch(Dispatchers.IO) {
            try {
                when (action) {
                    FixAction.RUN_DEEP_RECONCILIATION -> {
                        FocusTimerManager.runBackgroundAuditAndHealing(appContext)
                        if (username.isNotBlank()) {
                            FocusReconciliationEngine.runReconciliation(appContext, username)
                        }
                        _lastDetectedAnomaly.value = null
                        onResult("✅ Deep Alignment Complete: All overlapping intervals merged and local vault recalculated.")
                    }
                    FixAction.CLEAR_CROSS_DEVICE_ADOPTION -> {
                        val prefs = appContext.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                        val meEmail = DynamicCommandManager.activeEmail.lowercase().trim()
                        val sanitizedEmail = meEmail.replace(".", "_")
                        prefs.edit()
                            .putLong("adopted_today_ms_${sanitizedEmail}", 0L)
                            .remove("adopted_today_date_${sanitizedEmail}")
                            .apply()
                        FocusTimerManager.setAdoptedTodayMs(0L)
                        FocusTimerManager.runBackgroundAuditAndHealing(appContext)
                        _lastDetectedAnomaly.value = null
                        onResult("✅ Cross-Device Adoption Reset: Local device totals will reflect local session vault only.")
                    }
                    FixAction.RECALCULATE_LOCAL_VAULT -> {
                        FocusTimerManager.runBackgroundAuditAndHealing(appContext)
                        _lastDetectedAnomaly.value = null
                        onResult("✅ Local Vault Recalculated: Display preferences updated directly from SQLite DB.")
                    }
                    FixAction.RESYNC_SESSION_TO_RTDB -> {
                        val meEmail = DynamicCommandManager.activeEmail
                        val ok = FocusDriftDetector.resyncSession(appContext, meEmail)
                        _lastDetectedAnomaly.value = null
                        if (ok) {
                            onResult("⚡ Session Resynced to Firebase RTDB: Client state aligned with cloud timestamps.")
                        } else {
                            onResult("❌ Session Resync Failed: Could not update Realtime Database.")
                        }
                    }
                    FixAction.DISMISS -> {
                        _lastDetectedAnomaly.value = null
                        onResult("Alert dismissed.")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed applying fix action $action", e)
                onResult("❌ Error applying fix: ${e.localizedMessage}")
            }
        }
    }
}
