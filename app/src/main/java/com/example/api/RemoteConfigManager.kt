package com.example.api

import android.content.Context
import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SduiPreferences(
    val rawBannerText: String = "CA Inter Exams on 1st Sep 2026! Grind!",
    val isArenaEnabled: Boolean = true,
    val defaultThemeOverride: String = "SLATE_DARK"
) {
    val motivationalBannerText: String
        get() {
            try {
                val targetCal = java.util.Calendar.getInstance().apply {
                    set(2026, java.util.Calendar.SEPTEMBER, 1, 0, 0, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                }
                val diffMs = targetCal.timeInMillis - System.currentTimeMillis()
                val days = if (diffMs > 0) {
                    // Safe division for days left
                    diffMs / (1000L * 60L * 60L * 24L)
                } else {
                    0L
                }
                return "CA Inter Exams in $days Days! Grind!"
            } catch (e: Exception) {
                return "CA Inter Exams on 1st Sep 2026! Grind!"
            }
        }
}

object RemoteConfigManager {
    private const val TAG = "RemoteConfigManager"
    private const val PREFS_NAME = "sdui_prefs"
    private const val KEY_BANNER_TEXT = "motivational_banner_text"
    private const val KEY_ARENA_ENABLED = "is_arena_enabled"
    private const val KEY_THEME_OVERRIDE = "default_theme_override"

    private val _sduiPreferences = MutableStateFlow(SduiPreferences())
    val sduiPreferences: StateFlow<SduiPreferences> = _sduiPreferences.asStateFlow()

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cachedBanner = prefs.getString(KEY_BANNER_TEXT, "CA Inter Exams on 1st Sep 2026! Grind!") ?: "CA Inter Exams on 1st Sep 2026! Grind!"
        val cachedArena = prefs.getBoolean(KEY_ARENA_ENABLED, true)
        val cachedTheme = prefs.getString(KEY_THEME_OVERRIDE, "SLATE_DARK") ?: "SLATE_DARK"

        _sduiPreferences.value = SduiPreferences(
            rawBannerText = cachedBanner,
            isArenaEnabled = cachedArena,
            defaultThemeOverride = cachedTheme
        )

        // Sync with Realtime Database
        try {
            Firebase.ensureFirebaseInitialized(context)
            val dbUrl = FirebaseConfig.getDatabaseUrl(context)
            if (dbUrl.isNotEmpty()) {
                val database = FirebaseDatabase.getInstance(dbUrl)
                val ref = database.getReference("APP_CONFIG/SDUI_PREFERENCES")
                ref.addValueEventListener(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        if (snapshot.exists()) {
                            val banner = snapshot.child("motivational_banner_text").getValue(String::class.java) ?: "CA Inter Exams on 1st Sep 2026! Grind!"
                            val arena = snapshot.child("is_arena_enabled").getValue(Boolean::class.java) ?: true
                            val theme = snapshot.child("default_theme_override").getValue(String::class.java) ?: "SLATE_DARK"

                            prefs.edit().apply {
                                putString(KEY_BANNER_TEXT, banner)
                                putBoolean(KEY_ARENA_ENABLED, arena)
                                putString(KEY_THEME_OVERRIDE, theme)
                            }.apply()

                            _sduiPreferences.value = SduiPreferences(
                                rawBannerText = banner,
                                isArenaEnabled = arena,
                                defaultThemeOverride = theme
                            )
                            Log.d(TAG, "SDUI Preferences updated: $banner, Arena=$arena, Theme=$theme")
                        } else {
                            // Seed default config on RTDB if it doesn't exist
                            ref.child("motivational_banner_text").setValue("CA Inter Exams on 1st Sep 2026! Grind!")
                            ref.child("is_arena_enabled").setValue(true)
                            ref.child("default_theme_override").setValue("SLATE_DARK")
                        }
                    }

                    override fun onCancelled(error: DatabaseError) {
                        Log.e(TAG, "Failed to read SDUI config: ${error.message}")
                    }
                })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Firebase not ready for SDUI sync", e)
        }
    }
}
