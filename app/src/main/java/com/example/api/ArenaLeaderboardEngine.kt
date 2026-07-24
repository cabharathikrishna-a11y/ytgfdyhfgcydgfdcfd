package com.example.api

import android.content.Context
import android.util.Log
import com.example.util.TimeEngine
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class ArenaRankModel(
    val email: String,
    val displayName: String,
    val totalFocusMs: Long,
    val activeStreak: Int,
    val xpScore: Int,
    val topSubject: String,
    val isMe: Boolean = false,
    val rank: Int = 0,
    val customEmoji: String = ""
)

object ArenaLeaderboardEngine {
    private const val TAG = "ArenaLeaderboardEngine"

    private val _leaderboardFlow = MutableStateFlow<List<ArenaRankModel>>(emptyList())
    val leaderboardFlow: StateFlow<List<ArenaRankModel>> = _leaderboardFlow.asStateFlow()

    private var friendsListener: ValueEventListener? = null
    private var friendsRef: com.google.firebase.database.DatabaseReference? = null
    private val activeWeeklyListeners = mutableMapOf<String, Pair<com.google.firebase.database.DatabaseReference, ValueEventListener>>()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var peerStatesCollectJob: Job? = null

    // Temporary storage for individual peer raw weekly stats
    private val rawWeeklyStatsMap = mutableMapOf<String, PeerWeeklyRawStats>()
    private var appContext: Context? = null

    private data class PeerWeeklyRawStats(
        val email: String,
        val displayName: String,
        val totalFocusMs: Long,
        val activeStreak: Int,
        val topSubject: String,
        val customEmoji: String = "",
        val xpScore: Int = 0,
        val lastUpdated: Long = 0L,
        val baseOverallXp: Int = 0,
        val unconsumedShieldsCount: Int = 0
    )

    private var activePeriod: String = "TODAY"

    fun startListening(context: Context, myEmail: String, period: String = "TODAY") {
        activePeriod = period
        appContext = context.applicationContext
        if (myEmail.isBlank()) {
            Log.e(TAG, "Cannot start leaderboard listening: blank email")
            return
        }
        
        try {
            val dbUrl = FirebaseConfig.getDatabaseUrl(context)
            if (dbUrl.isEmpty()) {
                Log.e(TAG, "Database URL is empty, cannot load leaderboard.")
                return
            }

            val database = FirebaseDatabase.getInstance(dbUrl)
            
            // Cleanup existing listeners if any
            stopListening()

            // 1. Core Reactive Sync with PeerLiveSphereManager's active peers
            peerStatesCollectJob = scope.launch {
                PeerLiveSphereManager.peerLiveStates.collect { peerStates ->
                    val peerEmails = mutableListOf<String>()
                    peerEmails.addAll(peerStates.keys)
                    if (myEmail.isNotBlank()) {
                        peerEmails.add(myEmail.lowercase().trim())
                    }
                    val deduplicatedEmails = peerEmails.map { it.lowercase().trim() }.distinct()
                    Log.d(TAG, "Leaderboard syncing weekly listeners dynamically based on active Live Sphere peers: $deduplicatedEmails")
                    syncWeeklyListeners(context, database, deduplicatedEmails, myEmail)
                }
            }

            // 2. Also listen to Friends List directly as a live database trigger
            val mySanitized = DevicePresenceManager.sanitizeEmail(myEmail)
            val fRef = database.getReference("FOCUS_TIMMER")
                .child("USER")
                .child(mySanitized)
                .child("FRIENDS_LIST")

            friendsRef = fRef

            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val peerEmails = mutableListOf<String>()
                    peerEmails.add(myEmail.lowercase().trim()) // Always include myself

                    // Extract all friend emails
                    if (snapshot.exists()) {
                        for (child in snapshot.children) {
                            val key = child.key ?: continue
                            val valueStr = child.getValue(String::class.java)
                            val friendId = if (valueStr != null && valueStr.contains("@")) {
                                valueStr.lowercase().trim()
                            } else if (key.contains("@") || key.contains("_")) {
                                key.lowercase().trim()
                            } else {
                                key.lowercase().trim()
                            }
                            if (friendId.isNotBlank()) {
                                peerEmails.add(friendId)
                            }
                        }
                    }

                    // Also pull from current room participants dynamically
                    val roomState = FocusLockerManager.uiState.value
                    roomState.participants.forEach {
                        peerEmails.add(it.email.lowercase().trim())
                    }

                    val deduplicatedEmails = peerEmails.map { it.lowercase().trim() }.distinct()
                    Log.d(TAG, "Leaderboard direct DB listener sync: $deduplicatedEmails")
                    syncWeeklyListeners(context, database, deduplicatedEmails, myEmail)
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Friends list listener cancelled for leaderboard", error.toException())
                }
            }

