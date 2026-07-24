package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.util.SizeF
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.util.FocusTimerManager

object WidgetUpdater {

    fun getPendingIntentFlags(isMutable: Boolean = false): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (isMutable) PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
    }

    /**
     * Programmatically requests the Android Launcher to pin a widget to the Home Screen (Android 8.0+ / API 26+)
     */
    fun requestPinWidget(context: Context, providerClass: Class<*>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            if (appWidgetManager.isRequestPinAppWidgetSupported) {
                val myProvider = ComponentName(context, providerClass)
                val successCallback = PendingIntent.getBroadcast(
                    context,
                    9000,
                    Intent(context, providerClass).apply { action = "com.example.widget.ACTION_WIDGET_PINNED" },
                    getPendingIntentFlags()
                )
                appWidgetManager.requestPinAppWidget(myProvider, null, successCallback)
            }
        }
    }

    /**
     * Updates the Friends Focus Widget ("Who is Focusing")
     * Supports responsive layouts (Android 12+) with Small and Standard views.
     */
    fun updateFriendsFocusWidget(context: Context, statusText: String? = null) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val thisWidget = ComponentName(context, FriendsFocusWidgetProvider::class.java)
        val allWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
        if (allWidgetIds.isEmpty()) return

        val textToShow = statusText ?: context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
            .getString("last_friends_focus_text", "No active peers") ?: "No active peers"

        if (statusText != null) {
            context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
                .edit()
                .putString("last_friends_focus_text", statusText)
                .apply()
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("SHOW_TIMER_PAGE", true)
        }
        val pendingIntent = PendingIntent.getActivity(context, 2001, intent, getPendingIntentFlags())

        for (widgetId in allWidgetIds) {
            val largeView = RemoteViews(context.packageName, R.layout.widget_friends_focus).apply {
                setTextViewText(R.id.focus_status_text, textToShow)
                setOnClickPendingIntent(R.id.widget_root, pendingIntent)
            }

            val finalViews = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val smallView = RemoteViews(context.packageName, R.layout.widget_friends_focus_small).apply {
                    setTextViewText(R.id.focus_status_text, textToShow)
                    setOnClickPendingIntent(android.R.id.background, pendingIntent)
                }
                val viewMap = mapOf(
                    SizeF(140f, 50f) to smallView,
                    SizeF(200f, 80f) to largeView
                )
                RemoteViews(viewMap)
            } else {
                largeView
            }

            appWidgetManager.updateAppWidget(widgetId, finalViews)
        }
    }

    /**
     * Updates the Stopwatch Widget using Chronometer and responsive layouts (Android 12+ API 31+)
     */
    fun updateStopwatchWidget(context: Context, isPartialUpdate: Boolean = false) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val thisWidget = ComponentName(context, TimerStopwatchWidgetProvider::class.java)
        val allWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
        if (allWidgetIds.isEmpty()) return

        FocusTimerManager.init(context)
        val seconds = FocusTimerManager.stopwatchSeconds.value
        val isRunning = FocusTimerManager.isStopwatchActive.value
        val baseTime = android.os.SystemClock.elapsedRealtime() - seconds * 1000L

        val startPauseIntent = Intent(context, TimerStopwatchWidgetProvider::class.java).apply {
            action = "com.example.widget.ACTION_STOPWATCH_START_PAUSE"
        }
        val startPausePending = PendingIntent.getBroadcast(context, 3001, startPauseIntent, getPendingIntentFlags())

        val resetIntent = Intent(context, TimerStopwatchWidgetProvider::class.java).apply {
            action = "com.example.widget.ACTION_STOPWATCH_RESET"
        }
        val resetPending = PendingIntent.getBroadcast(context, 3002, resetIntent, getPendingIntentFlags())

        val rootIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("SHOW_TIMER_PAGE", true)
        }
        val rootPending = PendingIntent.getActivity(context, 3003, rootIntent, getPendingIntentFlags())

        val btnText = if (isRunning) "⏸ PAUSE" else "▶ START"

        for (widgetId in allWidgetIds) {
            val largeView = RemoteViews(context.packageName, R.layout.widget_stopwatch).apply {
                setChronometer(R.id.stopwatch_time_display, baseTime, null, isRunning)
                setTextViewText(R.id.btn_stopwatch_start_pause, btnText)
                setOnClickPendingIntent(R.id.btn_stopwatch_start_pause, startPausePending)
                setOnClickPendingIntent(R.id.btn_stopwatch_reset, resetPending)
                setOnClickPendingIntent(R.id.stopwatch_title, rootPending)
                setOnClickPendingIntent(R.id.stopwatch_time_display, rootPending)
            }

            if (isPartialUpdate) {
                appWidgetManager.partiallyUpdateAppWidget(widgetId, largeView)
                continue
            }

            val finalViews = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val smallView = RemoteViews(context.packageName, R.layout.widget_stopwatch_small).apply {
                    setChronometer(R.id.stopwatch_time_display, baseTime, null, isRunning)
                    setTextViewText(R.id.btn_stopwatch_start_pause, btnText)
                    setOnClickPendingIntent(R.id.btn_stopwatch_start_pause, startPausePending)
                    setOnClickPendingIntent(R.id.stopwatch_time_display, rootPending)
                }
                val viewMap = mapOf(
                    SizeF(140f, 70f) to smallView,
                    SizeF(200f, 100f) to largeView
                )
                RemoteViews(viewMap)
            } else {
                largeView
            }

            appWidgetManager.updateAppWidget(widgetId, finalViews)
        }
    }

    /**
     * Updates the Pomodoro Widget using countdown Chronometer and responsive layouts (Android 12+ API 31+)
     */
    fun updatePomodoroWidget(context: Context, isPartialUpdate: Boolean = false) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val thisWidget = ComponentName(context, PomodoroWidgetProvider::class.java)
        val allWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
        if (allWidgetIds.isEmpty()) return

        FocusTimerManager.init(context)
        val totalSecs = FocusTimerManager.timerSecondsLeft.value
        val isRunning = FocusTimerManager.isTimerRunning.value
        val isFocus = FocusTimerManager.isFocusPhase.value
        val baseTime = android.os.SystemClock.elapsedRealtime() + totalSecs * 1000L

        val headerText = if (isFocus) "POMODORO FOCUS 🎯" else "REST BREAK ☕"
        val headerColor = if (isFocus) 0xFF30D158.toInt() else 0xFFFF9500.toInt()
        val btnText = if (isRunning) "⏸ PAUSE" else "▶ START"

        val startPauseIntent = Intent(context, PomodoroWidgetProvider::class.java).apply {
            action = "com.example.widget.ACTION_POMO_START_PAUSE"
        }
        val startPausePending = PendingIntent.getBroadcast(context, 4001, startPauseIntent, getPendingIntentFlags())

        val resetIntent = Intent(context, PomodoroWidgetProvider::class.java).apply {
            action = "com.example.widget.ACTION_POMO_RESET"
        }
        val resetPending = PendingIntent.getBroadcast(context, 4002, resetIntent, getPendingIntentFlags())

        val rootIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("SHOW_TIMER_PAGE", true)
        }
        val rootPending = PendingIntent.getActivity(context, 4003, rootIntent, getPendingIntentFlags())

        for (widgetId in allWidgetIds) {
            val largeView = RemoteViews(context.packageName, R.layout.widget_pomodoro).apply {
                setTextViewText(R.id.pomo_title, headerText)
                setTextColor(R.id.pomo_title, headerColor)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    setChronometerCountDown(R.id.pomo_time_display, true)
                }
                setChronometer(R.id.pomo_time_display, baseTime, null, isRunning)
                setTextViewText(R.id.btn_pomo_start_pause, btnText)
                setOnClickPendingIntent(R.id.btn_pomo_start_pause, startPausePending)
                setOnClickPendingIntent(R.id.btn_pomo_reset, resetPending)
                setOnClickPendingIntent(R.id.pomo_title, rootPending)
                setOnClickPendingIntent(R.id.pomo_time_display, rootPending)
            }

            if (isPartialUpdate) {
                appWidgetManager.partiallyUpdateAppWidget(widgetId, largeView)
                continue
            }

            val finalViews = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val smallView = RemoteViews(context.packageName, R.layout.widget_pomodoro_small).apply {
                    setTextViewText(R.id.pomo_title, headerText)
                    setTextColor(R.id.pomo_title, headerColor)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        setChronometerCountDown(R.id.pomo_time_display, true)
                    }
                    setChronometer(R.id.pomo_time_display, baseTime, null, isRunning)
                    setTextViewText(R.id.btn_pomo_start_pause, btnText)
                    setOnClickPendingIntent(R.id.btn_pomo_start_pause, startPausePending)
                    setOnClickPendingIntent(R.id.pomo_time_display, rootPending)
                }
                val viewMap = mapOf(
                    SizeF(140f, 70f) to smallView,
                    SizeF(200f, 100f) to largeView
                )
                RemoteViews(viewMap)
            } else {
                largeView
            }

            appWidgetManager.updateAppWidget(widgetId, finalViews)
        }
    }

    /**
     * Forces full updates across all widgets
     */
    fun updateAllWidgets(context: Context) {
        try {
            updateFriendsFocusWidget(context)
            updateStopwatchWidget(context)
            updatePomodoroWidget(context)
        } catch (e: Exception) {
            Log.e("WidgetUpdater", "Error updating widgets: ${e.message}")
        }
    }
}
