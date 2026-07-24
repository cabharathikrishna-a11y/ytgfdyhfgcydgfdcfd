package com.example.api

import android.content.Context
import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * FirebaseRepairKit
 *
 * Ensures Firebase Realtime Database (RTDB) structure under `FOCUS_TIMMER/USER/<sanitizedEmail>`
 * is healthy, completely populated, and free of obsolete/unwanted legacy nodes.
 *
 * KEY GUARANTEES:
 * 1. Checks and recreates required nodes/fields if missing (e.g. ARENA, ACTIVE_FOCUS_TIMER,
 *    DEVICES_LOGGED_IN, SYLLABUS_COMPLETED).
 * 2. Prunes unwanted/obsolete legacy branches (e.g., corrupt keys, temporary nodes, legacy WEEKLY_STATS).
 * 3. NO RECURSIVE/INFINITE LOOPS: Employs read-before-write single-value inspections, debouncing,
 *    and strict comparison so writes or deletes are ONLY performed when an actual mismatch exists.
 */
object FirebaseRepairKit {
    private const val TAG = "FirebaseRepairKit"
    private const val MIN_REPAIR_INTERVAL_MS = 10000L // 10s debounce per user

    private val lastRepairTimes = ConcurrentHashMap<String, Long>()
    private val isRepairingMap = ConcurrentHashMap<String, AtomicBoolean>()

    // Allowed / Known valid root branches under FOCUS_TIMMER/USER/{sanitizedEmail}
    private val VALID_USER_BRANCHES = setOf(
        "ARENA",
        "ACTIVE_FOCUS_TIMER",
        "DEVICES_LOGGED_IN",
        "SYLLABUS_COMPLETED",
        "TASKS",
        "FOCUS_LOCKER",
        "STREAK_SHIELDS",
        "settingsLastUpdatedTs",
        "DEDUCTED_XP",
        "active_command",
        "status",
        "typing",
        "focusTimer"
    )

    // Known obsolete or unwanted legacy keys to explicitly prune if present
    private val OBSOLETE_USER_BRANCHES = setOf(
        "WEEKLY_STATS",
        "old_arena",
        "users",
        "temp_test",
        "dummy_data",
        "null",
        "undefined",
        "test_node",
        "invalid_data",
        "corrupt_branch"
    )

    fun sanitizeEmail(rawEmail: String): String {
        return rawEmail.lowercase().trim()
            .replace(".", "_")
            .replace("$", "_")
            .replace("[", "_")
            .replace("]", "_")
            .replace("#", "_")
            .replace("/", "_")
    }