            fRef.addValueEventListener(listener)
            friendsListener = listener

        } catch (e: Exception) {
            Log.e(TAG, "Error starting leaderboard listening", e)
        }
    }

    fun stopListening() {
        try {
            peerStatesCollectJob?.cancel()
            peerStatesCollectJob = null
        } catch (e: Exception) {
            // ignore
        }

        try {
            friendsRef?.removeEventListener(friendsListener ?: return)
        } catch (e: Exception) {
            // ignore
        }
        friendsRef = null
        friendsListener = null

        // Remove all weekly listeners
        for ((ref, listener) in activeWeeklyListeners.values) {
            try {
                ref.removeEventListener(listener)
            } catch (e: Exception) {
                // ignore
            }
        }
        activeWeeklyListeners.clear()
        rawWeeklyStatsMap.clear()
        _leaderboardFlow.value = emptyList()
    }

    private fun syncWeeklyListeners(
        context: Context,
        database: FirebaseDatabase,
        peerEmails: List<String>,
        myEmail: String
    ) {
        // 1. Remove listeners for peers no longer in our set
        val iterator = activeWeeklyListeners.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val email = entry.key
            if (!peerEmails.contains(email)) {
                val (ref, listener) = entry.value
                try {
                    ref.removeEventListener(listener)
                } catch (e: Exception) {
                    // ignore
                }
                iterator.remove()
                rawWeeklyStatsMap.remove(email)
            }
        }

        // 2. Add listeners for new peers
        for (email in peerEmails) {
            if (!activeWeeklyListeners.containsKey(email)) {
                try {
                    val sanitized = DevicePresenceManager.sanitizeEmail(email)
                    val weeklyRef = database.getReference("FOCUS_TIMMER")
                        .child("USER")
                        .child(sanitized)

                    val listener = object : ValueEventListener {
                        override fun onDataChange(snapshot: DataSnapshot) {
                            val arenaSnapshot = snapshot.child("ARENA")
                            if (!snapshot.exists() || !arenaSnapshot.exists()) {
                                // Default or placeholder stats if the node doesn't exist yet
                                val defaultName = if (email == myEmail) {
                                    val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                                    val username = prefs.getString("current_username", "") ?: ""
                                    val cachedNickname = prefs.getString("user_nickname_$username", "") ?: ""
                                    val cachedName = prefs.getString("user_name_$username", "") ?: ""
                                    val resolved = if (cachedNickname.isNotEmpty()) {
                                        cachedNickname
                                    } else if (cachedName.isNotEmpty()) {
                                        cachedName
                                    } else if (username.isNotEmpty() && username != "Guest") {
                                        username
                                    } else {
                                        email.substringBefore("@")
                                    }
                                    resolved
                                } else {
                                    email.substringBefore("@")
                                }
                                val cachedEmoji = if (email == myEmail) {
                                    val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                                    val username = prefs.getString("current_username", "") ?: ""
                                    prefs.getString("user_emoji_$username", "") ?: ""
                                } else {
                                    PeerLiveSphereManager.peerLiveStates.value[email]?.customEmoji ?: ""
                                }
                                rawWeeklyStatsMap[email] = PeerWeeklyRawStats(
                                    email = email,
                                    displayName = defaultName,
                                    totalFocusMs = 0L,
                                    activeStreak = 0,
                                    topSubject = "None",
                                    customEmoji = cachedEmoji
                                )
                                computeAndEmitLeaderboard(myEmail)
                                return
                            }

                            val activeStreak = arenaSnapshot.child("ActiveStreak").getValue(Int::class.java) ?: 0
                            val rawName = arenaSnapshot.child("DisplayName").getValue(String::class.java)
                            val displayName = if (!rawName.isNullOrBlank()) rawName else email.substringBefore("@")

                            val rawEmoji = arenaSnapshot.child("CustomEmoji").getValue(String::class.java)
                                ?: arenaSnapshot.child("ProfileUrl").getValue(String::class.java)
                                ?: arenaSnapshot.child("ProfilePictureUrl").getValue(String::class.java)
                                ?: arenaSnapshot.child("profile_url").getValue(String::class.java)
                                ?: arenaSnapshot.child("avatar_url").getValue(String::class.java)
                                ?: arenaSnapshot.child("AvatarUrl").getValue(String::class.java)
                                ?: (if (email == myEmail) {
                                    val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                                    val username = prefs.getString("current_username", "") ?: ""
                                    prefs.getString("user_emoji_$username", "") ?: ""
                                } else {
                                    PeerLiveSphereManager.peerLiveStates.value[email]?.customEmoji ?: ""
                                })

                            val subBranchName = when (activePeriod) {
                                "TODAY" -> "TODAY"
                                "PAST_7_DAYS" -> "PAST_7_DAYS"
                                "PAST_30_DAYS" -> "PAST_30_DAYS"
                                else -> "ALL_TIME"
                            }

                            val subSnapshot = arenaSnapshot.child(subBranchName)
                            val totalFocusMs = subSnapshot.child("Total_Focus_Ms").getValue(Long::class.java) ?: 0L

                            // Find top subject from Subject_Breakdown under the sub snapshot
                            var topSubjectName = "None"
                            var maxFocusMs = 0L
                            val breakdownSnapshot = subSnapshot.child("Subject_Breakdown")
                            if (breakdownSnapshot.exists()) {
                                for (subChild in breakdownSnapshot.children) {
                                    val subName = subChild.key ?: continue
                                    val subMs = subChild.getValue(Long::class.java) ?: 0L
                                    if (subMs > maxFocusMs) {
                                        maxFocusMs = subMs
                                        topSubjectName = subName
                                    }
                                }
                            }

                            val lastUpdated = arenaSnapshot.child("Last_Updated").getValue(Long::class.java) ?: 0L
                            val baseXp = arenaSnapshot.child("XpScore").getValue(Int::class.java) ?: 0

                            var unconsumedShieldsCount = 0
                            val shieldsSnapshot = snapshot.child("SHIELDS")
                            if (shieldsSnapshot.exists()) {
                                for (shieldChild in shieldsSnapshot.children) {
                                    val isConsumed = shieldChild.child("Is_Consumed").getValue(Boolean::class.java)
                                        ?: shieldChild.child("is_consumed").getValue(Boolean::class.java)
                                        ?: false
                                    if (!isConsumed) {
                                        unconsumedShieldsCount++
                                    }
                                }
                            }

                            rawWeeklyStatsMap[email] = PeerWeeklyRawStats(
                                email = email,
                                displayName = displayName,
                                totalFocusMs = totalFocusMs,
                                activeStreak = activeStreak,
                                topSubject = topSubjectName,
                                customEmoji = rawEmoji ?: "",
                                xpScore = baseXp,
                                lastUpdated = lastUpdated,
                                baseOverallXp = baseXp,
                                unconsumedShieldsCount = unconsumedShieldsCount
                            )
                            computeAndEmitLeaderboard(myEmail)
                        }

                        override fun onCancelled(error: DatabaseError) {
                            Log.e(TAG, "Weekly stats listener cancelled for $email", error.toException())
                        }
                    }

                    weeklyRef.addValueEventListener(listener)
                    activeWeeklyListeners[email] = Pair(weeklyRef, listener)

                } catch (e: Exception) {
                    Log.e(TAG, "Error starting weekly stats listener for $email", e)
                }
            }
        }

        // Trigger an initial calculation in case some exist but no changes are fired
        computeAndEmitLeaderboard(myEmail)
    }

    private fun computeAndEmitLeaderboard(myEmail: String) {
        val rawList = rawWeeklyStatsMap.values
            .filter {
                it.displayName.lowercase() != "guest" &&
                !it.email.lowercase().contains("guest")
            }
            .sortedByDescending { it.totalFocusMs }
            .distinctBy { it.displayName.lowercase().trim() }
            .toList()
        if (rawList.isEmpty()) {
            _leaderboardFlow.value = emptyList()
            return
        }

        // Calculate XP and create ArenaRankModel
        val rankModels = rawList.map { raw ->
            val liveState = com.example.api.PeerLiveSphereManager.peerLiveStates.value[raw.email]
            
            // If we are looking at TODAY and there is a live state, resolve live focus milliseconds!
            val totalFocusMs = if (activePeriod == "TODAY" && liveState != null) {
                val todayStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
                val maxDeviceTodayMs = liveState.devices?.values
                    ?.filter { it.lastUpdateDate == todayStr || it.lastUpdateDate.isNullOrEmpty() }
                    ?.maxOfOrNull { it.todayFocusMs } ?: 0L
                val isRelaxing = liveState.status.equals("Relaxing", ignoreCase = true) || liveState.status.equals("IDLE", ignoreCase = true) || liveState.status.isEmpty()
                val activeSessionFocusMs = if (!isRelaxing) {
                    com.example.api.TimelineSyncEngine.calculateAccumulatedFocusMs(liveState.timeline, liveState.status)
                } else {
                    0L
                }
                maxDeviceTodayMs + activeSessionFocusMs
            } else {
                raw.totalFocusMs
            }

            val (decayedStreak, decayedXp) = if (raw.email == myEmail) {
                Pair(raw.activeStreak, raw.xpScore)
            } else {
                if (raw.lastUpdated > 0L) {
                    getDecayedStreakAndXp(raw.lastUpdated, raw.activeStreak, raw.xpScore, raw.unconsumedShieldsCount)
                } else {
                    Pair(raw.activeStreak, raw.xpScore)
                }
            }

            val xpScore = decayedXp

            ArenaRankModel(
                email = raw.email,
                displayName = raw.displayName,
                totalFocusMs = totalFocusMs,
                activeStreak = decayedStreak,
                xpScore = xpScore,
                topSubject = raw.topSubject,
                isMe = (raw.email == myEmail),
                customEmoji = raw.customEmoji
            )
        }

        // Sort descending by totalFocusMs, fallback to XP
        val sortedList = rankModels.sortedWith(
            compareByDescending<ArenaRankModel> { it.totalFocusMs }
                .thenByDescending { it.xpScore }
        )

        // Assign ranks (1-indexed)
        val finalRankedList = sortedList.mapIndexed { index, model ->
            model.copy(rank = index + 1)
        }

        _leaderboardFlow.value = finalRankedList
    }

    fun recomputeLeaderboard(myEmail: String) {
        computeAndEmitLeaderboard(myEmail)
    }

    fun getDecayedStreakAndXp(
        lastUpdated: Long,
        baseStreak: Int,
        baseXp: Int,
        unconsumedShieldsCount: Int
    ): Pair<Int, Int> {
        val now = System.currentTimeMillis()
        val daysPassed = ((now - lastUpdated) / (24L * 3600L * 1000L)).toInt()
        
        if (daysPassed <= 0) {
            return Pair(baseStreak, baseXp)
        }
        
        if (daysPassed >= 7) {
            return Pair(0, 0)
        }
        
        var currentStreak = baseStreak
        var shieldsLeft = unconsumedShieldsCount
        
        for (day in 1..daysPassed) {
            if (currentStreak >= 1) {
                if (shieldsLeft > 0) {
                    shieldsLeft--
                } else {
                    currentStreak = 0
                }
            } else {
                currentStreak = 0
            }
        }
        
        val revisedXp = if (baseStreak == currentStreak) {
            baseXp
        } else {
            val baseMultiplier = 1.0 + (0.1 * baseStreak)
            val revisedMultiplier = 1.0 + (0.1 * currentStreak)
            ((baseXp.toDouble() / baseMultiplier) * revisedMultiplier).toInt()
        }
        
        return Pair(currentStreak, revisedXp)
    }

    fun calculateXp(focusMs: Long, streak: Int): Int {
        val focusMins = focusMs / 60000L
        val tenHoursMins = 10 * 60L // 600
        val eightHoursMins = 8 * 60L // 480

        val ctx = appContext
        val myEmail = try {
            if (ctx != null) {
                val googleAccount = com.google.android.gms.auth.api.signin.GoogleSignIn.getLastSignedInAccount(ctx)
                val prefs = ctx.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                val savedUsername = prefs.getString("current_username", "Guest") ?: "Guest"
                googleAccount?.email ?: prefs.getString("user_email_$savedUsername", "") ?: "$savedUsername@gmail.com"
            } else {
                ""
            }
        } catch (e: Exception) {
            ""
        }

        val deductedXp = try {
            ctx?.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                ?.getInt("deducted_xp_${myEmail}", 0) ?: 0
        } catch (e: Exception) {
            0
        }

        val xpOffset = try {
            ctx?.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                ?.getInt("xp_offset_penalty_${myEmail}", 0) ?: 0
        } catch (e: Exception) {
            0
        }

        val extraCredits = try {
            ctx?.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                ?.getInt("extra_credits_xp", 0) ?: 0
        } catch (e: Exception) {
            0
        }

        fun getBaseXpWithRate(rate: Double): Double {
            return if (focusMins >= tenHoursMins) {
                focusMins.toDouble() / rate
            } else if (focusMins < eightHoursMins) {
                val baseEarned = focusMins / rate.toLong()
                val effectiveFocusMins = (focusMins / 10L) * 10L
                val penaltyMinutes = eightHoursMins - effectiveFocusMins
                val penaltyXp = penaltyMinutes / 10L
                baseEarned.toDouble() - penaltyXp.toDouble()
            } else {
                val baseEarned = eightHoursMins.toDouble() / rate
                val excessMins = focusMins - eightHoursMins
                val extraEarned = excessMins / 10L
                baseEarned + extraEarned.toDouble()
            }
        }

        var baseXp = getBaseXpWithRate(15.0)
        var totalBaseXp = baseXp + extraCredits - (deductedXp + xpOffset)

        // If XP is in negative, rate increases to 10 mins = 1 XP
        if (totalBaseXp < 0.0) {
            baseXp = getBaseXpWithRate(10.0)
            totalBaseXp = baseXp + extraCredits - (deductedXp + xpOffset)
        }

        return (totalBaseXp * (1.0 + (0.1 * streak))).let { 
            if (it.isNaN() || it.isInfinite()) 0 else it.toInt()
        }
    }
}
