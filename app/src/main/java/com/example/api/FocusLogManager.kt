package com.example.api

import android.content.Context
import android.util.Log
import com.example.util.TimeEngine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FocusLogManager {
    private const val TAG = "FocusLogManager"
    private const val PREF_NAME = "focus_logs_prefs"
    private const val KEY_LOGS = "focus_logs_list"

    fun logEvent(context: Context, message: String) {
        try {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val existing = prefs.getString(KEY_LOGS, "") ?: ""
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val timestamp = sdf.format(Date(TimeEngine.getTrueTimeMs()))
            val logLine = "[$timestamp] $message"
            
            val updated = if (existing.isEmpty()) {
                logLine
            } else {
                logLine + "\n" + existing
            }
            prefs.edit().putString(KEY_LOGS, updated).apply()
            Log.d(TAG, "Logged event: $logLine")
        } catch (e: Exception) {
            Log.e(TAG, "Error writing focus log", e)
        }
    }

    fun getLogs(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_LOGS, "") ?: ""
        return if (existing.isEmpty()) {
            listOf("No focus log history yet.")
        } else {
            existing.split("\n")
        }
    }
    
    fun clearLogs(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().remove(KEY_LOGS).apply()
    }
}
