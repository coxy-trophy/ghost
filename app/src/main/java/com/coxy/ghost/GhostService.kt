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
import com.coxy.ghost.perception.HomePoint
import com.coxy.ghost.perception.VisionTrigger
import com.coxy.ghost.perception.GhostPrompts
import com.coxy.ghost.perception.MicListener
import com.coxy.ghost.perception.wakeword.KwsSampleRecorder
import com.coxy.ghost.perception.wakeword.KwsScorer
import com.coxy.ghost.perception.wakeword.WakeTrigger
import java.io.File
import com.coxy.ghost.perception.BleScanner
import com.coxy.ghost.perception.PerceptionManager
import com.coxy.ghost.perception.RadioWatcher
import com.coxy.ghost.perception.RetentionManager
import com.coxy.ghost.action.ActionExecutor
import com.coxy.ghost.action.ActionRunner
import com.coxy.ghost.cloud.CloudKeyStorage
import com.coxy.ghost.cloud.CloudRouter
import com.coxy.ghost.initiative.InitiativeManager
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
    // Phase 4.5: nearby BLE devices + radio state (call, signal, NFC).
    private var bleScanner: BleScanner? = null
    private var radioWatcher: RadioWatcher? = null
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

    /**
     * The path by which GHOST speaks first. Switched off by default; [ACTION_SET_INITIATIVE] turns
     * it on. The loop runs regardless and holds on the preference, so toggling it takes effect on
     * the next tick without restarting anything.
     */
    private val initiative: InitiativeManager by lazy {
        InitiativeManager(
            context = applicationContext,
            scope = lifecycleScope,
            router = cloudRouter,
            memoryStore = memoryStore,
            initiativeTrait = { runCatching { personalityRepo.activeProfile().initiative }.getOrDefault(0) },
            systemPrompt = { currentSystemPrompt },
        )
    }

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
        bleScanner = BleScanner(applicationContext).also { it.start() }
        radioWatcher = RadioWatcher(applicationContext).also { it.start() }

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
        initiative.start()

        // Drop fact-extraction output that was stored as conversation. Those rows keep being
        // recalled into [MEMORY] and keep re-teaching GHOST to answer in "FACT: GHOST did X" form
        // and to address Coxy by GHOST's own name, so blocking new ones is not enough on its own.
        // Idempotent and cheap (one scan of a table capped at 1000 rows); runs once per process.
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { memoryStore.purgeExtractionArtifacts() }
            runCatching { memoryStore.purgeSeparationClaims() }
                .onFailure { Log.w(TAG, "memory artifact purge failed", it) }
        }

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
                if (cs.voiceCommandSeq == lastSeq) return@collect
                // Advance the seq before deciding whether we can run it: a command we cannot use
                // must not leave this observer pinned to a stale seq, and a drop that is logged is
                // far easier to diagnose than one that is silent.
                lastSeq = cs.voiceCommandSeq
                val cmd = cs.voiceCommand
                if (cmd.isNullOrBlank()) {
                    Log.w(TAG, "voice command seq ${cs.voiceCommandSeq} carried no text — dropped")
                    return@collect
                }
                launch { runVoiceCommand(cmd) }
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
                bleScanner?.stop()
                bleScanner = BleScanner(applicationContext).also { it.start() }
                radioWatcher?.stop()
                radioWatcher = RadioWatcher(applicationContext).also { it.start() }
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
                // The "leave the mic on" latch for Live mode. Persist the new state and tell
                // MicListener to open/close without recreating the service.
                // EXTRA_ENABLED makes this a set, not a flip. MainActivity used to write the pref
                // itself and *then* send this action, which flipped it straight back — so the mic
                // button changed its own label and nothing else. A shell caller can still omit the
                // extra to get the old toggle behaviour.
                val explicit = if (intent.hasExtra(EXTRA_ENABLED)) {
                    intent.getBooleanExtra(EXTRA_ENABLED, false)
                } else null
                lifecycleScope.launch {
                    val next = explicit ?: !GhostPrefs.isMicEnabled(applicationContext)
                    GhostPrefs.setMicEnabled(applicationContext, next)
                    micListener?.setEnabled(next)
                    Log.i(TAG, "Mic latch ${if (next) "OPEN" else "OFF"}")
                }
            }
            ACTION_PTT_DOWN -> {
                // Push-to-talk pressed. Momentary and never persisted — the mic is open exactly as
                // long as the button is held.
                micListener?.startPushToTalk()
            }
            ACTION_PTT_UP -> {
                micListener?.stopPushToTalk()
            }
            ACTION_SET_MODEL_PATH -> {
                // Persist the GGUF path without the UI. MainActivity's document picker was the only
                // way to set this, so a wiped DataStore (e.g. after the app's uid changed) left the
                // local engine idle with no way back except tapping through the picker by hand.
                val path = intent.getStringExtra(EXTRA_PATH)
                Log.i(TAG, "Set model path requested: $path")
                lifecycleScope.launch {
                    val file = path?.let { File(it) }
                    if (file == null || !file.isFile || !file.canRead()) {
                        Log.w(TAG, "Model path missing or unreadable — ignoring: $path")
                        return@launch
                    }
                    GhostPrefs.setModelPath(applicationContext, file.path)
                    Log.i(TAG, "Model path persisted: ${file.path}")
                    // setSystemPrompt is once-only per load, so re-arm the guard around a reload —
                    // same handling as reloadModelForPersonality.
                    if (engine.state.value.isModelLoaded) {
                        Log.i(TAG, "Swapping the loaded model for ${file.name}")
                        systemPromptAppliedInProcess = false
                        runCatching { engine.cleanUp() }
                    }
                    runCatching { engine.loadModel(file.path) }
                        .onSuccess {
                            applySystemPromptOnce()
                            Log.i(TAG, "Model loaded from ${file.path}")
                        }
                        .onFailure { Log.e(TAG, "Model load failed for ${file.path}", it) }
                }
            }
            ACTION_CAPTURE_KWS -> {
                // Phase 5.2 retraining aid: record a wake-word training clip through the same
                // AudioRecord path the detector runs on. MicListener owns the mic, so pause it for
                // the duration and resume afterwards (two MIC-source clients are not reliable here).
                val label = intent.getStringExtra(EXTRA_LABEL) ?: KwsSampleRecorder.LABEL_POSITIVE
                val seconds = intent.getIntExtra(EXTRA_SECONDS, 3)
                Log.i(TAG, "KWS capture requested (label=$label seconds=$seconds)")
                lifecycleScope.launch {
                    val wasEnabled = MicListener.isRunning
                    if (wasEnabled) micListener?.setEnabled(false)
                    // Give the recognizer/AudioRecord teardown a moment to release the mic.
                    delay(KWS_MIC_RELEASE_MS)
                    val file = withContext(Dispatchers.IO) {
                        KwsSampleRecorder.record(applicationContext, label, seconds)
                    }
                    Log.i(TAG, "KWS capture ${if (file != null) "wrote ${file.absolutePath}" else "FAILED"}")
                    if (wasEnabled) micListener?.setEnabled(true)
                }
            }
            ACTION_SCORE_KWS -> {
                // Phase 5.2 gate instrument: replay recorded clips through the shipped model and log
                // what it actually scored. Answers whether a missed wake word is a tuning problem or
                // a training problem — see KwsScorer's header.
                val label = intent.getStringExtra(EXTRA_LABEL) ?: KwsSampleRecorder.LABEL_POSITIVE
                Log.i(TAG, "KWS scoring requested (label=$label)")
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        KwsScorer.scoreRecordedClips(applicationContext, label)
                    }
                }
            }
            ACTION_SET_KWS_TUNING -> {
                // Persists trigger tuning for the wake-word spotter. Nothing listens ambiently any
                // more (MicListener's header explains why), so this no longer changes live
                // behaviour — it is kept because a retrained spotter has to be re-tuned against
                // this same stored value before it could be armed again, and `kws_gate.sh` reads it.
                val threshold = if (intent.hasExtra(EXTRA_THRESHOLD)) {
                    intent.getFloatExtra(EXTRA_THRESHOLD, WakeTrigger.DEFAULT_THRESHOLD)
                } else null
                val frames = if (intent.hasExtra(EXTRA_FRAMES)) {
                    intent.getIntExtra(EXTRA_FRAMES, WakeTrigger.DEFAULT_FRAMES)
                } else null
                Log.i(TAG, "KWS tuning stored (threshold=$threshold frames=$frames) — spotter is not armed")
                lifecycleScope.launch {
                    GhostPrefs.setKwsTuning(applicationContext, threshold, frames)
                }
            }
            ACTION_SET_HOME -> {
                // `location=home/away` needs a point to measure from. With --ed lat/--ed lon this
                // stores that point; with neither, it stores wherever the last GPS fix put the
                // phone, which is the "I am standing in it" case. `--ez clear true` removes it and
                // location= goes back to unknown.
                //
                // The coordinates are deliberately never logged. Everything else about the home
                // point is safe to say out loud; the point itself is the one value in the whole
                // perception layer worth keeping out of logcat.
                val clear = intent.getBooleanExtra(EXTRA_CLEAR, false)
                val radius = if (intent.hasExtra(EXTRA_RADIUS)) {
                    intent.getFloatExtra(EXTRA_RADIUS, HomePoint.DEFAULT_RADIUS_M)
                } else null
                val explicit = if (intent.hasExtra(EXTRA_LAT) && intent.hasExtra(EXTRA_LON)) {
                    intent.getDoubleExtra(EXTRA_LAT, 0.0) to intent.getDoubleExtra(EXTRA_LON, 0.0)
                } else null
                lifecycleScope.launch {
                    if (clear) {
                        GhostPrefs.setHomePoint(applicationContext, null, null, null)
                        Log.i(TAG, "home point cleared — location= returns to unknown")
                    } else {
                        val point = explicit ?: perception?.lastKnownFix()
                        if (point == null) {
                            Log.w(
                                TAG,
                                "SET_HOME: no coordinates given and no GPS fix yet — nothing stored. " +
                                    "Pass --ed lat/--ed lon, or wait for a fix and retry."
                            )
                        } else {
                            GhostPrefs.setHomePoint(applicationContext, point.first, point.second, radius)
                            Log.i(
                                TAG,
                                "home point stored (source=${if (explicit != null) "explicit" else "last fix"} " +
                                    "radius=${radius ?: HomePoint.DEFAULT_RADIUS_M}m)"
                            )
                        }
                    }
                    perception?.reloadHome()
                }
            }
            ACTION_SET_CLOUD_KEY -> {
                // Store or clear the Ollama key without the UI.
                //
                // This exists because the stored key outranks the one baked into the build, so a
                // stale stored key cannot be fixed by rebuilding — which is exactly the state a
                // rejected key leaves behind. `--es key ""` (or --ez clear true) removes the stored
                // value and lets the baked one take over.
                //
                // The key itself is never logged: it is the one value in this service worth
                // keeping out of logcat.
                val clear = intent.getBooleanExtra(EXTRA_CLEAR, false)
                val key = intent.getStringExtra(EXTRA_KEY)
                lifecycleScope.launch {
                    if (clear || key.isNullOrBlank()) {
                        CloudKeyStorage.setKey(applicationContext, null)
                        Log.i(TAG, "stored cloud key cleared — the build's baked-in key now applies")
                    } else {
                        CloudKeyStorage.setKey(applicationContext, key)
                        Log.i(TAG, "stored cloud key replaced (${key.length} chars)")
                    }
                }
            }
            ACTION_SET_INITIATIVE -> {
                // Whether GHOST may start a conversation. Off unless deliberately turned on — the
                // one other ambient behaviour that defaulted on was the always-listening mic.
                val on = intent.getBooleanExtra(EXTRA_ENABLED, false)
                Log.i(TAG, "speaking first set to $on")
                lifecycleScope.launch { GhostPrefs.setInitiativeEnabled(applicationContext, on) }
            }
            ACTION_KILL -> {
                // Independent kill switch (D3, §8): stop everything + wipe retention + stopSelf.
                // setEnabled(false) is also persisted by KillSwitch.killGhost (the caller), but we
                // set it here too so a process death between the two can't lose it.
                Log.i(TAG, "Kill requested (independent kill switch)")
                lifecycleScope.launch {
                    GhostPrefs.setEnabled(applicationContext, false)
                    initiative.stop()
                    perception?.stop(); perception = null
                    bleScanner?.stop(); bleScanner = null
                    radioWatcher?.stop(); radioWatcher = null
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
        bleScanner?.stop()
        bleScanner = null
        radioWatcher?.stop()
        radioWatcher = null
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
        appliedSystemPrompt = prompt
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
        if (!EngineGate.tryAcquire()) {
            Log.i(TAG, "engine busy — voice command dropped: $cmd")
            // Surface it in the chat so the spoken command isn't left as a silent empty bubble.
            ContextStore.update { it.copy(voiceResponse = "(skipped — GHOST was already responding)") }
            return
        }
        try {
            if (cameraCapture != null && VisionTrigger.hasCameraPermission(this) &&
                VisionTrigger.wantsVision(cmd)
            ) {
                cameraCapture?.captureOnDemand(CameraCapture.FACING_BACK)
                delay(VisionTrigger.CAPTURE_WAIT_MS)
            }
            // Phase 6.4: same as the typed path — the trait moves before the prompt is built.
            val nudgeAck = com.coxy.ghost.personality.TraitNudger.applyFromText(
                personalityRepo, cmd, GhostService::refreshPersonalityPrompt
            )

                // Phase 6.5: if he did not *say* what to change, read how he reacted to the
                // last exchange instead. Explicit wins — an inference must never override a
                // request — and this one stays silent whatever it finds.
                if (nudgeAck == null) {
                    val recent = runCatching { memoryStore.recent(2) }.getOrDefault(emptyList())
                    val prevUser = recent.lastOrNull { it.role == "user" }?.content
                    val prevAssistant = recent.lastOrNull { it.role == "assistant" }?.content
                    com.coxy.ghost.personality.ImplicitSignalDetector
                        .detect(prevUser, prevAssistant, cmd)
                        ?.let {
                            com.coxy.ghost.personality.TraitNudger.applyImplicit(
                                personalityRepo, it, GhostService::refreshPersonalityPrompt
                            )
                        }
                }

            // Same as the typed path: applied here, not left to the model. See [DeviceNameSignal].
            val namingAck = com.coxy.ghost.net.DeviceNamer.applyFromText(applicationContext, cmd)

            val cs = ContextStore.state.value
            // Phase 6: recall semantically similar past turns and prepend a [MEMORY] block
            // (rides the sendUserPrompt string alongside [CONTEXT]). recall runs off the engine's
            // single-threaded llamaDispatcher (TFLite + Room); the engine is idle here.
            val memBlock = memoryStore.recall(cmd).toMemoryBlock()
            // Shared with MainActivity's typed path so the two cannot drift apart again.
            val prompt = GhostPrompts.buildUserPrompt(cs, memBlock, cmd)
            Log.i(TAG, "voice command -> inference: $cmd")
            val sb = StringBuilder()
            suspend fun stream(p: String) =
                cloudRouter.route(p, currentSystemPrompt, predictLength = 512).collect { token ->
                    sb.append(token)
                    ContextStore.update { it.copy(voiceResponse = sb.toString()) }
                }

            stream(prompt)

            // The extra passes, identical in rule to the typed path: a web result is input, not
            // output, so if she asked to look something up the reply so far is the request and has
            // to be replaced by an answer. Wired here as well as in MainActivity because prompt
            // assembly on these two paths has drifted apart before, and a spoken "what's the
            // weather" that silently could not search would be exactly that drift again.
            var webPrompt = prompt
            var webSteps = 0
            while (webSteps < com.coxy.ghost.tool.LookupTurn.MAX_STEPS) {
                val req = com.coxy.ghost.tool.LookupTurn.requestIn(sb.toString()) ?: break
                ContextStore.update {
                    it.copy(voiceResponse = com.coxy.ghost.tool.LookupTurn.placeholderFor(applicationContext, req))
                }
                val block = com.coxy.ghost.tool.LookupTurn.blockFor(applicationContext, req)
                webPrompt = com.coxy.ghost.tool.LookupTurn.withBlock(webPrompt, block)
                sb.setLength(0)
                stream(webPrompt)
                webSteps++
            }
            Log.i(TAG, "voice response: ${sb.toString().take(120)}")

            // Phase 7: parse any structured actions from the reply, execute immediate ones, and
            // surface Tier 3 actions for confirmation. Also strip the deprecated "Ghost:" prefix.
            val finalReply = stripGhostPrefix(ActionRunner.run(actionExecutor, sb.toString()))
                .let { if (nudgeAck != null) "$it\n\n$nudgeAck" else it }
                .let { if (namingAck != null) "$it\n$namingAck" else it }
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
            EngineGate.release()
        }
    }

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
        const val ACTION_PTT_DOWN = "com.coxy.ghost.action.PTT_DOWN"
        const val ACTION_PTT_UP = "com.coxy.ghost.action.PTT_UP"
        const val ACTION_KILL = "com.coxy.ghost.action.KILL"
        const val ACTION_CAPTURE_KWS = "com.coxy.ghost.action.CAPTURE_KWS"
        const val ACTION_SCORE_KWS = "com.coxy.ghost.action.SCORE_KWS"
        const val ACTION_SET_KWS_TUNING = "com.coxy.ghost.action.SET_KWS_TUNING"
        const val ACTION_SET_MODEL_PATH = "com.coxy.ghost.action.SET_MODEL_PATH"
        const val ACTION_SET_HOME = "com.coxy.ghost.action.SET_HOME"
        const val ACTION_SET_INITIATIVE = "com.coxy.ghost.action.SET_INITIATIVE"
        const val ACTION_SET_CLOUD_KEY = "com.coxy.ghost.action.SET_CLOUD_KEY"
        const val EXTRA_FACING = "facing"
        const val EXTRA_PATH = "path"
        const val EXTRA_LABEL = "label"
        const val EXTRA_SECONDS = "seconds"
        const val EXTRA_THRESHOLD = "threshold"
        const val EXTRA_FRAMES = "frames"
        const val EXTRA_ENABLED = "enabled"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
        const val EXTRA_RADIUS = "radius"
        const val EXTRA_CLEAR = "clear"
        const val EXTRA_KEY = "key"

        /** Time to let MicListener's AudioRecord/recognizer release the mic before we grab it. */
        private const val KWS_MIC_RELEASE_MS = 600L

        // Vision auto-capture for voice commands (parity with MainActivity's typed path).

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
         *
         * Falls back to the baseline-personality prompt when the local model never loaded (no GGUF
         * staged, or a load failure): [applySystemPromptOnce] never runs on that path, and handing
         * the cloud an empty system prompt would leave it with no persona, no bedrock and no action
         * rules while it can still emit action blocks.
         */
        @JvmStatic
        val currentSystemPrompt: String
            get() = appliedSystemPrompt.ifBlank { baselineSystemPrompt }

        /**
         * Rebuild the in-force system prompt around a new `[PERSONALITY ...]` block.
         *
         * Used by Phase 6.4's trait drift, which must not reload the model: a correction like "be
         * more direct" would otherwise cost a full GGUF reload and drop the conversation context to
         * change one slider. The cloud brain reads [currentSystemPrompt] per request and so obeys
         * the change on the very next turn — including the reply to the correction itself. The
         * local engine's setSystemPrompt is once-only per load and catches up when it next loads.
         */
        @JvmStatic
        fun refreshPersonalityPrompt(personalityBlock: String) {
            appliedSystemPrompt = GhostPrompts.buildSystemPrompt(personalityBlock)
            Log.i(TAG, "system prompt rebuilt for personality change: $personalityBlock")
        }

        @Volatile
        private var appliedSystemPrompt: String = ""

        private val baselineSystemPrompt: String by lazy {
            GhostPrompts.buildSystemPrompt(
                PersonalityTranslator.profileToPromptBlock(PersonalityTranslator.baseline())
            )
        }
    }
}