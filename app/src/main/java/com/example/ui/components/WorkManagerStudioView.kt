package com.example.ui.components

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.core.os.HandlerCompat
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import androidx.concurrent.futures.CallbackToFutureAdapter
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.*
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.session.SessionCommand
import com.example.service.MediaPlaybackService
import com.example.receiver.BootReceiver
import com.example.receiver.TaskReminderReceiver
import com.example.worker.*
import java.util.UUID

enum class SelectedWorkerType(val displayName: String, val badgeColor: Color, val description: String) {
    WORKER("Worker", Color(0xFF38BDF8), "Synchronous execution on background Executor thread pool"),
    COROUTINE_WORKER("CoroutineWorker", Color(0xFF10B981), "Recommended for Kotlin. Suspending doWork() with Dispatchers.IO"),
    LISTENABLE_WORKER("ListenableWorker", Color(0xFFF59E0B), "Callback-based asynchronous APIs with ListenableFuture & Cancellation listeners"),
    RX_WORKER("RxWorker", Color(0xFFA855F7), "Reactive streams execution pipeline returning Single<Result>"),
    REMOTE_WORKER("RemoteWorker", Color(0xFFEC4899), "Multi-process execution isolated in separate process (:worker1)"),
    MEDIA3("Media3 Session", Color(0xFFE11D48), "ExoPlayer + MediaSessionService + MediaController architecture")
}

data class LogEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: String = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date()),
    val workerType: SelectedWorkerType,
    val message: String,
    val isError: Boolean = false
)