    /**
     * Main repair function. Triggers an asynchronous inspection and repair of RTDB.
     */
    fun repairUserData(context: Context, email: String, force: Boolean = false) {
        val sanitized = sanitizeEmail(email)
        if (sanitized.isBlank() || sanitized == "null" || sanitized == "undefined") return

        val now = System.currentTimeMillis()
        val lastRun = lastRepairTimes[sanitized] ?: 0L
        if (!force && (now - lastRun < MIN_REPAIR_INTERVAL_MS)) {
            Log.d(TAG, "Repair skipped for $sanitized (debounced within $MIN_REPAIR_INTERVAL_MS ms)")
            return
        }

        val flag = isRepairingMap.computeIfAbsent(sanitized) { AtomicBoolean(false) }
        if (!flag.compareAndSet(false, true)) {
            Log.d(TAG, "Repair already in progress for $sanitized")
            return
        }

        lastRepairTimes[sanitized] = now

        CoroutineScope(Dispatchers.IO).launch {
            try {
                Firebase.ensureFirebaseInitialized(context)
                val dbUrl = FirebaseConfig.getDatabaseUrl(context)
                if (dbUrl.isEmpty()) {
                    flag.set(false)
                    return@launch
                }

                val db = FirebaseDatabase.getInstance(dbUrl)
                val userRef = db.getReference("FOCUS_TIMMER/USER").child(sanitized)

                userRef.addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        try {
                            performStructuralRepair(context, sanitized, snapshot, userRef)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error during structural repair execution for $sanitized", e)
                        } finally {
                            flag.set(false)
                        }
                    }

                    override fun onCancelled(error: DatabaseError) {
                        Log.e(TAG, "Database error during repair for $sanitized: ${error.message}")
                        flag.set(false)
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "Failed launching repairUserData for $sanitized", e)
                flag.set(false)
            }
        }
    }

    private fun performStructuralRepair(
        context: Context,
        sanitizedEmail: String,
        userSnapshot: DataSnapshot,
        userRef: com.google.firebase.database.DatabaseReference
    ) {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val displayName = prefs.getString("user_nickname", "")?.ifEmpty {
            prefs.getString("user_name", "")?.ifEmpty { sanitizedEmail.substringBefore("@") }
        } ?: sanitizedEmail.substringBefore("@")
        val emoji = prefs.getString("user_emoji", "👤")?.ifEmpty { "👤" } ?: "👤"

        var mutationsPerformed = false

        // 1. PRUNE OBSOLETE OR UNWANTED BRANCHES
        for (child in userSnapshot.children) {
            val key = child.key ?: continue
            if (OBSOLETE_USER_BRANCHES.contains(key) || (!VALID_USER_BRANCHES.contains(key) && key.startsWith("temp_"))) {
                Log.w(TAG, "Pruning unwanted/obsolete branch: FOCUS_TIMMER/USER/$sanitizedEmail/$key")
                userRef.child(key).removeValue()
                mutationsPerformed = true
            }
        }

        // 2. CHECK AND REPAIR 'ARENA' BRANCH
        val arenaSnap = userSnapshot.child("ARENA")
        val arenaRef = userRef.child("ARENA")
        val nowMs = System.currentTimeMillis()
        val nowStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(nowMs))

        val arenaUpdates = mutableMapOf<String, Any>()

        if (!arenaSnap.hasChild("ALL_TIME") && !arenaSnap.hasChild("All_Time")) {
            arenaUpdates["ALL_TIME"] = 0L
        }
        if (!arenaSnap.hasChild("PAST_30_DAYS") && !arenaSnap.hasChild("Past_30_Days")) {
            arenaUpdates["PAST_30_DAYS"] = 0L
        }
        if (!arenaSnap.hasChild("PAST_7_DAYS") && !arenaSnap.hasChild("Past_7_Days")) {
            arenaUpdates["PAST_7_DAYS"] = 0L
        }
        if (!arenaSnap.hasChild("TODAY") && !arenaSnap.hasChild("Today")) {
            arenaUpdates["TODAY"] = 0L
        }
        if (!arenaSnap.hasChild("ActiveStreak")) {
            arenaUpdates["ActiveStreak"] = 0
        }
        if (!arenaSnap.hasChild("XpScore")) {
            arenaUpdates["XpScore"] = 0
        }
        if (!arenaSnap.hasChild("DisplayName") || arenaSnap.child("DisplayName").getValue(String::class.java).isNullOrBlank()) {
            arenaUpdates["DisplayName"] = displayName
        }
        if (!arenaSnap.hasChild("CustomEmoji") || arenaSnap.child("CustomEmoji").getValue(String::class.java).isNullOrBlank()) {
            arenaUpdates["CustomEmoji"] = emoji
        }
        if (!arenaSnap.hasChild("Last_Updated")) {
            arenaUpdates["Last_Updated"] = nowMs
        }
        if (!arenaSnap.hasChild("Last_Updated_String")) {
            arenaUpdates["Last_Updated_String"] = nowStr
        }

        if (arenaUpdates.isNotEmpty()) {
            Log.i(TAG, "Repairing missing ARENA fields for $sanitizedEmail: ${arenaUpdates.keys}")
            arenaRef.updateChildren(arenaUpdates)
            mutationsPerformed = true
        }

        // 3. CHECK AND REPAIR 'ACTIVE_FOCUS_TIMER' BRANCH
        val timerSnap = userSnapshot.child("ACTIVE_FOCUS_TIMER")
        val timerRef = userRef.child("ACTIVE_FOCUS_TIMER")
        val timerUpdates = mutableMapOf<String, Any>()

        if (!timerSnap.hasChild("User_Display_Name") || timerSnap.child("User_Display_Name").getValue(String::class.java).isNullOrBlank()) {
            timerUpdates["User_Display_Name"] = displayName
        }
        if (!timerSnap.hasChild("User_Emoji") || timerSnap.child("User_Emoji").getValue(String::class.java).isNullOrBlank()) {
            timerUpdates["User_Emoji"] = emoji
        }
        if (!timerSnap.hasChild("Is_Timer_Running")) {
            timerUpdates["Is_Timer_Running"] = false
        }
        if (!timerSnap.hasChild("Current_Timer_Mode")) {
            timerUpdates["Current_Timer_Mode"] = "IDLE"
        }
        if (!timerSnap.hasChild("Total_Elapsed_Ms")) {
            timerUpdates["Total_Elapsed_Ms"] = 0L
        }

        if (timerUpdates.isNotEmpty()) {
            Log.i(TAG, "Repairing missing ACTIVE_FOCUS_TIMER fields for $sanitizedEmail: ${timerUpdates.keys}")
            timerRef.updateChildren(timerUpdates)
            mutationsPerformed = true
        }

        // 4. CHECK AND REPAIR 'DEVICES_LOGGED_IN' BRANCH
        val devicesSnap = userSnapshot.child("DEVICES_LOGGED_IN")
        if (!devicesSnap.exists() || !devicesSnap.hasChildren()) {
            Log.i(TAG, "Repairing missing DEVICES_LOGGED_IN node for $sanitizedEmail")
            val deviceModel = android.os.Build.MODEL.replace(".", "_").replace("#", "_").replace("$", "_").replace("[", "_").replace("]", "_")
            val deviceMap = mapOf(
                "isLoggedIn" to true,
                "deviceName" to android.os.Build.MODEL,
                "lastActiveTime" to nowStr,
                "lastUpdateDate" to SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(nowMs))
            )
            userRef.child("DEVICES_LOGGED_IN").child(deviceModel).updateChildren(deviceMap)
            mutationsPerformed = true
        }

        // 5. CHECK AND REPAIR 'SYLLABUS_COMPLETED' BRANCH CONTAINER
        val syllabusSnap = userSnapshot.child("SYLLABUS_COMPLETED")
        if (!syllabusSnap.exists()) {
            Log.i(TAG, "Repairing missing SYLLABUS_COMPLETED node for $sanitizedEmail")
            userRef.child("SYLLABUS_COMPLETED").setValue(emptyMap<String, Any>())
            mutationsPerformed = true
        }

        if (mutationsPerformed) {
            Log.i(TAG, "Firebase Repair Kit successfully verified and repaired database structure for $sanitizedEmail")
        } else {
            Log.d(TAG, "Firebase Repair Kit verified $sanitizedEmail: Structure is completely healthy, no mutations needed.")
        }
    }

    /**
     * Cleans up global root nodes if corrupt legacy entries exist.
     */
    fun repairGlobalRoots(context: Context) {
        try {
            Firebase.ensureFirebaseInitialized(context)
            val dbUrl = FirebaseConfig.getDatabaseUrl(context)
            if (dbUrl.isEmpty()) return

            val db = FirebaseDatabase.getInstance(dbUrl)
            val userRootRef = db.getReference("FOCUS_TIMMER/USER")

            userRootRef.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    for (child in snapshot.children) {
                        val key = child.key ?: continue
                        if (key.isBlank() || key == "null" || key == "undefined" || key == "cabharathikrishna" || key == "cabharathikrishan") {
                            Log.w(TAG, "Global Repair: Removing invalid/legacy user root node: FOCUS_TIMMER/USER/$key")
                            userRootRef.child(key).removeValue()
                        }
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Global repair cancelled: ${error.message}")
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Global repair failed", e)
        }
    }
}
