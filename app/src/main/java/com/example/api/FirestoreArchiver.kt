package com.example.api

import android.content.Context
import android.util.Log
import com.example.data.AppDatabase
import com.example.data.LocalHistoryVault
import com.example.data.OutboxQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object FirestoreArchiver {
    private const val TAG = "FirestoreArchiver"

    suspend fun pullAndSyncFocusHistoryFromFirestore(context: Context, email: String): Pair<Boolean, String> {
        if (email.isBlank()) {
            return Pair(false, "User email is blank")
        }
        if (!com.example.util.NetworkChecker.isOnline(context)) {
            return Pair(false, "Device is offline")
        }

        try {
            val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance(
                com.google.firebase.FirebaseApp.getInstance(),
                "main"
            )

            val snapshot = suspendCancellableCoroutine { cont ->
                firestore.collection("users").document(email)
                    .collection("focus_records")
                    .get()
                    .addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            cont.resume(task.result)
                        } else {
                            cont.resumeWithException(task.exception ?: Exception("Failed to query focus_records from Firestore"))
                        }
                    }
            }

            if (snapshot == null || snapshot.isEmpty) {
                return Pair(true, "No records found in Firestore")
            }

            val db = AppDatabase.getInstance(context)
            var count = 0

            for (doc in snapshot.documents) {
                val sessionId = doc.getString("Session_ID") ?: doc.id
                val currentTag = doc.getString("Current_Tag") ?: doc.getString("subject") ?: ""
                val currentTask = doc.getString("Current_Task") ?: doc.getString("task_title") ?: ""
                val timerMode = doc.getString("Timer_Mode") ?: doc.getString("mode") ?: "POMODORO"
                val totalFocusMs = doc.getLong("Total_Focus_Time_Ms") ?: 0L
                val totalBreakMs = doc.getLong("Total_Break_Time_Ms") ?: 0L
                val startTimestamp = doc.getLong("Start_Timestamp") ?: 0L
                val endTimestamp = doc.getLong("End_Timestamp") ?: 0L
                val totalFocusFormatted = doc.getString("Total_Focus_Time_Formatted") ?: TimelineSyncEngine.formatTimeMsToHhMmSs(totalFocusMs)
                val totalBreakFormatted = doc.getString("Total_Break_Time_Formatted") ?: TimelineSyncEngine.formatTimeMsToHhMmSs(totalBreakMs)

                val timelineList = mutableListOf<TimelineEvent>()
                val rawTimeline = doc.get("Timeline") as? List<Map<String, Any>>
                if (rawTimeline != null) {
                    for (item in rawTimeline) {
                        val deviceId = item["deviceId"] as? String ?: ""
                        val event = item["event"] as? String ?: ""
                        val timestamp = (item["timestamp"] as? Number)?.toLong() ?: 0L
                        if (event.isNotEmpty()) {
                            timelineList.add(TimelineEvent(deviceId, event, timestamp))
                        }
                    }
                }

                val pauseCount = timelineList.count { it.event.lowercase() == "paused" || it.event.lowercase() == "break_started" }
                val sdfTime = SimpleDateFormat("HH:mm:ss", Locale.US)
                val startTimeFormatted = if (startTimestamp > 0L) sdfTime.format(Date(startTimestamp)) else "00:00:00"
                val endTimeFormatted = if (endTimestamp > 0L) sdfTime.format(Date(endTimestamp)) else "00:00:00"
                val sdfDate = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                val dateString = if (startTimestamp > 0L) sdfDate.format(Date(startTimestamp)) else ""

                val timelineJsonArray = JSONArray()
                for (event in timelineList) {
                    val eventObj = JSONObject()
                    eventObj.put("deviceId", event.deviceId)
                    eventObj.put("event", event.event)
                    eventObj.put("timestamp", event.timestamp)
                    timelineJsonArray.put(eventObj)
                }

                val vaultRecord = LocalHistoryVault(
                    record_id = sessionId,
                    date_string = dateString,
                    subject = if (currentTag.isNotEmpty()) currentTag else "Study",
                    task_title = currentTask,
                    start_time_ms = startTimestamp,
                    end_time_ms = endTimestamp,
                    total_focus_ms = totalFocusMs,
                    total_break_ms = totalBreakMs,
                    pause_count = pauseCount,
                    duration_formatted = totalFocusFormatted,
                    start_time_formatted = startTimeFormatted,
                    end_time_formatted = endTimeFormatted,
                    is_synced_to_firestore = 1,
                    mode = timerMode.uppercase(),
                    timeline_json = timelineJsonArray.toString(),
                    timeline = timelineList
                )

                db.localHistoryVaultDao().insertRecord(vaultRecord)
                count++
            }

            Log.d(TAG, "Successfully pulled and synced $count records from Firestore to SQLite.")
            com.example.util.FocusTimerManager.reloadFocusRecordsFromDb(context)
            DevicePresenceManager.updateDeviceFocusStats(context, email)

            return Pair(true, "Successfully synchronized $count sessions from cloud.")
        } catch (e: Exception) {
            Log.e(TAG, "Error in pullAndSyncFocusHistoryFromFirestore", e)
            return Pair(false, e.message ?: "Unknown error")
        }
    }

    suspend fun archiveSessionPayload(
        context: Context,
        email: String,
        payload: SessionPayload,
        timerMode: String,
        currentTask: String,
        currentTag: String
    ) {
        val sessionId = payload.sessionId
        val startTimestamp = payload.startTimestamp
        val endTimestamp = payload.endTimestamp
        val timeline = payload.timeline

        val totalFocusMs = TimelineSyncEngine.calculateAccumulatedFocusMs(timeline, "session_end")
        val totalBreakMs = TimelineSyncEngine.calculateAccumulatedBreakMs(timeline, "session_end")

        val totalFocusFormatted = TimelineSyncEngine.formatTimeMsToHhMmSs(totalFocusMs)
        val totalBreakFormatted = TimelineSyncEngine.formatTimeMsToHhMmSs(totalBreakMs)

        // Construct Firestore payload map
        val payloadMap = hashMapOf<String, Any>(
            "Session_ID" to sessionId,
            "Current_Tag" to currentTag,
            "Current_Task" to currentTask,
            "Timer_Mode" to timerMode,
            "Total_Focus_Time_Formatted" to totalFocusFormatted,
            "Total_Break_Time_Formatted" to totalBreakFormatted,
            "Total_Focus_Time_Ms" to totalFocusMs,
            "Total_Break_Time_Ms" to totalBreakMs,
            "Start_Timestamp" to startTimestamp,
            "End_Timestamp" to endTimestamp,
            "Timeline" to timeline.map {
                mapOf(
                    "deviceId" to it.deviceId,
                    "event" to it.event,
                    "timestamp" to it.timestamp
                )
            }
        )

        var isSyncedSuccessfully = false

        // 1. Primary Upload: Attempt direct Firestore set
        try {
            if (com.example.util.NetworkChecker.isOnline(context)) {
                val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance(
                    com.google.firebase.FirebaseApp.getInstance(),
                    "main"
                )

                kotlinx.coroutines.withTimeout(5000L) {
                    firestore.collection("users").document(email)
                        .collection("focus_records").document(sessionId)
                        .set(payloadMap, com.google.firebase.firestore.SetOptions.merge())
                        .awaitTask()
                }

                isSyncedSuccessfully = true
                Log.d(TAG, "Successfully uploaded focus record to Firestore: $sessionId")
            } else {
                Log.d(TAG, "Device is offline. Skipping direct Firestore upload for $sessionId and queueing in local outbox.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload focus record direct to Firestore: $sessionId. Will queue in local outbox.", e)
        }

        // 2. Local SQLite Backup: Save the exact same data to the Room database
        val pauseCount = timeline.count { it.event.lowercase() == "paused" || it.event.lowercase() == "break_started" }
        val sdfTime = SimpleDateFormat("HH:mm:ss", Locale.US)
        val startTimeFormatted = sdfTime.format(Date(startTimestamp))
        val endTimeFormatted = sdfTime.format(Date(endTimestamp))
        val sdfDate = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val dateString = sdfDate.format(Date(startTimestamp))

        // Serialize timeline to JSON
        val timelineJsonArray = JSONArray()
        for (event in timeline) {
            val eventObj = JSONObject()
            eventObj.put("deviceId", event.deviceId)
            eventObj.put("event", event.event)
            eventObj.put("timestamp", event.timestamp)
            timelineJsonArray.put(eventObj)
        }
        val timelineJsonString = timelineJsonArray.toString()

        val vaultRecord = LocalHistoryVault(
            record_id = sessionId,
            date_string = dateString,
            subject = if (currentTag.isNotEmpty()) currentTag else "Study",
            task_title = currentTask,
            start_time_ms = startTimestamp,
            end_time_ms = endTimestamp,
            total_focus_ms = totalFocusMs,
            total_break_ms = totalBreakMs,
            pause_count = pauseCount,
            duration_formatted = totalFocusFormatted,
            start_time_formatted = startTimeFormatted,
            end_time_formatted = endTimeFormatted,
            is_synced_to_firestore = if (isSyncedSuccessfully) 1 else 0,
            mode = timerMode.uppercase(),
            timeline_json = timelineJsonString,
            timeline = timeline
        )

        withContext(Dispatchers.IO) {
            try {
                val db = AppDatabase.getInstance(context)
                db.localHistoryVaultDao().insertRecord(vaultRecord)
                Log.d(TAG, "Successfully backed up focus record to SQLite: $sessionId")
            } catch (dbEx: Exception) {
                Log.e(TAG, "Failed to write local SQLite backup for $sessionId", dbEx)
            }
        }

        // 3. Outbox Fallback: If direct upload failed, serialize and save to Room Outbox table
        if (!isSyncedSuccessfully) {
            val payloadJsonStr = serializePayloadToJson(payloadMap)
            val outboxItem = OutboxQueue(
                mutation_id = "mut_arch_${UUID.randomUUID()}",
                created_at_ms = com.example.util.TimeEngine.getTrueTimeMs(),
                routing_target = "FIRESTORE", // target = "FIRESTORE"
                action_type = "ARCHIVE_SESSION",
                payload_json = payloadJsonStr,
                retry_count = 0,
                status = "PENDING"
            )

            withContext(Dispatchers.IO) {
                try {
                    val db = AppDatabase.getInstance(context)
                    db.outboxQueueDao().insertQueueItemRaw(outboxItem)
                    Log.d(TAG, "Successfully queued unsynced focus record $sessionId in local Outbox queue.")
                } catch (dbEx: Exception) {
                    Log.e(TAG, "Failed to enqueue outbox fallback item for $sessionId", dbEx)
                }
            }
        }
    }

    private fun serializePayloadToJson(payload: Map<String, Any>): String {
        val json = JSONObject()
        json.put("Session_ID", payload["Session_ID"])
        json.put("Current_Tag", payload["Current_Tag"])
        json.put("Current_Task", payload["Current_Task"])
        json.put("Timer_Mode", payload["Timer_Mode"])
        json.put("Total_Focus_Time_Formatted", payload["Total_Focus_Time_Formatted"])
        json.put("Total_Break_Time_Formatted", payload["Total_Break_Time_Formatted"])
        json.put("Total_Focus_Time_Ms", payload["Total_Focus_Time_Ms"])
        json.put("Total_Break_Time_Ms", payload["Total_Break_Time_Ms"])
        json.put("Start_Timestamp", payload["Start_Timestamp"])
        json.put("End_Timestamp", payload["End_Timestamp"])

        val timelineArray = JSONArray()
        val timelineList = payload["Timeline"] as? List<Map<String, Any>> ?: emptyList()
        for (event in timelineList) {
            val eventObj = JSONObject()
            eventObj.put("deviceId", event["deviceId"])
            eventObj.put("event", event["event"])
            eventObj.put("timestamp", event["timestamp"])
            timelineArray.put(eventObj)
        }
        json.put("Timeline", timelineArray)
        return json.toString()
    }

    private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
        addOnCompleteListener { task ->
            if (task.isSuccessful) {
                cont.resume(task.result)
            } else {
                cont.resumeWithException(task.exception ?: Exception("Task failed"))
            }
        }
    }
}