fun parseStopReason(stopReason: Int): String {
    return when (stopReason) {
        WorkInfo.STOP_REASON_UNKNOWN -> "STOP_REASON_UNKNOWN (-128): Work was stopped for an unspecified reason."
        WorkInfo.STOP_REASON_CANCELLED_BY_APP -> "STOP_REASON_CANCELLED_BY_APP (1): Explicitly cancelled by app via cancelWorkById/cancelAllWork."
        WorkInfo.STOP_REASON_TIMEOUT -> "STOP_REASON_TIMEOUT (3): Task exceeded execution time limit. Warning: Frequent timeouts put app in Android 14 Restricted Standby Bucket!"
        WorkInfo.STOP_REASON_DEVICE_STATE -> "STOP_REASON_DEVICE_STATE (4): Device state changed (e.g. low battery / thermal throttling)."
        WorkInfo.STOP_REASON_CONSTRAINT_CHARGING -> "STOP_REASON_CONSTRAINT_CHARGING (5): Charging constraint no longer satisfied."
        WorkInfo.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW -> "STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW (6): Battery dropped below low threshold."
        WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY -> "STOP_REASON_CONSTRAINT_CONNECTIVITY (7): Network connectivity condition lost."
        WorkInfo.STOP_REASON_SYSTEM_PROCESSING -> "STOP_REASON_SYSTEM_PROCESSING (8): System preempted background work for higher priority task."
        else -> "Stop Reason Code $stopReason: Task was stopped by WorkManager runtime."
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkManagerStudioView() {
    val context = LocalContext.current
    val workManager = remember { WorkManager.getInstance(context) }
    val powerManager = remember { context.getSystemService(Context.POWER_SERVICE) as PowerManager }

    var selectedType by remember { mutableStateOf(SelectedWorkerType.COROUTINE_WORKER) }
    var targetUrl by remember { mutableStateOf("https://www.google.com") }
    var repeatCount by remember { mutableFloatStateOf(100f) }

    // Constraints & Advanced Best Practices
    var requireCharging by remember { mutableStateOf(false) }
    var requireUnmeteredWifi by remember { mutableStateOf(false) }
    var requireBatteryNotLow by remember { mutableStateOf(true) }
    var requireDeviceIdle by remember { mutableStateOf(false) }
    var isExpeditedWork by remember { mutableStateOf(false) }

    var currentWorkId by remember { mutableStateOf<UUID?>(null) }
    var currentWorkState by remember { mutableStateOf("IDLE") }
    var currentProgress by remember { mutableIntStateOf(0) }
    var downloadedItems by remember { mutableIntStateOf(0) }
    var lastStopReasonText by remember { mutableStateOf<String?>(null) }

    // Wake Lock State
    var activeWakeLock by remember { mutableStateOf<PowerManager.WakeLock?>(null) }
    var wakeLockHeld by remember { mutableStateOf(false) }
    var wakeLockTagInput by remember { mutableStateOf("com.example.app::BackgroundSyncTask") }
    var wakeLockTimeoutSec by remember { mutableFloatStateOf(10f) }
    var wakeLockLogText by remember { mutableStateOf("Wake Lock inactive") }

    // AlarmManager State
    val alarmManager = remember { context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager }
    var canScheduleExact by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                alarmManager?.canScheduleExactAlarms() ?: false
            } else true
        )
    }
    var selectedAlarmCategory by remember { mutableStateOf("INEXACT") } // "INEXACT", "EXACT", "ALARM_CLOCK"
    var selectedClockType by remember { mutableStateOf("ELAPSED_REALTIME") } // "ELAPSED_REALTIME", "RTC"
    var alarmDelaySec by remember { mutableFloatStateOf(10f) }
    var alarmTitleInput by remember { mutableStateOf("Scheduled Water Reminder") }
    var activeAlarmScheduled by remember { mutableStateOf(false) }

    // Boot Receiver State
    val bootComponent = remember { ComponentName(context, BootReceiver::class.java) }
    var isBootReceiverEnabled by remember {
        mutableStateOf(
            context.packageManager.getComponentEnabledSetting(bootComponent) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        )
    }

    // Java ThreadPoolExecutor & Handler State
    val coresCount = remember { Runtime.getRuntime().availableProcessors() }
    val customThreadPool = remember {
        ThreadPoolExecutor(
            coresCount,
            coresCount,
            1L,
            TimeUnit.SECONDS,
            LinkedBlockingQueue()
        )
    }
    val mainThreadHandler = remember { HandlerCompat.createAsync(Looper.getMainLooper()) }
    var threadTaskParamInput by remember { mutableStateOf("UserLoginPayload_#10492") }
    var threadTaskDurationSec by remember { mutableFloatStateOf(2f) }
    var isThreadTaskRunning by remember { mutableStateOf(false) }

    // Guava ListenableFuture State
    var futureParamInput by remember { mutableStateOf("FetchQueryResult_ID_99") }
    var futureShouldFail by remember { mutableStateOf(false) }
    var futureStatusText by remember { mutableStateOf("Idle") }

    val logs = remember { mutableStateListOf<LogEntry>() }

    fun addLog(type: SelectedWorkerType, msg: String, isError: Boolean = false) {
        logs.add(0, LogEntry(workerType = type, message = msg, isError = isError))
        if (logs.size > 80) logs.removeAt(logs.lastIndex)
    }

    // Jetpack Media3 State & Async MediaController Listener
    var mediaController by remember { mutableStateOf<MediaController?>(null) }
    var mediaCurrentTitle by remember { mutableStateOf("No Track Playing") }
    var mediaCurrentArtist by remember { mutableStateOf("Media3 Session") }
    var mediaIsPlaying by remember { mutableStateOf(false) }
    var mediaPlaybackStateText by remember { mutableStateOf("STATE_IDLE") }
    var mediaCurrentPositionMs by remember { mutableLongStateOf(0L) }
    var mediaDurationMs by remember { mutableLongStateOf(0L) }
    var mediaRepeatMode by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    var mediaShuffleMode by remember { mutableStateOf(false) }

    DisposableEffect(context) {
        val sessionToken = SessionToken(
            context,
            ComponentName(context, MediaPlaybackService::class.java)
        )
        val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()

        controllerFuture.addListener(
            {
                try {
                    val controller = controllerFuture.get()
                    mediaController = controller

                    val listener = object : Player.Listener {
                        override fun onEvents(player: Player, events: Player.Events) {
                            mediaIsPlaying = player.isPlaying
                            mediaRepeatMode = player.repeatMode
                            mediaShuffleMode = player.shuffleModeEnabled
                            mediaCurrentPositionMs = player.currentPosition
                            mediaDurationMs = player.duration.coerceAtLeast(0L)

                            val metadata = player.currentMediaItem?.mediaMetadata
                            mediaCurrentTitle = metadata?.title?.toString() ?: "No Track Playing"
                            mediaCurrentArtist = metadata?.artist?.toString() ?: "Media3 Session"

                            mediaPlaybackStateText = when (player.playbackState) {
                                Player.STATE_IDLE -> "STATE_IDLE"
                                Player.STATE_BUFFERING -> "STATE_BUFFERING"
                                Player.STATE_READY -> "STATE_READY"
                                Player.STATE_ENDED -> "STATE_ENDED"
                                else -> "UNKNOWN"
                            }
                        }
                    }

                    controller.addListener(listener)

                    // Initial state sync
                    mediaIsPlaying = controller.isPlaying
                    mediaRepeatMode = controller.repeatMode
                    mediaShuffleMode = controller.shuffleModeEnabled
                    mediaCurrentPositionMs = controller.currentPosition
                    mediaDurationMs = controller.duration.coerceAtLeast(0L)
                    val metadata = controller.currentMediaItem?.mediaMetadata
                    if (metadata != null) {
                        mediaCurrentTitle = metadata.title?.toString() ?: "No Track Playing"
                        mediaCurrentArtist = metadata.artist?.toString() ?: "Media3 Session"
                    }
                    mediaPlaybackStateText = when (controller.playbackState) {
                        Player.STATE_IDLE -> "STATE_IDLE"
                        Player.STATE_BUFFERING -> "STATE_BUFFERING"
                        Player.STATE_READY -> "STATE_READY"
                        Player.STATE_ENDED -> "STATE_ENDED"
                        else -> "UNKNOWN"
                    }

                    addLog(SelectedWorkerType.MEDIA3, "Connected MediaController to MediaPlaybackService via SessionToken")
                } catch (e: Exception) {
                    addLog(SelectedWorkerType.MEDIA3, "MediaController connection failed: ${e.message}", isError = true)
                }
            },
            context.mainExecutor
        )

        onDispose {
            MediaController.releaseFuture(controllerFuture)
            mediaController = null
        }
    }

    // Observe active WorkRequest state changes and stop reasons
    LaunchedEffect(currentWorkId) {
        val workId = currentWorkId ?: return@LaunchedEffect
        workManager.getWorkInfoByIdLiveData(workId).observeForever { workInfo ->
            if (workInfo != null) {
                currentWorkState = workInfo.state.name
                val progress = workInfo.progress.getInt("progress", 0)
                val downloaded = workInfo.progress.getInt("downloaded", 0)
                currentProgress = progress
                downloadedItems = downloaded

                // Check stop reason if work was cancelled or stopped
                if (workInfo.state == WorkInfo.State.CANCELLED || workInfo.state == WorkInfo.State.FAILED) {
                    val stopReasonCode = workInfo.stopReason
                    val reasonDesc = parseStopReason(stopReasonCode)
                    lastStopReasonText = reasonDesc
                    addLog(selectedType, "STOP DETECTED -> $reasonDesc", isError = true)
                }

                val stateMsg = "WorkState: ${workInfo.state.name} | Progress: $progress% ($downloaded items)"
                addLog(selectedType, stateMsg, isError = workInfo.state == WorkInfo.State.FAILED)

                if (workInfo.state.isFinished) {
                    val outputData = workInfo.outputData
                    val totalDownloaded = outputData.getInt("total_downloaded", downloaded)
                    addLog(selectedType, "Finished work ${workInfo.state.name}! Total output: $totalDownloaded items.")
                }
            }
        }
    }

    Scaffold(
        containerColor = Color(0xFF090A0F),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFF38BDF8).copy(alpha = 0.15f),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Build,
                                    contentDescription = "WorkManager Icon",
                                    tint = Color(0xFF38BDF8),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text("WorkManager & Wake Lock Studio", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Text("Threading Primitives, Constraints & WakeLock Best Practices", color = Color.Gray, fontSize = 11.sp)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0D0F18))
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // On-Demand Custom Initialization Banner
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B))
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Speed, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(28.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("On-Demand Custom Initialization Active", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(color = Color(0xFF10B981).copy(alpha = 0.2f), shape = RoundedCornerShape(4.dp)) {
                                Text("Enabled", color = Color(0xFF10B981), fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                            }
                        }
                        Text(
                            "WorkManagerInitializer removed from AndroidManifest.xml. Application implements Configuration.Provider with 8-thread fixed Executor pool.",
                            color = Color.LightGray,
                            fontSize = 11.sp,
                            lineHeight = 14.sp
                        )
                    }
                }
            }

            // Section 1: Primitive Selection
            Text("1. Select Work Primitive", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectedWorkerType.entries.forEach { type ->
                    val isSelected = selectedType == type
                    Surface(
                        onClick = { selectedType = type },
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) type.badgeColor.copy(alpha = 0.18f) else Color(0xFF131520),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isSelected) type.badgeColor else Color(0xFF222638)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { selectedType = type },
                                colors = RadioButtonDefaults.colors(selectedColor = type.badgeColor)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = type.displayName,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        color = type.badgeColor.copy(alpha = 0.2f),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = type.name,
                                            color = type.badgeColor,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = type.description,
                                    color = Color.Gray,
                                    fontSize = 11.sp,
                                    lineHeight = 14.sp
                                )
                            }
                        }
                    }
                }
            }

            // Section 2: Constraints & Battery Optimization Settings
            Text("2. Constraints & Battery Optimization Settings", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)

            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF12141F)),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E2235))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Work Constraints (Combine & Optimize Battery/Network)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)

                    ConstraintToggleItem(
                        title = "Requires Charging",
                        description = "Only execute when device battery is charging",
                        checked = requireCharging,
                        onCheckedChange = { requireCharging = it },
                        icon = Icons.Default.BatteryChargingFull
                    )

                    ConstraintToggleItem(
                        title = "Requires Unmetered Network (WiFi)",
                        description = "Avoids mobile data consumption",
                        checked = requireUnmeteredWifi,
                        onCheckedChange = { requireUnmeteredWifi = it },
                        icon = Icons.Default.Wifi
                    )

                    ConstraintToggleItem(
                        title = "Requires Battery Not Low",
                        description = "Prevents battery drain under low power mode",
                        checked = requireBatteryNotLow,
                        onCheckedChange = { requireBatteryNotLow = it },
                        icon = Icons.Default.BatteryFull
                    )

                    ConstraintToggleItem(
                        title = "Requires Device Idle",
                        description = "For low-priority background maintenance tasks",
                        checked = requireDeviceIdle,
                        onCheckedChange = { requireDeviceIdle = it },
                        icon = Icons.Default.PowerSettingsNew
                    )

                    HorizontalDivider(color = Color(0xFF222638), thickness = 1.dp)

                    // Expedited Work Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Bolt, contentDescription = null, tint = Color(0xFFF59E0B))
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("Expedited Task (Time-Sensitive)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text("Prioritizes execution & overrides standard power restrictions", color = Color.Gray, fontSize = 11.sp)
                            }
                        }
                        Switch(
                            checked = isExpeditedWork,
                            onCheckedChange = { isExpeditedWork = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.Black,
                                checkedTrackColor = Color(0xFFF59E0B)
                            )
                        )
                    }
                }
            }

            // Section 3: Input Parameters & Actions Card
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF12141F)),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E2235))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("3. Execution Parameters & Trigger", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)

                    OutlinedTextField(
                        value = targetUrl,
                        onValueChange = { targetUrl = it },
                        label = { Text("Target URL") },
                        leadingIcon = { Icon(Icons.Default.Language, contentDescription = null, tint = Color(0xFF38BDF8)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF38BDF8),
                            unfocusedBorderColor = Color(0xFF222638),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Repeat Iterations", color = Color.LightGray, fontSize = 12.sp)
                            Text("${repeatCount.toInt()} times", color = selectedType.badgeColor, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        Slider(
                            value = repeatCount,
                            onValueChange = { repeatCount = it },
                            valueRange = 10f..200f,
                            steps = 18,
                            colors = SliderDefaults.colors(
                                thumbColor = selectedType.badgeColor,
                                activeTrackColor = selectedType.badgeColor
                            )
                        )
                    }

                    // Action Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                val count = repeatCount.toInt()
                                val data = workDataOf("url" to targetUrl, "repeat_count" to count)

                                // Build constraints
                                val constraints = Constraints.Builder()
                                    .setRequiresCharging(requireCharging)
                                    .setRequiredNetworkType(if (requireUnmeteredWifi) NetworkType.UNMETERED else NetworkType.NOT_REQUIRED)
                                    .setRequiresBatteryNotLow(requireBatteryNotLow)
                                    .setRequiresDeviceIdle(requireDeviceIdle)
                                    .build()

                                when (selectedType) {
                                    SelectedWorkerType.WORKER -> {
                                        val builder = OneTimeWorkRequestBuilder<DownloadWorker>()
                                            .setInputData(data)
                                            .setConstraints(constraints)
                                            .addTag("DownloadWorker")
                                        if (isExpeditedWork) {
                                            builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                                        }
                                        val request = builder.build()
                                        workManager.enqueue(request)
                                        currentWorkId = request.id
                                        addLog(selectedType, "Enqueued DownloadWorker (Worker) [Expedited=$isExpeditedWork]")
                                    }
                                    SelectedWorkerType.COROUTINE_WORKER -> {
                                        val builder = OneTimeWorkRequestBuilder<CoroutineDownloadWorker>()
                                            .setInputData(data)
                                            .setConstraints(constraints)
                                            .addTag("CoroutineDownloadWorker")
                                        if (isExpeditedWork) {
                                            builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                                        }
                                        val request = builder.build()
                                        workManager.enqueue(request)
                                        currentWorkId = request.id
                                        addLog(selectedType, "Enqueued CoroutineDownloadWorker (CoroutineWorker) [Expedited=$isExpeditedWork]")
                                    }
                                    SelectedWorkerType.LISTENABLE_WORKER -> {
                                        val builder = OneTimeWorkRequestBuilder<CallbackWorker>()
                                            .setInputData(data)
                                            .setConstraints(constraints)
                                            .addTag("CallbackWorker")
                                        if (isExpeditedWork) {
                                            builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                                        }
                                        val request = builder.build()
                                        workManager.enqueue(request)
                                        currentWorkId = request.id
                                        addLog(selectedType, "Enqueued CallbackWorker (ListenableWorker) [Expedited=$isExpeditedWork]")
                                    }
                                    SelectedWorkerType.RX_WORKER -> {
                                        val builder = OneTimeWorkRequestBuilder<RxDownloadWorker>()
                                            .setInputData(data)
                                            .setConstraints(constraints)
                                            .addTag("RxDownloadWorker")
                                        val request = builder.build()
                                        workManager.enqueue(request)
                                        currentWorkId = request.id
                                        addLog(selectedType, "Enqueued RxDownloadWorker (RxWorker)")
                                    }
                                    SelectedWorkerType.REMOTE_WORKER -> {
                                        val request = ExampleRemoteCoroutineWorker.buildWorkRequest(context, targetUrl, count)
                                        workManager.enqueue(request)
                                        currentWorkId = request.id
                                        addLog(selectedType, "Enqueued ExampleRemoteCoroutineWorker (:worker1 process)")
                                    }
                                    SelectedWorkerType.MEDIA3 -> {
                                        addLog(selectedType, "Jetpack Media3 playback active in Section 10 studio")
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = selectedType.badgeColor),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Enqueue Work", color = Color.Black, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                currentWorkId?.let { id ->
                                    workManager.cancelWorkById(id)
                                    addLog(selectedType, "Explicitly cancelled work request $id. Testing onStopped() & getStopReason()...")
                                } ?: run {
                                    workManager.cancelAllWork()
                                    addLog(selectedType, "Cancelled all enqueued WorkManager tasks.")
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Stop Work", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Section 4: PowerManager Wake Lock Studio & Inspector
            Text("4. PowerManager Wake Lock Studio & Best Practices", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)

            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF13111C)),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2C243B))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.LockClock, contentDescription = null, tint = Color(0xFFA855F7), modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Wake Lock Management", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }

                        Surface(
                            color = if (wakeLockHeld) Color(0xFF10B981).copy(alpha = 0.2f) else Color(0xFF64748B).copy(alpha = 0.2f),
                            shape = RoundedCornerShape(6.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (wakeLockHeld) Color(0xFF10B981) else Color(0xFF64748B))
                        ) {
                            Text(
                                text = if (wakeLockHeld) "ACTIVE (HELD)" else "RELEASED",
                                color = if (wakeLockHeld) Color(0xFF10B981) else Color(0xFF94A3B8),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    // Wake Lock Tag Configuration
                    OutlinedTextField(
                        value = wakeLockTagInput,
                        onValueChange = { wakeLockTagInput = it },
                        label = { Text("WakeLock Tag (Include Package/Class, No PII)") },
                        leadingIcon = { Icon(Icons.Default.Tag, contentDescription = null, tint = Color(0xFFA855F7)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFFA855F7),
                            unfocusedBorderColor = Color(0xFF2C243B),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Timeout Duration (acquire(timeout))", color = Color.LightGray, fontSize = 12.sp)
                            Text("${wakeLockTimeoutSec.toInt()} seconds", color = Color(0xFFA855F7), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        Slider(
                            value = wakeLockTimeoutSec,
                            onValueChange = { wakeLockTimeoutSec = it },
                            valueRange = 2f..30f,
                            steps = 13,
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFFA855F7),
                                activeTrackColor = Color(0xFFA855F7)
                            )
                        )
                    }

                    // Action Buttons for Wake Lock
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                try {
                                    val tag = if (wakeLockTagInput.isBlank()) "com.example.app::WakelockTag" else wakeLockTagInput
                                    val wl = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag)
                                    val timeoutMs = (wakeLockTimeoutSec * 1000).toLong()
                                    
                                    // Acquire with explicit timeout & try-finally pattern
                                    wl.acquire(timeoutMs)
                                    activeWakeLock = wl
                                    wakeLockHeld = true
                                    wakeLockLogText = "Acquired WakeLock '$tag' with ${wakeLockTimeoutSec.toInt()}s timeout"
                                    addLog(SelectedWorkerType.COROUTINE_WORKER, wakeLockLogText)
                                } catch (e: Exception) {
                                    wakeLockLogText = "Failed to acquire WakeLock: ${e.message}"
                                    addLog(SelectedWorkerType.COROUTINE_WORKER, wakeLockLogText, isError = true)
                                }
                            },
                            enabled = !wakeLockHeld,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFA855F7)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Acquire WakeLock", color = Color.White, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                activeWakeLock?.let { wl ->
                                    try {
                                        if (wl.isHeld) {
                                            wl.release()
                                        }
                                        wakeLockHeld = false
                                        activeWakeLock = null
                                        wakeLockLogText = "WakeLock explicitly released via try-finally pattern"
                                        addLog(SelectedWorkerType.COROUTINE_WORKER, wakeLockLogText)
                                    } catch (e: Exception) {
                                        wakeLockLogText = "Error releasing WakeLock: ${e.message}"
                                        addLog(SelectedWorkerType.COROUTINE_WORKER, wakeLockLogText, isError = true)
                                    }
                                }
                            },
                            enabled = wakeLockHeld,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.LockOpen, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Release Lock", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }

                    // WakeLock Log Callout
                    Surface(
                        color = Color(0xFF1E1B2E),
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3B2D54))
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFFA855F7), modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(wakeLockLogText, color = Color.LightGray, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }

                    HorizontalDivider(color = Color(0xFF2C243B), thickness = 1.dp)

                    // Wake Lock Best Practices & Tagging Guidelines
                    Text("Wake Lock Best Practices & Debugging Guidelines", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        GuideItem(
                            title = "Always set a timeout with acquire(timeoutMs)",
                            desc = "Never call un-timed acquire(). Setting a timeout prevents endless battery drain if your worker or app crashes."
                        )
                        GuideItem(
                            title = "Always release in a try-finally block",
                            desc = "Wrap execution in try { acquire(); doWork() } finally { release() } to guarantee cleanup on exception."
                        )
                        GuideItem(
                            title = "Proper Tag Naming (No PII / No Reflection)",
                            desc = "Include package & class name (e.g. 'com.example.app::SyncTask'). Exclude PII (like emails) which get obfuscated as _UNKNOWN, and avoid dynamic getName()."
                        )
                        GuideItem(
                            title = "Foreground Service Visibility",
                            desc = "If holding a wake lock directly, pair it with a Foreground Service notification so the user is aware of active battery usage."
                        )
                        GuideItem(
                            title = "Debug with dumpsys batterystats",
                            desc = "Run 'adb shell dumpsys batterystats' or inspect Android Studio Background Task Inspector to audit wake lock hold duration."
                        )
                    }
                }
            }

            // Live Execution Monitor
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF10121A)),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E2235))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Live Execution Monitor", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        
                        val stateColor = when (currentWorkState) {
                            "RUNNING" -> Color(0xFF10B981)
                            "ENQUEUED" -> Color(0xFF38BDF8)
                            "SUCCEEDED" -> Color(0xFF3B82F6)
                            "CANCELLED" -> Color(0xFFF59E0B)
                            "FAILED" -> Color(0xFFEF4444)
                            else -> Color.Gray
                        }

                        Surface(
                            color = stateColor.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(6.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, stateColor)
                        ) {
                            Text(
                                text = currentWorkState,
                                color = stateColor,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    LinearProgressIndicator(
                        progress = { currentProgress / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = selectedType.badgeColor,
                        trackColor = Color(0xFF1E2235)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Progress: $currentProgress%", color = Color.LightGray, fontSize = 12.sp)
                        Text("Downloaded: $downloadedItems items", color = Color.LightGray, fontSize = 12.sp)
                    }

                    // Stop Reason Inspector Callout
                    lastStopReasonText?.let { reason ->
                        Surface(
                            color = Color(0xFF7F1D1D).copy(alpha = 0.3f),
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF4444))
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text("WorkInfo.getStopReason() Inspector:", color = Color(0xFFFCA5A5), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                    Text(reason, color = Color.White, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }

            // Real-Time Event Log Console
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0B0C12)),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1B1E2E))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Terminal, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Execution Console Logs", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                        if (logs.isNotEmpty()) {
                            TextButton(onClick = { logs.clear() }) {
                                Text("Clear", color = Color.Gray, fontSize = 11.sp)
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .background(Color(0xFF05060A), shape = RoundedCornerShape(8.dp))
                            .border(1.dp, Color(0xFF151824), shape = RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        if (logs.isEmpty()) {
                            Text(
                                "No active execution logs yet. Tap 'Enqueue Work' above or test WakeLock to start logging.",
                                color = Color.DarkGray,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                items(logs, key = { it.id }) { log ->
                                    Row(verticalAlignment = Alignment.Top) {
                                        Text(
                                            text = "[${log.timestamp}]",
                                            color = Color.Gray,
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "${log.workerType.displayName}:",
                                            color = log.workerType.badgeColor,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = log.message,
                                            color = if (log.isError) Color(0xFFEF4444) else Color(0xFFE2E8F0),
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // System Attribution Reference Table
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF10121A)),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E2235))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("System Wake Lock Attribution Reference", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("System APIs create wake locks attributed to your app. Common tags in dumpsys logs:", color = Color.Gray, fontSize = 11.sp)

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SystemAttributionRow("*alarm*", "AlarmManager broadcasts (onReceive)")
                        SystemAttributionRow("GOOGLE_C2DM / GCM_MESSAGE", "Firebase Cloud Messaging delivery")
                        SystemAttributionRow("*job* / SystemJobService", "JobScheduler & WorkManager workers")
                        SystemAttributionRow("AudioMix / AudioOffload", "Audio & Media playback / capture APIs")
                        SystemAttributionRow("*location* / NlpWakeLock", "LocationManager & FusedLocationProviderClient")
                        SystemAttributionRow("_UNKNOWN", "Sanitized tag containing PII (avoid email/IDs in tag)")
                    }
                }
            }

            // Section 5: AlarmManager & System Alarms Studio
            Text("5. AlarmManager & System Alarms Studio", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)

            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF131722)),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF222938))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Alarm, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("AlarmManager & Exact Alarms", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }

                        Surface(
                            color = if (canScheduleExact) Color(0xFF10B981).copy(alpha = 0.2f) else Color(0xFFEF4444).copy(alpha = 0.2f),
                            shape = RoundedCornerShape(6.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (canScheduleExact) Color(0xFF10B981) else Color(0xFFEF4444))
                        ) {
                            Text(
                                text = if (canScheduleExact) "EXACT PERMITTED" else "EXACT DENIED",
                                color = if (canScheduleExact) Color(0xFF10B981) else Color(0xFFEF4444),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !canScheduleExact) {
                        OutlinedButton(
                            onClick = {
                                try {
                                    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                        data = android.net.Uri.parse("package:${context.packageName}")
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    addLog(SelectedWorkerType.WORKER, "Failed to open Exact Alarm Settings: ${e.message}", isError = true)
                                }
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFF59E0B)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Request SCHEDULE_EXACT_ALARM Permission in Settings", fontSize = 12.sp)
                        }
                    }

                    // Alarm Category Selection
                    Text("Alarm Precision Mode:", color = Color.LightGray, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("INEXACT" to "Inexact (Battery Friendly)", "EXACT" to "Exact (Precise)", "ALARM_CLOCK" to "AlarmClock (User-Visible)").forEach { (key, label) ->
                            val isSel = selectedAlarmCategory == key
                            Surface(
                                onClick = { selectedAlarmCategory = key },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSel) Color(0xFFF59E0B).copy(alpha = 0.25f) else Color(0xFF1E2433),
                                border = androidx.compose.foundation.BorderStroke(1.dp, if (isSel) Color(0xFFF59E0B) else Color(0xFF2C354A)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(
                                    modifier = Modifier.padding(8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(key, color = if (isSel) Color(0xFFF59E0B) else Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                    Text(label, color = Color.Gray, fontSize = 9.sp)
                                }
                            }
                        }
                    }

                    // Clock Type Selection
                    Text("Clock Base:", color = Color.LightGray, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("ELAPSED_REALTIME" to "Elapsed Realtime (System Boot relative)", "RTC" to "RTC Wall Clock (UTC Time)").forEach { (key, label) ->
                            val isSel = selectedClockType == key
                            Surface(
                                onClick = { selectedClockType = key },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSel) Color(0xFF38BDF8).copy(alpha = 0.2f) else Color(0xFF1E2433),
                                border = androidx.compose.foundation.BorderStroke(1.dp, if (isSel) Color(0xFF38BDF8) else Color(0xFF2C354A)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(
                                    modifier = Modifier.padding(8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(key, color = if (isSel) Color(0xFF38BDF8) else Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                    Text(label, color = Color.Gray, fontSize = 9.sp)
                                }
                            }
                        }
                    }

                    // Alarm Parameters
                    OutlinedTextField(
                        value = alarmTitleInput,
                        onValueChange = { alarmTitleInput = it },
                        label = { Text("Alarm Notification Title") },
                        leadingIcon = { Icon(Icons.Default.Notifications, contentDescription = null, tint = Color(0xFFF59E0B)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFFF59E0B),
                            unfocusedBorderColor = Color(0xFF2C354A),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Trigger Delay / Countdown", color = Color.LightGray, fontSize = 12.sp)
                            Text("${alarmDelaySec.toInt()} seconds", color = Color(0xFFF59E0B), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        Slider(
                            value = alarmDelaySec,
                            onValueChange = { alarmDelaySec = it },
                            valueRange = 5f..120f,
                            steps = 22,
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFFF59E0B),
                                activeTrackColor = Color(0xFFF59E0B)
                            )
                        )
                    }

                    // Schedule / Cancel Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                val am = alarmManager ?: return@Button
                                val intent = Intent(context, TaskReminderReceiver::class.java).apply {
                                    putExtra("TASK_ID", 88888)
                                    putExtra("RAW_TASK_ID", 88888)
                                    putExtra("TASK_TITLE", alarmTitleInput)
                                    putExtra("TASK_PRIORITY", "HIGH")
                                    putExtra("TASK_TIME", "In ${alarmDelaySec.toInt()}s")
                                }
                                val pendingIntent = PendingIntent.getBroadcast(
                                    context,
                                    88888,
                                    intent,
                                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                                )
                                val delayMs = (alarmDelaySec * 1000).toLong()

                                try {
                                    when (selectedAlarmCategory) {
                                        "EXACT" -> {
                                            if (selectedClockType == "ELAPSED_REALTIME") {
                                                val triggerTime = SystemClock.elapsedRealtime() + delayMs
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                                    am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerTime, pendingIntent)
                                                } else {
                                                    am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerTime, pendingIntent)
                                                }
                                            } else {
                                                val triggerTime = System.currentTimeMillis() + delayMs
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                                                } else {
                                                    am.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                                                }
                                            }
                                            addLog(SelectedWorkerType.WORKER, "Scheduled EXACT Alarm ($selectedClockType) in ${alarmDelaySec.toInt()}s")
                                        }
                                        "ALARM_CLOCK" -> {
                                            val triggerTime = System.currentTimeMillis() + delayMs
                                            val clockInfo = AlarmManager.AlarmClockInfo(triggerTime, pendingIntent)
                                            am.setAlarmClock(clockInfo, pendingIntent)
                                            addLog(SelectedWorkerType.WORKER, "Scheduled setAlarmClock (User-Visible) in ${alarmDelaySec.toInt()}s")
                                        }
                                        else -> {
                                            if (selectedClockType == "ELAPSED_REALTIME") {
                                                val triggerTime = SystemClock.elapsedRealtime() + delayMs
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                                    am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerTime, pendingIntent)
                                                } else {
                                                    am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerTime, pendingIntent)
                                                }
                                            } else {
                                                val triggerTime = System.currentTimeMillis() + delayMs
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                                                } else {
                                                    am.set(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                                                }
                                            }
                                            addLog(SelectedWorkerType.WORKER, "Scheduled INEXACT Alarm ($selectedClockType) in ${alarmDelaySec.toInt()}s")
                                        }
                                    }
                                    activeAlarmScheduled = true
                                } catch (e: Exception) {
                                    addLog(SelectedWorkerType.WORKER, "Alarm scheduling error: ${e.message}", isError = true)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.AlarmAdd, contentDescription = null, tint = Color.Black)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Schedule Alarm", color = Color.Black, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                val am = alarmManager ?: return@Button
                                val intent = Intent(context, TaskReminderReceiver::class.java)
                                val pendingIntent = PendingIntent.getBroadcast(
                                    context,
                                    88888,
                                    intent,
                                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                                )
                                if (pendingIntent != null) {
                                    am.cancel(pendingIntent)
                                    pendingIntent.cancel()
                                    activeAlarmScheduled = false
                                    addLog(SelectedWorkerType.WORKER, "Alarm cancelled via PendingIntent.getBroadcast(..., FLAG_NO_CREATE) & cancel()")
                                } else {
                                    addLog(SelectedWorkerType.WORKER, "No active alarm PendingIntent found to cancel.")
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.AlarmOff, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Cancel Alarm", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Section 6: Device Boot Receiver (& Programmatic Component Toggle)
            Text("6. Device Boot Receiver & Component State", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)

            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1F2937))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Power, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("BootReceiver (RECEIVE_BOOT_COMPLETED)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }

                        Surface(
                            color = if (isBootReceiverEnabled) Color(0xFF10B981).copy(alpha = 0.2f) else Color(0xFF64748B).copy(alpha = 0.2f),
                            shape = RoundedCornerShape(6.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (isBootReceiverEnabled) Color(0xFF10B981) else Color(0xFF64748B))
                        ) {
                            Text(
                                text = if (isBootReceiverEnabled) "COMPONENT ENABLED" else "COMPONENT DISABLED",
                                color = if (isBootReceiverEnabled) Color(0xFF10B981) else Color(0xFF94A3B8),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    Text(
                        "All scheduled alarms are wiped when a device reboots. To reschedule, enable a BroadcastReceiver for ACTION_BOOT_COMPLETED. Programmatically toggle the receiver below using PackageManager.setComponentEnabledSetting():",
                        color = Color.LightGray,
                        fontSize = 11.sp,
                        lineHeight = 14.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Enable Boot Receiver Component across reboots", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Switch(
                            checked = isBootReceiverEnabled,
                            onCheckedChange = { enable ->
                                try {
                                    val newState = if (enable) {
                                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                                    } else {
                                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                                    }
                                    context.packageManager.setComponentEnabledSetting(
                                        bootComponent,
                                        newState,
                                        PackageManager.DONT_KILL_APP
                                    )
                                    isBootReceiverEnabled = enable
                                    addLog(
                                        SelectedWorkerType.WORKER,
                                        "BootReceiver updated to ${if (enable) "ENABLED" else "DISABLED"}. Programmatic setting overrides manifest across reboots."
                                    )
                                } catch (e: Exception) {
                                    addLog(SelectedWorkerType.WORKER, "Failed to update BootReceiver state: ${e.message}", isError = true)
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.Black,
                                checkedTrackColor = Color(0xFF10B981)
                            )
                        )
                    }
                }
            }

            // Section 7: Official AlarmManager Best Practices & Policy Guide
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF10121A)),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E2235))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Official Android AlarmManager Guidelines", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        GuideItem(
                            title = "Inexact Alarms (Default Best Practice)",
                            desc = "Delivered in batch windows to optimize battery. Target Android 12+ clips window length under 10 minutes to 10 minutes."
                        )
                        GuideItem(
                            title = "ELAPSED_REALTIME_WAKEUP vs RTC_WAKEUP",
                            desc = "Prefer ELAPSED_REALTIME (time since boot). RTC depends on UTC wall clock and can misfire if the user manually changes system time or time zone."
                        )
                        GuideItem(
                            title = "SCHEDULE_EXACT_ALARM vs USE_EXACT_ALARM",
                            desc = "SCHEDULE_EXACT_ALARM is granted by user and revokable. USE_EXACT_ALARM is granted automatically for specific apps (e.g. alarm clock, timers) subject to Play policy."
                        )
                        GuideItem(
                            title = "Doze Mode Bypass",
                            desc = "Use setAndAllowWhileIdle() or setExactAndAllowWhileIdle() for critical alarms during Doze. For longer tasks, enqueue a WorkManager WorkRequest from the alarm's BroadcastReceiver."
                        )
                        GuideItem(
                            title = "In-App Timing vs AlarmManager",
                            desc = "For timing guaranteed to occur during app lifetime, use Handler postDelayed() or Kotlin Coroutines delay(). AlarmManager is specifically for work OUTSIDE app lifetime."
                        )
                        GuideItem(
                            title = "Prevent Server Throttling with Jitter",
                            desc = "Add random time jitter (e.g. +/- 5 min) to network sync alarms to prevent millions of devices hitting your backend at the exact same minute."
                        )
                    }
                }
            }

            // Section 8: Java ThreadPoolExecutor & Handler Studio
            Text("8. Java ThreadPoolExecutor & MainThreadHandler Studio", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)

            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Memory, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("ThreadPoolExecutor ($coresCount Cores)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }

                        Surface(
                            color = Color(0xFF38BDF8).copy(alpha = 0.2f),
                            shape = RoundedCornerShape(6.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF38BDF8))
                        ) {
                            Text(
                                text = "${customThreadPool.activeCount} ACTIVE THREADS",
                                color = Color(0xFF38BDF8),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    OutlinedTextField(
                        value = threadTaskParamInput,
                        onValueChange = { threadTaskParamInput = it },
                        label = { Text("Task Input Body (Immutable Parameter)") },
                        leadingIcon = { Icon(Icons.Default.Code, contentDescription = null, tint = Color(0xFF38BDF8)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF38BDF8),
                            unfocusedBorderColor = Color(0xFF1E293B),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Simulated Background Execution Time", color = Color.LightGray, fontSize = 12.sp)
                            Text("${threadTaskDurationSec.toInt()} seconds", color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        Slider(
                            value = threadTaskDurationSec,
                            onValueChange = { threadTaskDurationSec = it },
                            valueRange = 1f..10f,
                            steps = 8,
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFF38BDF8),
                                activeTrackColor = Color(0xFF38BDF8)
                            )
                        )
                    }

                    Button(
                        onClick = {
                            isThreadTaskRunning = true
                            val payload = threadTaskParamInput
                            val durationMs = (threadTaskDurationSec * 1000).toLong()

                            addLog(SelectedWorkerType.WORKER, "Submitted Runnable task to ThreadPoolExecutor (Pool size: $coresCount)")

                            // Execute in background Executor thread
                            customThreadPool.execute {
                                try {
                                    Thread.sleep(durationMs)
                                    val response = "SuccessResult[payload='$payload', timestamp=${System.currentTimeMillis()}]"

                                    // Communicate result back to Main UI Thread using Handler + Looper.getMainLooper()
                                    mainThreadHandler.post {
                                        isThreadTaskRunning = false
                                        addLog(SelectedWorkerType.WORKER, "MainThreadHandler RECEIVED: $response")
                                    }
                                } catch (e: Exception) {
                                    val errorMsg = e.message ?: "Task execution failed"
                                    mainThreadHandler.post {
                                        isThreadTaskRunning = false
                                        addLog(SelectedWorkerType.WORKER, "MainThreadHandler ERROR: $errorMsg", isError = true)
                                    }
                                }
                            }
                        },
                        enabled = !isThreadTaskRunning,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isThreadTaskRunning) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.Black, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Running in Thread Pool...", color = Color.Black, fontWeight = FontWeight.Bold)
                        } else {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Execute via ThreadPoolExecutor + Handler", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }

                    HorizontalDivider(color = Color(0xFF1E293B), thickness = 1.dp)

                    Text("Official Java Concurrency Best Practices", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        GuideItem(
                            title = "Reuse Thread Pools (Avoid Expensive Thread Creation)",
                            desc = "Creating threads is expensive. Store ExecutorService in your Application class or DI container as Executors.newFixedThreadPool(4) or custom ThreadPoolExecutor."
                        )
                        GuideItem(
                            title = "Never Block the Main UI Thread",
                            desc = "Running network or disk I/O on the main thread blocks onDraw() and causes Application Not Responding (ANR) dialogs."
                        )
                        GuideItem(
                            title = "Inject Executor (Interface over Implementation)",
                            desc = "Repositories should depend on Executor interface rather than ExecutorService to promote testability and decouples thread lifecycle management."
                        )
                        GuideItem(
                            title = "Immutable Data & Thread Safety",
                            desc = "Avoid sharing mutable state between threads. Pass immutable parameter objects to prevent lock contention and synchronization issues."
                        )
                        GuideItem(
                            title = "HandlerCompat + Looper.getMainLooper()",
                            desc = "Use HandlerCompat.createAsync(Looper.getMainLooper()) to post results from background threads safely back to the UI layer."
                        )
                    }
                }
            }

            // Section 9: Guava ListenableFuture & Async Interop Studio
            Text("9. Guava ListenableFuture & Async Interop Studio", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)

            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF131520)),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF222638))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.HourglassTop, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Guava ListenableFuture API", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }

                        Surface(
                            color = Color(0xFF10B981).copy(alpha = 0.2f),
                            shape = RoundedCornerShape(6.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF10B981))
                        ) {
                            Text(
                                text = futureStatusText.uppercase(),
                                color = Color(0xFF10B981),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    OutlinedTextField(
                        value = futureParamInput,
                        onValueChange = { futureParamInput = it },
                        label = { Text("QueryResult Param (ListenableFuture payload)") },
                        leadingIcon = { Icon(Icons.Default.Analytics, contentDescription = null, tint = Color(0xFF10B981)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF10B981),
                            unfocusedBorderColor = Color(0xFF222638),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Simulate Network Error / Failure", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Switch(
                            checked = futureShouldFail,
                            onCheckedChange = { futureShouldFail = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.Black,
                                checkedTrackColor = Color(0xFFEF4444)
                            )
                        )
                    }

                    // Action Buttons for ListenableFuture
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                futureStatusText = "Awaiting Callback..."
                                val settable = SettableFuture.create<String>()
                                val param = futureParamInput
                                val fail = futureShouldFail

                                // Attach FutureCallback using Futures.addCallback
                                Futures.addCallback(
                                    settable,
                                    object : FutureCallback<String> {
                                        override fun onSuccess(result: String?) {
                                            futureStatusText = "Success"
                                            addLog(SelectedWorkerType.LISTENABLE_WORKER, "Futures.addCallback onSuccess: $result")
                                        }

                                        override fun onFailure(t: Throwable) {
                                            futureStatusText = "Failed"
                                            addLog(SelectedWorkerType.LISTENABLE_WORKER, "Futures.addCallback onFailure: ${t.message}", isError = true)
                                        }
                                    },
                                    context.mainExecutor
                                )

                                // Complete future asynchronously on thread pool
                                customThreadPool.execute {
                                    try {
                                        Thread.sleep(1200)
                                        if (fail) {
                                            settable.setException(RuntimeException("Network request failed for query: $param"))
                                        } else {
                                            settable.set("QueryResultData[id='$param', status=200 OK]")
                                        }
                                    } catch (e: Exception) {
                                        settable.setException(e)
                                    }
                                }

                                addLog(SelectedWorkerType.LISTENABLE_WORKER, "Created SettableFuture<String> & attached Futures.addCallback with context.mainExecutor")
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.CallMade, contentDescription = null, tint = Color.Black)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Futures.addCallback", color = Color.Black, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                try {
                                    val immediate = if (futureShouldFail) {
                                        Futures.immediateFailedFuture<String>(IllegalStateException("Immediate failure for $futureParamInput"))
                                    } else {
                                        Futures.immediateFuture("ImmediateSuccess[param='$futureParamInput']")
                                    }

                                    Futures.addCallback(
                                        immediate,
                                        object : FutureCallback<String> {
                                            override fun onSuccess(result: String?) {
                                                futureStatusText = "Immediate OK"
                                                addLog(SelectedWorkerType.LISTENABLE_WORKER, "Futures.immediateFuture SUCCESS: $result")
                                            }

                                            override fun onFailure(t: Throwable) {
                                                futureStatusText = "Immediate Err"
                                                addLog(SelectedWorkerType.LISTENABLE_WORKER, "Futures.immediateFailedFuture ERROR: ${t.message}", isError = true)
                                            }
                                        },
                                        context.mainExecutor
                                    )
                                } catch (e: Exception) {
                                    addLog(SelectedWorkerType.LISTENABLE_WORKER, "Error: ${e.message}", isError = true)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Immediate Future", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }

                    HorizontalDivider(color = Color(0xFF222638), thickness = 1.dp)

                    Text("Official ListenableFuture Guidelines & Interop", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        GuideItem(
                            title = "ListenableFuture Callback Registration",
                            desc = "Use Futures.addCallback(future, callback, executor) to register success and failure callbacks executed on a specified Executor (e.g. context.mainExecutor)."
                        )
                        GuideItem(
                            title = "Kotlin Suspend Interop (future.await())",
                            desc = "Use kotlinx.coroutines.guava's await() extension to suspend awaiting the result of a ListenableFuture without blocking threads."
                        )
                        GuideItem(
                            title = "CallbackToFutureAdapter (androidx.concurrent)",
                            desc = "Convert legacy listener/callback APIs to ListenableFuture using CallbackToFutureAdapter.getFuture { completer -> ... }."
                        )
                        GuideItem(
                            title = "Immediate Futures (Futures.immediateFuture)",
                            desc = "Wrap synchronous results into ListenableFuture for testing or non-async methods using Futures.immediateFuture() or Futures.immediateFailedFuture()."
                        )
                        GuideItem(
                            title = "RxJava Interop (Single -> SettableFuture)",
                            desc = "Convert RxJava Single<T> into ListenableFuture by creating SettableFuture<T> and subscribing single.subscribe(future::set, future::setException)."
                        )
                    }
                }
            }

            // Section 10: Jetpack Media3 Playback & MediaSession Service Studio
            Text("10. Jetpack Media3 Playback & MediaSession Service Studio", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)

            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1B2E)),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3B2D54))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.GraphicEq, contentDescription = null, tint = Color(0xFFE11D48), modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Jetpack Media3 ExoPlayer", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }

                        Surface(
                            color = if (mediaController != null) Color(0xFFE11D48).copy(alpha = 0.2f) else Color.Gray.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(6.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (mediaController != null) Color(0xFFE11D48) else Color.Gray)
                        ) {
                            Text(
                                text = if (mediaController != null) "CONTROLLER CONNECTED" else "DISCONNECTED",
                                color = if (mediaController != null) Color(0xFFE11D48) else Color.Gray,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    // Live Track Display Box
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF13111C)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color(0xFFFB7185), modifier = Modifier.size(24.dp))
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(mediaCurrentTitle, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        Text(mediaCurrentArtist, color = Color.LightGray, fontSize = 11.sp)
                                    }
                                }

                                Surface(
                                    color = Color(0xFF38BDF8).copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = mediaPlaybackStateText,
                                        color = Color(0xFF38BDF8),
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            // Timeline & Position Bar
                            val currentSec = (mediaCurrentPositionMs / 1000).toInt()
                            val totalSec = (mediaDurationMs / 1000).coerceAtLeast(1).toInt()
                            val progressFloat = (mediaCurrentPositionMs.toFloat() / mediaDurationMs.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)

                            Column {
                                LinearProgressIndicator(
                                    progress = { progressFloat },
                                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                    color = Color(0xFFE11D48),
                                    trackColor = Color(0xFF3B2D54)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        String.format(java.util.Locale.US, "%02d:%02d", currentSec / 60, currentSec % 60),
                                        color = Color.Gray,
                                        fontSize = 10.sp
                                    )
                                    Text(
                                        String.format(java.util.Locale.US, "%02d:%02d", totalSec / 60, totalSec % 60),
                                        color = Color.Gray,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }
                    }

                    // Playback Controls Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                mediaController?.let { controller ->
                                    val newShuffle = !controller.shuffleModeEnabled
                                    controller.shuffleModeEnabled = newShuffle
                                    mediaShuffleMode = newShuffle
                                    addLog(SelectedWorkerType.MEDIA3, "MediaController: Set shuffleModeEnabled = $newShuffle")
                                }
                            },
                            modifier = Modifier.background(if (mediaShuffleMode) Color(0xFFE11D48).copy(alpha = 0.3f) else Color.Transparent, CircleShape)
                        ) {
                            Icon(Icons.Default.Shuffle, contentDescription = "Shuffle", tint = if (mediaShuffleMode) Color(0xFFFB7185) else Color.Gray)
                        }

                        IconButton(
                            onClick = {
                                mediaController?.let { controller ->
                                    controller.seekToPreviousMediaItem()
                                    addLog(SelectedWorkerType.MEDIA3, "MediaController: Command seekToPreviousMediaItem()")
                                }
                            }
                        ) {
                            Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", tint = Color.White, modifier = Modifier.size(28.dp))
                        }

                        FloatingActionButton(
                            onClick = {
                                mediaController?.let { controller ->
                                    if (controller.isPlaying) {
                                        controller.pause()
                                        addLog(SelectedWorkerType.MEDIA3, "MediaController: Command pause()")
                                    } else {
                                        controller.play()
                                        addLog(SelectedWorkerType.MEDIA3, "MediaController: Command play()")
                                    }
                                }
                            },
                            containerColor = Color(0xFFE11D48),
                            contentColor = Color.White,
                            shape = CircleShape,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                if (mediaIsPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (mediaIsPlaying) "Pause" else "Play",
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                mediaController?.let { controller ->
                                    controller.seekToNextMediaItem()
                                    addLog(SelectedWorkerType.MEDIA3, "MediaController: Command seekToNextMediaItem()")
                                }
                            }
                        ) {
                            Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = Color.White, modifier = Modifier.size(28.dp))
                        }

                        IconButton(
                            onClick = {
                                mediaController?.let { controller ->
                                    val nextRepeat = when (controller.repeatMode) {
                                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                                        else -> Player.REPEAT_MODE_OFF
                                    }
                                    controller.repeatMode = nextRepeat
                                    mediaRepeatMode = nextRepeat
                                    addLog(SelectedWorkerType.MEDIA3, "MediaController: Set repeatMode = $nextRepeat")
                                }
                            },
                            modifier = Modifier.background(if (mediaRepeatMode != Player.REPEAT_MODE_OFF) Color(0xFFE11D48).copy(alpha = 0.3f) else Color.Transparent, CircleShape)
                        ) {
                            Icon(Icons.Default.Repeat, contentDescription = "Repeat", tint = if (mediaRepeatMode != Player.REPEAT_MODE_OFF) Color(0xFFFB7185) else Color.Gray)
                        }
                    }

                    // Custom Session Commands
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                mediaController?.let { controller ->
                                    val command = SessionCommand(MediaPlaybackService.CUSTOM_ACTION_FAVORITE, Bundle.EMPTY)
                                    val future = controller.sendCustomCommand(command, Bundle.EMPTY)
                                    Futures.addCallback(
                                        future,
                                        object : FutureCallback<androidx.media3.session.SessionResult> {
                                            override fun onSuccess(result: androidx.media3.session.SessionResult?) {
                                                val msg = result?.extras?.getString("MESSAGE") ?: "Favorite toggled"
                                                addLog(SelectedWorkerType.MEDIA3, "Custom Command Success: $msg")
                                            }

                                            override fun onFailure(t: Throwable) {
                                                addLog(SelectedWorkerType.MEDIA3, "Custom Command Error: ${t.message}", isError = true)
                                            }
                                        },
                                        context.mainExecutor
                                    )
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B2D54)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Favorite, contentDescription = null, tint = Color(0xFFFB7185), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Favorite FX", fontSize = 11.sp, color = Color.White)
                        }

                        Button(
                            onClick = {
                                mediaController?.let { controller ->
                                    val command = SessionCommand(MediaPlaybackService.CUSTOM_ACTION_BOOST_BASS, Bundle.EMPTY)
                                    val future = controller.sendCustomCommand(command, Bundle.EMPTY)
                                    Futures.addCallback(
                                        future,
                                        object : FutureCallback<androidx.media3.session.SessionResult> {
                                            override fun onSuccess(result: androidx.media3.session.SessionResult?) {
                                                val msg = result?.extras?.getString("MESSAGE") ?: "Equalizer Applied"
                                                addLog(SelectedWorkerType.MEDIA3, "Equalizer Command Success: $msg")
                                            }

                                            override fun onFailure(t: Throwable) {
                                                addLog(SelectedWorkerType.MEDIA3, "Equalizer Command Error: ${t.message}", isError = true)
                                            }
                                        },
                                        context.mainExecutor
                                    )
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B2D54)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Equalizer, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Bass Boost", fontSize = 11.sp, color = Color.White)
                        }
                    }

                    HorizontalDivider(color = Color(0xFF3B2D54), thickness = 1.dp)

                    Text("Official Jetpack Media3 Architectural Best Practices", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        GuideItem(
                            title = "Common Player Interface Abstraction",
                            desc = "Player is the unified interface implemented by ExoPlayer, MediaController, and MediaBrowser. No connectors required between session and UI."
                        )
                        GuideItem(
                            title = "MediaSessionService Foreground Service",
                            desc = "Houses Player and MediaSession inside a foreground service with foregroundServiceType='mediaPlayback' for background playback without ANR crashes."
                        )
                        GuideItem(
                            title = "Automatic MediaStyle System Notifications",
                            desc = "MediaSessionService automatically updates MediaStyle system notifications with artwork, track metadata, and media button actions across Android Auto, Wear OS, and lock screen."
                        )
                        GuideItem(
                            title = "MediaController & SessionToken IPC Interop",
                            desc = "Connects UI activity or external apps asynchronously to the remote MediaSession process using SessionToken and MediaController.Builder."
                        )
                        GuideItem(
                            title = "Playback Resumption & MediaButtonReceiver",
                            desc = "Override MediaSession.Callback.onPlaybackResumption() and declare MediaButtonReceiver to restore playback state after reboots or bluetooth media button clicks."
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SystemAttributionRow(tag: String, usage: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            color = Color(0xFF1E2235),
            shape = RoundedCornerShape(4.dp)
        ) {
            Text(
                text = tag,
                color = Color(0xFF38BDF8),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(usage, color = Color.LightGray, fontSize = 11.sp)
    }
}

@Composable
private fun ConstraintToggleItem(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Icon(icon, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(description, color = Color.Gray, fontSize = 10.sp)
            }
        }
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(checkedColor = Color(0xFF38BDF8))
        )
    }
}

@Composable
private fun GuideItem(title: String, desc: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text(desc, color = Color.Gray, fontSize = 11.sp)
        }
    }
}
