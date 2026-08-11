package com.coxy.ghost

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.BatteryManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.isModelLoaded
import com.coxy.ghost.memory.Embedder
import com.coxy.ghost.memory.FactExtractor
import com.coxy.ghost.memory.MemoryStore
import com.coxy.ghost.memory.MemoryStore.Companion.stripGhostPrefix
import com.coxy.ghost.memory.toMemoryBlock
import com.coxy.ghost.perception.CameraCapture
import com.coxy.ghost.perception.ContextStore
import com.coxy.ghost.perception.GhostPrompts
import com.coxy.ghost.perception.MicListener
import com.coxy.ghost.perception.PerceptionManager
import com.coxy.ghost.perception.RetentionManager
import com.coxy.ghost.perception.toPromptBlock
import com.coxy.ghost.action.ActionCatalog
import com.coxy.ghost.action.ActionExecutor
import com.coxy.ghost.action.ActionParser
import com.coxy.ghost.cloud.CloudRouter
import com.coxy.ghost.personality.PersonalityRepository
import com.coxy.ghost.personality.PersonalityTranslator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * GHOST's persistent core.
 *
 * A foreground service (type `specialUse`) that owns the [InferenceEngine] lifecycle so the
 * native model survives Activity destruction, screen-off, and (with the boot receiver + START_STICKY)
 * reboots and process-kills. Holds a partial WakeLock so perception/inference can run while the
 * screen is off. The Activity and the Service share the one process-wide engine singleton.
 *
 * Auto-restart: returns [START_STICKY], so if the OS kills the process the system recreates the
 * Service (a fresh `onCreate`) and we re-load the persisted model — that is the survival mechanism
 * the Phase 3 gate tests.
 */
class GhostService : LifecycleService() {

