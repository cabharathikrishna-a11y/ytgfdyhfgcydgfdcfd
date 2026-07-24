package com.example.api

import com.example.util.TimeEngine
import java.util.Locale

object TimelineSyncEngine {

    fun calculateAccumulatedFocusMs(timeline: List<TimelineEvent>, currentStatus: String): Long {
        var totalFocusMs = 0L
        var lastFocusAnchor = 0L

        for (event in timeline) {
            val action = event.event.lowercase().trim()
            if (action == "start" || action == "resume" || action == "resumed" || action == "break_ended" || action == "break end" || action == "break_end") {
                lastFocusAnchor = event.timestamp
            } else if (action == "pause" || action == "paused" || action == "break_started" || action == "break start" || action == "session_end" || action == "completed" || action == "end") {
                if (lastFocusAnchor > 0L) {
                    if (event.timestamp > lastFocusAnchor) {
                        totalFocusMs += (event.timestamp - lastFocusAnchor)
                    }
                    lastFocusAnchor = 0L
                }
            }
        }

        val cleanStatus = currentStatus.lowercase().trim()
        if ((cleanStatus == "focusing" || cleanStatus == "running" || cleanStatus == "active") && lastFocusAnchor > 0L) {
            val trueTime = TimeEngine.getTrueTimeMs()
            if (trueTime > lastFocusAnchor) {
                totalFocusMs += (trueTime - lastFocusAnchor)
            }
        }

        return maxOf(0L, totalFocusMs)
    }

    fun calculateAccumulatedBreakMs(timeline: List<TimelineEvent>, currentStatus: String): Long {
        var totalBreakMs = 0L
        var lastBreakAnchor = 0L

        for (event in timeline) {
            val action = event.event.lowercase().trim()
            if (action == "break_started" || action == "break start" || action == "break") {
                lastBreakAnchor = event.timestamp
            } else if (action == "resume" || action == "resumed" || action == "break_ended" || action == "break end" || action == "break_end" || action == "session_end" || action == "completed" || action == "end") {
                if (lastBreakAnchor > 0L) {
                    if (event.timestamp > lastBreakAnchor) {
                        totalBreakMs += (event.timestamp - lastBreakAnchor)
                    }
                    lastBreakAnchor = 0L
                }
            }
        }

        val cleanStatus = currentStatus.lowercase().trim()
        if ((cleanStatus == "break" || cleanStatus == "breaking" || cleanStatus == "relaxing") && lastBreakAnchor > 0L) {
            val trueTime = TimeEngine.getTrueTimeMs()
            if (trueTime > lastBreakAnchor) {
                totalBreakMs += (trueTime - lastBreakAnchor)
            }
        }

        return maxOf(0L, totalBreakMs)
    }

    fun calculateAccumulatedPauseMs(timeline: List<TimelineEvent>, currentStatus: String): Long {
        var totalPauseMs = 0L
        var lastPauseAnchor = 0L

        for (event in timeline) {
            val action = event.event.lowercase().trim()
            if (action == "pause" || action == "paused") {
                lastPauseAnchor = event.timestamp
            } else if (action == "resume" || action == "resumed" || action == "break_started" || action == "break start" || action == "break_ended" || action == "break end" || action == "break_end" || action == "session_end" || action == "completed" || action == "end") {
                if (lastPauseAnchor > 0L) {
                    if (event.timestamp > lastPauseAnchor) {
                        totalPauseMs += (event.timestamp - lastPauseAnchor)
                    }
                    lastPauseAnchor = 0L
                }
            }
        }

        val cleanStatus = currentStatus.lowercase().trim()
        if (cleanStatus == "paused" && lastPauseAnchor > 0L) {
            val trueTime = TimeEngine.getTrueTimeMs()
            if (trueTime > lastPauseAnchor) {
                totalPauseMs += (trueTime - lastPauseAnchor)
            }
        }

        return maxOf(0L, totalPauseMs)
    }

    fun formatTimeMsToHhMmSs(timeMs: Long): String {
        val totalSeconds = maxOf(0L, timeMs / 1000)
        val hh = totalSeconds / 3600
        val mm = (totalSeconds % 3600) / 60
        val ss = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d:%02d", hh, mm, ss)
    }
}