    private val engine: InferenceEngine by lazy {
        AiChat.getInferenceEngine(applicationContext)
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var startTimeMs: Long = 0L
    private var perception: PerceptionManager? = null
    private var micListener: MicListener? = null
    private var cameraCapture: CameraCapture? = null
    private var retention: RetentionManager? = null

    // Phase 6 memory: shared with MainActivity via the process-wide GhostDatabase + Embedder
    // singletons. Lazy so it's only built once the service is actually running.
    private val memoryStore: MemoryStore by lazy { MemoryStore(applicationContext) }
    // Phase 6.2: extract durable facts from completed voice/typed exchanges.
    private val factExtractor: FactExtractor by lazy {
        FactExtractor(applicationContext, engine, memoryStore)
    }
    // Phase 6.3: trait profile repository. Used to load the active personality before applying the
    // system prompt, and to restart the engine when the user changes traits.
    private val personalityRepo: PersonalityRepository by lazy { PersonalityRepository(applicationContext) }
    // Phase 7: action executor. Runs whitelisted device actions parsed from the model's reply.
    private val actionExecutor: ActionExecutor by lazy { ActionExecutor(applicationContext) }
    // Phase 8: cloud-brain router. Falls back to the local 1.5B engine when cloud is unavailable.
    private val cloudRouter: CloudRouter by lazy { CloudRouter(applicationContext, engine) }

    // Thermal monitoring (GHOST.md §8: measure honestly).
    @Volatile private var lastThermalStatus: Int = 0
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startTimeMs = System.currentTimeMillis()
        Log.i(TAG, "onCreate — starting foreground service")

        startForegroundCompat()
        acquireWakeLock()

        // Perception layer: buckets sensor + location data into a ContextState the chat path
        // prepends to each prompt. Independent of the engine — starts before the model is loaded.
        perception = PerceptionManager(applicationContext).also { it.start() }

        // Phase 5 perception: mic (continuous, D2) + camera (on-demand, D2) + rolling-window
        // retention (D4). Each runs on its own thread and only writes ContextStore — never the
        // engine. MicListener.start() is a no-op if RECORD_AUDIO isn't granted yet (it logs +
        // sets micAvailable=false); ACTION_REFRESH_PERCEPTION re-inits it once granted.
        retention = RetentionManager(applicationContext)
        micListener = MicListener(applicationContext, retention).also { it.start() }
        cameraCapture = CameraCapture(applicationContext, retention)

        // Phase 6: initialise the on-device sentence embedder (copies the ~22 MB model asset to
        // filesDir on first run). Off the main thread; idempotent if MainActivity already inited it.
        lifecycleScope.launch(Dispatchers.IO) { Embedder.init(applicationContext) }

        // Thermal monitoring (GHOST.md §8: measure honestly). The listener fires on status
        // changes; the telemetry coroutine below logs current status + battery temp every 60s.
        thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
            if (status != lastThermalStatus) {
                Log.i(HEARTBEAT_TAG, "thermal status: $status")
                lastThermalStatus = status
            }
        }
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            thermalListener?.let { pm.addThermalStatusListener(it) }
        }

        // Load the persisted model (if any). On a START_STICKY restart (fresh process) this is
        // what brings GHOST back to a ready state without user interaction.
        lifecycleScope.launch {
            val path = GhostPrefs.getModelPath(applicationContext)
            if (path == null) {
                Log.i(TAG, "No model path persisted — idling until one is picked")
                return@launch
            }
            // The engine is a process-wide singleton. If the service was stopped and re-started
            // WITHOUT the process dying (Stop→Start), the model is still loaded — reuse it.
            // loadModel would otherwise throw "Cannot load model in ModelReady!".
            if (engine.state.value.isModelLoaded) {
                if (!systemPromptAppliedInProcess) {
                    // Rare edge: model loaded this process but the system prompt wasn't applied
                    // (e.g. the previous load returned before applySystemPromptOnce ran). A clean
                    // reload is the only way to re-arm setSystemPrompt (it's once-only per load).
                    Log.i(TAG, "Model loaded but system prompt missing — reloading to re-arm it")
                    runCatching { engine.cleanUp() }
                    runCatching { engine.loadModel(path) }
                        .onSuccess { applySystemPromptOnce() }
                } else {
                    Log.i(TAG, "Model already loaded — reusing existing engine (system prompt already applied)")
                }
                return@launch
            }
            Log.i(TAG, "Loading persisted model: $path")
            val ok = runCatching { engine.loadModel(path) }.isSuccess
            if (ok) {
                applySystemPromptOnce()
            } else {
                Log.w(TAG, "Model load failed — likely started before user unlock (CE storage locked). Will retry on unlock.")
                retryModelLoadOnUnlock(path)
            }
        }

        // Heartbeat: every 5 min log that we're alive, with uptime + engine state. The 24h gate
        // counts these to prove survival.
        lifecycleScope.launch {
            while (true) {
                val uptime = (System.currentTimeMillis() - startTimeMs) / 1000
                val cs = ContextStore.state.value
                Log.i(HEARTBEAT_TAG, "alive uptime=${uptime}s state=${engine.state.value} mic=${cs.micState} cam=${if (cs.lastFrameMs > 0) "on" else "off"}")
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }

        // Phase 6: wake-word voice commands. MicListener writes voiceCommand + bumps voiceCommandSeq
        // when it hears "Hey Ghost, <command>". This observer picks up each new command and runs it
        // through the engine (Siri-style: GHOST answers the spoken command), streaming the reply to
        // ContextState.voiceResponse for MainActivity to render. The EngineGate single-flight guard
        // keeps this from racing a typed message in the Activity (the engine is single-threaded).
        lifecycleScope.launch(Dispatchers.Default) {
            var lastSeq = 0L
            ContextStore.state.collect { cs ->
                if (cs.voiceCommandSeq != lastSeq && !cs.voiceCommand.isNullOrBlank()) {
                    lastSeq = cs.voiceCommandSeq
                    launch { runVoiceCommand(cs.voiceCommand) }
                }
            }
        }

        // Telemetry + prune: every 60s log the bucketed context state, thermal status, battery
        // temp, retention bytes, and prune the rolling-window cache. Read-only on the StateFlow +
        // the PowerManager status field — does NOT touch the engine. (GHOST.md §8: measure
        // thermal/battery honestly.)
        lifecycleScope.launch {
            while (true) {
                val cs = ContextStore.state.value
                val bat = runCatching {
                    registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                }.getOrNull()
                val temp = bat?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)?.let { it / 10f } ?: -1f
                Log.i(
                    HEARTBEAT_TAG,
                    "perception=$cs thermal=$lastThermalStatus batTemp=${"%.1f".format(temp)}C retention=${retention?.bytesUsed() ?: -1}B memCount=${runCatching { memoryStore.count() }.getOrDefault(-1)}"
                )
                runCatching { retention?.prune() }
                runCatching { memoryStore.prune() }
                delay(PERCEPTION_LOG_INTERVAL_MS)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "Stop requested")
                lifecycleScope.launch {
                    GhostPrefs.setEnabled(applicationContext, false)
                    stopSelf()
                }
            }
            ACTION_REFRESH_PERCEPTION -> {
                // Sent by MainActivity after the user grants location/activity/camera/mic
                // permissions: restart perception so it registers GMS clients with the now-granted
                // perms, re-init mic (RECORD_AUDIO) + camera (CAMERA), and re-run startForegroundCompat
                // so the location/camera/microphone FGS types are added.
                Log.i(TAG, "Refresh perception requested (permissions likely just granted)")
                perception?.stop()
                perception = PerceptionManager(applicationContext).also { it.start() }
                micListener?.stop()
                micListener = MicListener(applicationContext, retention).also { it.start() }
                if (cameraCapture == null) cameraCapture = CameraCapture(applicationContext, retention)
                startForegroundCompat()
            }
            ACTION_CAPTURE_FRAME -> {
                // On-demand camera capture (D2). Triggered by MainActivity's Capture button or a
                // vision-related query. The frame metadata lands in ContextStore asynchronously.
                val facing = intent.getStringExtra(EXTRA_FACING) ?: CameraCapture.FACING_BACK
                Log.i(TAG, "Capture frame requested (facing=$facing)")
                cameraCapture?.captureOnDemand(facing)
            }
            ACTION_RELOAD_PERSONALITY -> {
                // Phase 6.3: the user edited the active personality profile. setSystemPrompt is
                // once-only per model load, so reload the model to apply the new traits.
                Log.i(TAG, "Reload personality requested")
                lifecycleScope.launch {
                    reloadModelForPersonality()
                }
            }
            ACTION_TOGGLE_MIC -> {
                // Phase 8.1: user toggle for continuous mic listening. Persist the new state and
                // tell MicListener to start/stop recognizer without recreating the service.
                lifecycleScope.launch {
                    val currently = GhostPrefs.isMicEnabled(applicationContext)
                    val next = !currently
                    GhostPrefs.setMicEnabled(applicationContext, next)
                    micListener?.setEnabled(next)
                    Log.i(TAG, "Mic ${if (next) "enabled" else "disabled"} by user toggle")
                }
            }
            ACTION_KILL -> {
                // Independent kill switch (D3, §8): stop everything + wipe retention + stopSelf.
                // setEnabled(false) is also persisted by KillSwitch.killGhost (the caller), but we
                // set it here too so a process death between the two can't lose it.
                Log.i(TAG, "Kill requested (independent kill switch)")
                lifecycleScope.launch {
                    GhostPrefs.setEnabled(applicationContext, false)
                    perception?.stop(); perception = null
                    micListener?.stop(); micListener = null
                    cameraCapture?.close(); cameraCapture = null
                    retention?.clear(); retention = null
                    stopSelf()
                }
            }
            else -> {
                // A truly null intent means the system restarted us after a kill (onCreate already
                // re-initialized everything). A non-null intent with a null action is a normal
                // start. Distinguish them so the 24h gate can count real restarts.
                val label = if (intent == null) "null(restart-after-kill)" else "start"
                Log.i(TAG, "onStartCommand $label")
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null // Not a bound service.
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy — releasing wake lock (engine is a process singleton; not destroyed here)")
        perception?.stop()
        perception = null
        micListener?.stop()
        micListener = null
        cameraCapture?.close()
        cameraCapture = null
        retention?.clear()
        retention = null
        thermalListener?.let {
            runCatching {
                (getSystemService(Context.POWER_SERVICE) as PowerManager).removeThermalStatusListener(it)
            }
        }
        thermalListener = null
        unlockReceiver?.let { runCatching { unregisterReceiver(it) } }
        unlockReceiver = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        // NOTE: deliberately NOT calling engine.destroy(). The InferenceEngine is a process-wide
        // singleton shared with MainActivity. Destroying it here (on the main thread) races with
        // in-flight native inference running on llamaDispatcher and caused a SIGSEGV in
        // common_chat_templates_was_explicit (freed chat-templates pointer, fault addr 0x2).
        // Native memory is reclaimed automatically when the OS reaps the process; a START_STICKY
        // restart gets a fresh process with a fresh (Uninitialized) engine, which onCreate re-loads.
        isRunning = false
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
        Log.i(TAG, "Acquired partial wake lock")
    }

    /**
     * If the service was root-started at boot before the user unlocked the phone, credential-
     * encrypted storage (the model file + DataStore) is inaccessible and the first `loadModel`
     * fails. Register for `ACTION_USER_UNLOCKED` (and `BOOT_COMPLETED` as a fallback) and retry
     * the load once the device is unlocked, then unregister. No-op if the load already succeeded.
     */
    private var unlockReceiver: BroadcastReceiver? = null
    private fun retryModelLoadOnUnlock(path: String) {
        if (unlockReceiver != null) return
        unlockReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                Log.i(TAG, "Unlock broadcast received (${intent.action}) — retrying model load")
                lifecycleScope.launch {
                    runCatching { engine.loadModel(path) }
                        .onSuccess {
                            Log.i(TAG, "Model loaded after unlock")
                            applySystemPromptOnce()
                        }
                        .onFailure { Log.e(TAG, "Model load still failing after unlock", it) }
                }
                runCatching { unregisterReceiver(unlockReceiver) }
                unlockReceiver = null
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_UNLOCKED)
            addAction(Intent.ACTION_BOOT_COMPLETED)
        }
        // minSdk 33 → the 3-arg form is always available. NOT_EXPORTED: we only want the system's
        // protected unlock/boot broadcasts (system delivers to NOT_EXPORTED receivers fine).
        registerReceiver(unlockReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    /**
     * Phase 6.3: reload the model so a new active personality profile takes effect. setSystemPrompt
     * is once-only per model load, so this is the only way to apply trait changes without an
     * app/service restart. Called from the ACTION_RELOAD_PERSONALITY handler.
     */
    private suspend fun reloadModelForPersonality() {
        if (!engine.state.value.isModelLoaded) {
            Log.i(TAG, "No model loaded — personality reload has no effect yet")
            return
        }
        val path = GhostPrefs.getModelPath(applicationContext) ?: return
        Log.i(TAG, "Reloading model to apply personality changes")
        systemPromptAppliedInProcess = false
        runCatching { engine.cleanUp() }
        runCatching { engine.loadModel(path) }
            .onSuccess { applySystemPromptOnce() }
            .onFailure { Log.w(TAG, "Personality reload failed", it) }
    }

    /**
     * Apply the GHOST system prompt exactly once per fresh model load. [InferenceEngine.setSystemPrompt]
     * is once-only (it throws IllegalStateException after the first user prompt / cleanup / second
     * call), so this is guarded by [systemPromptAppliedInProcess] and wrapped in [runCatching] as a
     * safety net: if the guard ever races, the worst case is a logged warning and the model runs
     * with Qwen's default prompt — no crash, no tombstone, the daemon survives.
     */
    private suspend fun applySystemPromptOnce() {
        if (systemPromptAppliedInProcess) return
        val profile = runCatching { personalityRepo.activeProfile() }.getOrDefault(
            com.coxy.ghost.personality.PersonalityTranslator.baseline()
        )
        val personalityBlock = PersonalityTranslator.profileToPromptBlock(profile)
        val prompt = GhostPrompts.buildSystemPrompt(personalityBlock)
        currentSystemPrompt = prompt
        Log.i(TAG, "Applying system prompt with personality: $personalityBlock")
        runCatching { engine.setSystemPrompt(prompt) }
            .onSuccess {
                systemPromptAppliedInProcess = true
                Log.i(TAG, "System prompt applied")
            }
            .onFailure { Log.w(TAG, "System prompt not applied (engine still usable without it)", it) }
    }

    /**
     * Phase 6: run a wake-word voice command through the engine. Prepends the live [CONTEXT] block
     * (so GHOST answers with its current perception), auto-captures a frame for vision-related
     * commands (parity with the typed path), and streams the reply token-by-token to
     * [ContextState.voiceResponse] for MainActivity to render. The [EngineGate] single-flight guard
     * rejects the command if a typed message is already generating (and vice versa) — the engine is
     * single-threaded and two concurrent sendUserPrompt calls would corrupt the conversation.
     */
    private suspend fun runVoiceCommand(cmd: String) {
        if (EngineGate.busy) {
            Log.i(TAG, "engine busy — voice command dropped: $cmd")
            // Surface it in the chat so the spoken command isn't left as a silent empty bubble.
            ContextStore.update { it.copy(voiceResponse = "(skipped — GHOST was already responding)") }
            return
        }
        EngineGate.busy = true
        try {
            if (cameraCapture != null && hasCamera() &&
                VISION_KEYWORDS.any { cmd.contains(it, true) }
            ) {
                cameraCapture?.captureOnDemand(CameraCapture.FACING_BACK)
                delay(VISION_CAPTURE_WAIT_MS)
            }
            val cs = ContextStore.state.value
            // Phase 6: recall semantically similar past turns and prepend a [MEMORY] block
            // (rides the sendUserPrompt string alongside [CONTEXT]). recall runs off the engine's
            // single-threaded llamaDispatcher (TFLite + Room); the engine is idle here.
            val memBlock = memoryStore.recall(cmd).toMemoryBlock()
            val prompt = if (memBlock.isNotEmpty()) cs.toPromptBlock() + "\n" + memBlock + "\n" + cmd
                         else cs.toPromptBlock() + "\n" + cmd
            Log.i(TAG, "voice command -> inference: $cmd")
            val sb = StringBuilder()
            cloudRouter.route(prompt, currentSystemPrompt, predictLength = 512).collect { token ->
                sb.append(token)
                ContextStore.update { it.copy(voiceResponse = sb.toString()) }
            }
            Log.i(TAG, "voice response: ${sb.toString().take(120)}")

            // Phase 7: parse any structured actions from the reply, execute immediate ones, and
            // surface Tier 3 actions for confirmation. Also strip the deprecated "Ghost:" prefix.
            val finalReply = stripGhostPrefix(runActions(sb.toString()))
            ContextStore.update { it.copy(voiceResponse = finalReply) }

            // After the reply completes (engine idle): persist the turn pair for future recall.
            memoryStore.remember("user", cmd, "voice")
            memoryStore.remember("assistant", finalReply, "voice")
            memoryStore.prune()
            // Phase 6.2: extract durable facts from the voice exchange.
            factExtractor.extract(cmd, finalReply, "voice")
        } catch (e: Exception) {
            Log.w(TAG, "voice inference failed", e)
        } finally {
            EngineGate.busy = false
        }
    }

    /** Phase 7: parse actions, execute immediate ones, request confirmation for Tier 3. */
    private suspend fun runActions(reply: String): String {
        val parsed = ActionParser.extract(reply)
        if (parsed.actions.isEmpty() && parsed.errors.isEmpty()) return reply

        val batch = actionExecutor.runImmediate(parsed.actions)
        if (batch.pending.isNotEmpty()) {
            actionExecutor.requestConfirmation(batch.pending)
        }

        val resultLines = buildList {
            addAll(parsed.errors.map { "[ACTION fail parser: $it]" })
            addAll(batch.results.map { it.toReplyLine() })
            if (batch.pending.isNotEmpty()) {
                add("[ACTION pending ${batch.pending.joinToString { ActionCatalog.describe(it.id) }}: requires confirmation]")
            }
        }

        val stripped = parsed.cleanedReply.trim()
        return if (resultLines.isEmpty()) stripped
               else stripped + "\n" + resultLines.joinToString("\n")
    }

    private fun hasCamera(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.ghost_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = getString(R.string.ghost_channel_desc) }
            )
        }

        val stopIntent = Intent(this, GhostService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.ghost_notif_title))
            .setContentText(getString(R.string.ghost_notif_text))
            .setSmallIcon(R.drawable.outline_send_24)
            .setOngoing(true)
            .addAction(0, getString(R.string.ghost_notif_stop), stopPi)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // The manifest declares specialUse|location, but the location type requires
            // ACCESS_FINE_LOCATION to be granted at startForeground time (else SecurityException).
            // On the boot-started path the user hasn't granted it yet → start specialUse-only;
            // MainActivity sends ACTION_REFRESH_PERCEPTION once the permission is granted, which
            // re-runs startForegroundCompat (adding the location type) and restarts perception.
            val hasFine = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val hasMic = ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            val hasCam = ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
            // Each FGS type requires its runtime permission granted at startForeground time (else
            // SecurityException). Boot path runs specialUse-only; ACTION_REFRESH_PERCEPTION adds the
            // others once granted. Mic is continuous (D2); camera is on-demand but the type must be
            // declared up-front to ever open the camera from the FGS.
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (hasFine) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            if (hasMic) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (hasCam) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            startForeground(NOTIF_ID, notification, types)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    companion object {
        private const val TAG = "GhostService"
        private const val HEARTBEAT_TAG = "Ghost"
        private const val CHANNEL_ID = "ghost_service"
        private const val NOTIF_ID = 1
        private const val WAKE_LOCK_TAG = "GHOST::GhostService"
        private const val HEARTBEAT_INTERVAL_MS = 5 * 60 * 1000L
        private const val PERCEPTION_LOG_INTERVAL_MS = 60 * 1000L

        const val ACTION_STOP = "com.coxy.ghost.action.STOP"
        const val ACTION_REFRESH_PERCEPTION = "com.coxy.ghost.action.REFRESH_PERCEPTION"
        const val ACTION_RELOAD_PERSONALITY = "com.coxy.ghost.action.RELOAD_PERSONALITY"
        const val ACTION_CAPTURE_FRAME = "com.coxy.ghost.action.CAPTURE_FRAME"
        const val ACTION_TOGGLE_MIC = "com.coxy.ghost.action.TOGGLE_MIC"
        const val ACTION_KILL = "com.coxy.ghost.action.KILL"
        const val EXTRA_FACING = "facing"

        // Vision auto-capture for voice commands (parity with MainActivity's typed path).
        private val VISION_KEYWORDS = listOf("see", "look", "camera", "frame", "in front", "what's there")
        private const val VISION_CAPTURE_WAIT_MS = 1500L

        /** Read by the UI to reflect whether the daemon is up. */
        @Volatile
        @JvmStatic
        var isRunning: Boolean = false
            private set

        /**
         * Whether the GHOST system prompt has been applied in this process. [InferenceEngine.setSystemPrompt]
         * is once-only per model load, so this guards against re-calling it on the Stop→Start
         * same-process reuse path (which would throw). Survives a Stop→Start within one process;
         * a START_STICKY fresh-process restart resets it to false (new process) and re-applies.
         */
        @Volatile
        @JvmStatic
        var systemPromptAppliedInProcess: Boolean = false
            private set

        /**
         * Phase 8: the most recent system prompt applied to the local engine. The cloud brain needs
         * the same system text so it answers in the same persona and obeys the same action rules.
         * Updated each time [applySystemPromptOnce] succeeds.
         */
        @Volatile
        @JvmStatic
        var currentSystemPrompt: String = ""
            private set
    }
}