package com.coxy.ghost

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.view.LayoutInflater
import android.annotation.SuppressLint
import android.view.MotionEvent
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.isModelLoaded
import com.coxy.ghost.action.ActionExecutor
import com.coxy.ghost.action.ActionRunner
import com.coxy.ghost.cloud.CloudKeyStorage
import com.coxy.ghost.cloud.CloudRouter
import com.coxy.ghost.memory.Embedder
import com.coxy.ghost.memory.FactExtractor
import com.coxy.ghost.memory.MemoryStore
import com.coxy.ghost.memory.MemoryStore.Companion.stripGhostPrefix
import com.coxy.ghost.memory.toMemoryBlock
import com.coxy.ghost.perception.CameraCapture
import com.coxy.ghost.perception.ContextStore
import com.coxy.ghost.perception.GhostPrompts
import com.coxy.ghost.perception.MicListener
import com.coxy.ghost.perception.MicState
import com.coxy.ghost.perception.VisionTrigger
import com.coxy.ghost.perception.hasSignal
import com.coxy.ghost.personality.PersonalityProfile
import com.coxy.ghost.personality.PersonalityRepository
import com.coxy.ghost.ui.CloudDialog
import com.coxy.ghost.ui.PersonalityDialog
import com.coxy.ghost.personality.PersonalitySnapshot
import com.coxy.ghost.personality.PersonalityTranslator
import com.coxy.ghost.perception.toStatusLine
import com.google.android.material.button.MaterialButton
import android.provider.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * GHOST control panel + chat UI.
 *
 * The [InferenceEngine] is a process-wide singleton shared with [GhostService]; the Activity only
 * holds a handle for chat and to observe [InferenceEngine.state]. It does NOT destroy the engine —
 * the Service owns teardown, so the model survives the Activity being destroyed/backgrounded.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusTv: TextView
    private lateinit var perceptionTv: TextView
    private lateinit var messagesRv: RecyclerView
    private lateinit var userInputEt: EditText
    private lateinit var sendFab: android.widget.ImageButton
    private lateinit var btnPick: android.widget.Button
    private lateinit var btnStart: android.widget.Button
    private lateinit var btnStop: android.widget.Button
    private lateinit var btnCapture: android.widget.Button
    private lateinit var btnPersonality: android.widget.Button
    private lateinit var btnCloud: android.widget.Button
    private lateinit var btnAssistant: android.widget.Button
    private lateinit var btnMic: android.widget.Button
    private lateinit var btnVoice: android.widget.Button
    private lateinit var statusDot: View

    // Three screens behind the bottom nav.
    private lateinit var screenChat: View
    private lateinit var screenLive: View
    private lateinit var screenSystem: View
    private lateinit var screenPerception: View
    private lateinit var screenDevices: View
    private lateinit var devicesPanel: com.coxy.ghost.ui.DevicesPanel

    /** The Senses screen. Fed from ContextStore by the same collector that drives the chips. */
    private lateinit var perceptionPanel: com.coxy.ghost.ui.PerceptionPanel
    private lateinit var sensorChips: LinearLayout
    private lateinit var sensorCard: View
    /** Chat's resting state: one greeting, hidden once there is a transcript. */
    private lateinit var chatHero: View
    /** The five screens, behind a hamburger instead of across the bottom of every one of them. */
    private lateinit var drawerLayout: androidx.drawerlayout.widget.DrawerLayout
    /** The drawer's own copy of the perception line, so it says something when it is open. */
    private lateinit var drawerSubtitle: TextView
    /** How far the drawer is open, 0..1. Drives the screen's corner radius as well as its motion. */
    private var drawerSlide = 0f
    /** The drawer footer's Start/Stop. The same two actions System has, where you can see her state. */
    private lateinit var drawerPower: android.widget.TextView
    /** Live's mic band. Fed the same audio level the [CONTEXT] block reports. */
    /**
     * The orb. One of them now, and it belongs to Live.
     *
     * Chat used to carry a second instance at the head of the transcript, kept in step with this
     * one so the two could never disagree about what she was doing. On a light page above a text
     * composer it was a graphic sitting where the first question should be, so Chat gave it up and
     * the synchronisation problem went with it.
     */
    private lateinit var liveOrb: com.coxy.ghost.ui.OrbView
    private lateinit var liveState: TextView
    private lateinit var liveHeard: TextView
    private lateinit var liveReply: TextView
    private lateinit var livePttHint: TextView
    /** Top-bar brain indicator. Tinted from the same ramp as the orb; opens the cloud settings. */
    private lateinit var btnTopBrain: android.widget.ImageButton
    /** The composer's camera button. Same action as System's Capture. */
    private lateinit var btnCaptureChat: android.widget.ImageButton
    /** The composer's brain pill: which brain answers, said in a word. Opens the cloud settings. */
    private lateinit var brainPill: TextView
    /** Jump back to the end of the transcript. Only present while the end is somewhere else. */
    private lateinit var scrollLatest: View
    private var pulse: android.animation.ValueAnimator? = null

    private val engine: InferenceEngine by lazy { AiChat.getInferenceEngine(applicationContext) }

    // Phase 6 memory: persists turns + retrieves them by semantic similarity. Shared with the
    // service via the process-wide GhostDatabase singleton + the Embedder object singleton.
    private val memoryStore: MemoryStore by lazy { MemoryStore(applicationContext) }
    // Phase 6.2: extracts durable facts from completed exchanges in the background.
    private val factExtractor: FactExtractor by lazy {
        FactExtractor(applicationContext, engine, memoryStore)
    }
    // Phase 6.3: trait profile + snapshot/rollback repository.
    private val personalityRepo: PersonalityRepository by lazy {
        PersonalityRepository(applicationContext)
    }
    // Phase 7: action executor. Runs whitelisted device actions parsed from the model reply.
    private val actionExecutor: ActionExecutor by lazy { ActionExecutor(applicationContext) }
    // Phase 8: cloud-brain router. Falls back to the local 1.5B engine when cloud is unavailable.
    private val cloudRouter: CloudRouter by lazy { CloudRouter(applicationContext, engine) }

    private var generationJob: Job? = null
    private var isModelReady = false
    private var modelPath: String? = null

    private val messages = mutableListOf<Message>()
    private val lastAssistantMsg = StringBuilder()
    private val messageAdapter = MessageAdapter(messages)

    /** Set only while the launch coroutine replays stored turns; see [followTranscript]. */
    private var replayingHistory = false

    private val pickModel = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { handleSelectedModel(it) } }

    private val requestNotifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result ignored; the FGS runs regardless, notification just won't post if denied */ }

    // Phase 4 + 5 perception permissions. Fine location is required for the location FGS type +
    // GPS; RECORD_AUDIO is required for the continuous mic (D2). Activity recognition + camera are
    // optional but requested together (the manager disables those modalities if not granted).
    private val requestPerceptionPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val fine = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val mic = result[Manifest.permission.RECORD_AUDIO] == true
        if (fine && mic) {
            // Re-arm perception + the FGS types now that the permissions are granted.
            refreshPerception()
            // Proceed with the Start that was gated on the permissions.
            startGhostService()
        } else {
            Toast.makeText(this, "Fine location + mic are needed for perception", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The appearance is applied in GhostApp.onCreate, not here. Doing it at this point — even
        // ahead of super.onCreate — resolved half the resources in one mode and half in the other.
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        // The sample app this grew out of registered a callback here that swallowed every back
        // press and logged "Ignore back press for simplicity". It has been removed: Back is how the
        // drawer closes, and a handler that consumes it unconditionally sits in front of that.

        statusTv = findViewById(R.id.gguf)
        perceptionTv = findViewById(R.id.perception)
        messagesRv = findViewById(R.id.messages)
        // stackFromEnd used to do all the scrolling here, but it pins short content to the bottom
        // of the viewport — with the orb as item 0 an empty transcript would hang it off the floor.
        // Normal top-anchored layout plus an explicit follow (below) puts it where it belongs.
        messagesRv.layoutManager = LinearLayoutManager(this)
        chatHero = layoutInflater.inflate(R.layout.item_chat_hero, messagesRv, false)
        messagesRv.adapter = androidx.recyclerview.widget.ConcatAdapter(
            com.coxy.ghost.ui.ChatHeroAdapter(chatHero), messageAdapter
        )
        userInputEt = findViewById(R.id.user_input)
        sendFab = findViewById(R.id.fab)
        btnPick = findViewById(R.id.btn_pick)
        btnStart = findViewById(R.id.btn_start)
        btnStop = findViewById(R.id.btn_stop)
        btnCapture = findViewById(R.id.btn_capture)
        btnPersonality = findViewById(R.id.btn_personality)
        btnCloud = findViewById(R.id.btn_cloud)
        btnAssistant = findViewById(R.id.btn_assistant)
        btnMic = findViewById(R.id.btn_mic)
        btnVoice = findViewById(R.id.btn_voice)
        statusDot = findViewById(R.id.status_dot)
        screenChat = findViewById(R.id.screen_chat)
        screenLive = findViewById(R.id.screen_live)
        screenSystem = findViewById(R.id.screen_system)
        screenPerception = findViewById(R.id.screen_perception)
        screenDevices = findViewById(R.id.screen_devices)
        devicesPanel = com.coxy.ghost.ui.DevicesPanel(screenDevices, lifecycleScope)
        perceptionPanel = com.coxy.ghost.ui.PerceptionPanel(findViewById(R.id.perception_web))
        perceptionPanel.attach()
        sensorChips = findViewById(R.id.sensor_chips)
        sensorCard = findViewById(R.id.sensor_card)
        drawerLayout = findViewById(R.id.drawer_layout)
        drawerSubtitle = findViewById(R.id.drawer_subtitle)
        findViewById<TextView>(R.id.drawer_version).text =
            getString(R.string.ghost_version_fmt, com.coxy.ghost.BuildConfig.VERSION_NAME)
        drawerPower = findViewById(R.id.drawer_power)
        drawerPower.setOnClickListener {
            if (GhostService.isRunning) stopGhost() else startGhost()
            drawerLayout.closeDrawer(androidx.core.view.GravityCompat.START)
        }
        setUpDrawerMotion()
        setUpThemeChoice()
        liveOrb = findViewById(R.id.live_orb)
        btnTopBrain = findViewById(R.id.btn_top_brain)
        btnCaptureChat = findViewById(R.id.btn_capture_chat)
        scrollLatest = findViewById(R.id.scroll_latest)
        scrollLatest.setOnClickListener {
            val last = (messagesRv.adapter?.itemCount ?: 0) - 1
            if (last >= 0) messagesRv.smoothScrollToPosition(last)
        }
        // Dragging is the other way the distance to the end changes, and it does not go through
        // followTranscript.
        messagesRv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = updateScrollLatest()
        })
        brainPill = findViewById(R.id.brain_pill)
        brainPill.setOnClickListener { showCloudDialog() }
        findViewById<View>(R.id.btn_voice_chat).setOnClickListener { showTab(Tab.LIVE) }
        // The orb samples the mic itself each frame, which is the job the waveform ribbon above it
        // used to do. It reads flat unless the mic is genuinely open, so a dead mic looks dead
        // rather than idling at a convincing height — the reason it is fed from MicListener's own
        // loudness and not from ContextState.audioLevelDb, which is never written.
        liveOrb.levelProvider = {
            // One number for "how much is happening", from whichever half of the conversation is
            // active. Her words beat the orb while she talks; your voice swells it while you do.
            if (com.coxy.ghost.perception.GhostVoice.speaking) {
                com.coxy.ghost.perception.GhostVoice.pulse()
            } else if (MicListener.mode == MicListener.MicMode.OFF) 0f
            else ((MicListener.lastRms - MicListener.RMS_FLOOR) /
                (MicListener.RMS_CEIL - MicListener.RMS_FLOOR))
        }
        liveState = findViewById(R.id.live_state)
        liveHeard = findViewById(R.id.live_heard)
        liveReply = findViewById(R.id.live_reply)
        findViewById<View>(R.id.nav_chat).setOnClickListener { showTab(Tab.CHAT) }
        findViewById<View>(R.id.nav_live).setOnClickListener { showTab(Tab.LIVE) }
        findViewById<View>(R.id.nav_system).setOnClickListener { showTab(Tab.SYSTEM) }
        findViewById<View>(R.id.nav_senses).setOnClickListener { showTab(Tab.SENSES) }
        findViewById<View>(R.id.nav_devices).setOnClickListener { showTab(Tab.DEVICES) }
        // One observer on the adapter covers every path that can add a turn — typed, voice, and the
        // history replayed at launch — which eight separate call sites would not have.
        messageAdapter.registerAdapterDataObserver(
            object : androidx.recyclerview.widget.RecyclerView.AdapterDataObserver() {
                // A new turn pulls the view down to it; a turn being *rewritten* is GHOST typing,
                // and only follows if the bottom was already on screen.
                override fun onChanged() = followTranscript(force = false)
                override fun onItemRangeInserted(positionStart: Int, itemCount: Int) =
                    followTranscript(force = true)
                override fun onItemRangeChanged(positionStart: Int, itemCount: Int) =
                    followTranscript(force = false)
            }
        )
        findViewById<View>(R.id.live_exit).setOnClickListener { showTab(Tab.CHAT) }
        findViewById<View>(R.id.live_mute).setOnClickListener { toggleMic() }
        livePttHint = findViewById(R.id.live_ptt_hint)
        wirePushToTalk(findViewById(R.id.live_ptt), livePttHint)
        showTab(Tab.CHAT)
        // The typed path rewrites the last bubble on every token; the default change animation
        // would cross-fade it on each one, which reads as flicker while GHOST is talking.
        (messagesRv.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)
            ?.supportsChangeAnimations = false

        btnPick.setOnClickListener { pickModel.launch(arrayOf("*/*")) }
        btnStart.setOnClickListener { startGhost() }
        btnStop.setOnClickListener { stopGhost() }
        btnCapture.setOnClickListener { captureFrame() }
        btnPersonality.setOnClickListener { showPersonalityDialog() }
        btnCloud.setOnClickListener { showCloudDialog() }
        btnAssistant.setOnClickListener { showAssistantDialog() }
        findViewById<android.widget.Button>(R.id.btn_capabilities).setOnClickListener {
            startActivity(Intent(this, com.coxy.ghost.capabilities.CapabilitiesActivity::class.java))
        }
        btnMic.setOnClickListener { toggleMic() }
        findViewById<View>(R.id.btn_wake).setOnClickListener {
            val enabled = GhostPrefs.isWakeWordEnabled(this)
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Hey Ghost")
                .setMessage("Say ‘Hey Ghost’, wait for the island to show Listening, then speak your request. Ambient audio stays on this phone and is not saved. Long-press the island to speak any time.")
                .setPositiveButton(if (enabled) "Turn off" else "Turn on") { _, _ ->
                    startService(Intent(this, GhostService::class.java).setAction(GhostService.ACTION_SET_WAKE)
                        .putExtra(GhostService.EXTRA_ENABLED, !enabled))
                }.setNegativeButton("Cancel", null).show()
        }
        btnVoice.setOnClickListener { com.coxy.ghost.ui.VoiceDialog(this, lifecycleScope).show() }
        lifecycleScope.launch(Dispatchers.IO) {
            val on = GhostPrefs.isVoiceEnabled(applicationContext)
            com.coxy.ghost.perception.GhostVoice.enabled = on
            // Applied before the engine finishes initialising as well as after: whichever order
            // they land in, the chosen voice is in place before she first speaks.
            val saved = GhostPrefs.getPreferredVoice(applicationContext)
            // The sentinel means "the neural one"; anything else is one of Android's by name.
            com.coxy.ghost.perception.GhostVoice.piperWanted = saved == null || saved == "piper:on-device"
            if (saved != null && saved != "piper:on-device") {
                com.coxy.ghost.perception.GhostVoice.applyPreferred(saved)
            }
        }
        // The top bar's two discs are shortcuts to the settings behind the state they display,
        // not new behaviour: the brain indicator opens the cloud dialog that decides which brain
        // answers, and the grid opens the screen every other control lives on.
        btnTopBrain.setOnClickListener { showCloudDialog() }
        findViewById<View>(R.id.btn_top_menu).setOnClickListener {
            drawerLayout.openDrawer(androidx.core.view.GravityCompat.START)
        }
        // Back closes the drawer before it leaves the app, which is the one thing a drawer must do
        // and the one thing it does not do for free.
        onBackPressedDispatcher.addCallback(this) {
            if (drawerLayout.isDrawerOpen(androidx.core.view.GravityCompat.START)) {
                drawerLayout.closeDrawer(androidx.core.view.GravityCompat.START)
            } else {
                // Step aside for one press so the default handler can run, then take the door back.
                // Left disabled, the drawer would only ever intercept Back once per process.
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        }
        // The pill's subtitle is the sensing state in one word; tapping it opens the screen that
        // shows the rest. Without this it would ripple under a press and do nothing.
        findViewById<View>(R.id.identity_pill).setOnClickListener { showTab(Tab.SENSES) }
        btnCaptureChat.setOnClickListener { captureFrame() }
        sendFab.setOnClickListener { if (isModelReady) handleUserInput() }

        // Phase 5: prompt once to enable the hardware volume-key kill-switch accessibility service.
        if (!isKillAccessibilityEnabled()) {
            Toast.makeText(this, "For the volume-key kill switch, enable GHOST in Settings → Accessibility", Toast.LENGTH_LONG).show()
        }

        // POST_NOTIFICATIONS is required (Android 13+) for the foreground notification to show.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Observe engine state → status banner + chat enablement. Prewarm the engine off-main.
        lifecycleScope.launch(Dispatchers.Default) {
            engine // trigger lazy init + JNI load off the main thread
            engine.state.collect { state ->
                withContext(Dispatchers.Main) { applyState(state) }
            }
        }

        // Observe the perception layer → live status line. Read-only on the StateFlow; this does
        // not touch the engine. ContextStore.state is process-wide, so this works even when the
        // service was boot-started before the Activity existed.
        lifecycleScope.launch {
            ContextStore.state.collect { cs ->
                withContext(Dispatchers.Main) {
                    renderPerception(cs)
                    // Mic button tracks the actual listener state (OFF means disabled by user).
                    updateMicButton(cs.micState != MicState.OFF)
                }
            }
        }

        // Phase 6: initialise the on-device embedder (copies the ~22 MB model asset to filesDir on
        // first run — off the main thread) and load recent turns into the chat list so the user
        // sees continuity across process death (the in-memory list is otherwise lost on kill).
        lifecycleScope.launch(Dispatchers.IO) {
            // Before anything reads memory: clear turns that were never really said. See
            // [MemoryStore.runCleanups].
            runCatching { memoryStore.runCleanups(applicationContext) }
            com.coxy.ghost.perception.GhostVoice.init(applicationContext)
            Embedder.init(applicationContext)
            val recent = memoryStore.recent(20)
            if (recent.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    val start = messages.size
                    recent.forEach { messages.add(Message(UUID.randomUUID().toString(), it.content, it.role == "user")) }
                    replayingHistory = true
                    messageAdapter.notifyItemRangeInserted(start, recent.size)
                    replayingHistory = false
                }
            }
        }

        // Phase 6: render wake-word voice commands + their streamed replies in the chat.
        // GhostService runs the inference and writes voiceCommand/voiceResponse to ContextStore;
        // this mirrors it into the message list (a user bubble for the spoken command, an assistant
        // bubble that grows with the reply). The EngineGate guard in handleUserInput keeps a typed
        // send from racing a voice generation, so the two paths never interleave tokens.
        lifecycleScope.launch {
            var lastSeq = 0L
            var voiceIdx = -1
            var lastShown = ""
            var lastUnpromptedSeq = 0L
            ContextStore.state.collect { cs ->
                // Something GHOST said on her own. Appended as its own assistant turn rather than
                // written into the voice reply's bubble, and keyed on the seq because the same
                // remark can legitimately repeat.
                if (cs.unpromptedSeq != lastUnpromptedSeq) {
                    lastUnpromptedSeq = cs.unpromptedSeq
                    val said = cs.unpromptedText
                    if (!said.isNullOrBlank()) {
                        withContext(Dispatchers.Main) {
                            messages.add(Message(UUID.randomUUID().toString(), said, false))
                            messageAdapter.notifyItemInserted(messages.size - 1)
                            // The bubble the voice path is streaming into has moved down by one.
                            if (voiceIdx >= 0) voiceIdx = -1
                        }
                    }
                }
                val cmd = cs.voiceCommand
                if (cs.voiceCommandSeq != lastSeq) {
                    // Advance regardless of whether the text survived, matching GhostService's
                    // observer so the two can't disagree about which command is current.
                    lastSeq = cs.voiceCommandSeq
                    if (!cmd.isNullOrBlank()) {
                        voiceIdx = -1
                        withContext(Dispatchers.Main) {
                            messages.add(Message(UUID.randomUUID().toString(), cmd, true))
                            messages.add(Message(UUID.randomUUID().toString(), "", false))
                            messageAdapter.notifyItemRangeInserted(messages.size - 2, 2)
                            voiceIdx = messages.size - 1
                        }
                        lastShown = ""
                    }
                }
                val resp = cs.voiceResponse
                if (voiceIdx >= 0 && resp != null && resp != lastShown) {
                    lastShown = resp
                    withContext(Dispatchers.Main) {
                        if (voiceIdx in 0 until messages.size && !messages[voiceIdx].isUser) {
                            messages[voiceIdx] = messages[voiceIdx].copy(content = resp)
                            messageAdapter.notifyItemChanged(voiceIdx)
                        }
                    }
                }
            }
        }

        // Track the persisted model path (enables Start once a model has been picked once).
        lifecycleScope.launch {
            GhostPrefs.modelPathFlow(applicationContext).collect { p ->
                withContext(Dispatchers.Main) {
                    modelPath = p
                    updateButtons()
                    if (p != null) statusTv.text = "Model: ${File(p).name} — press Start GHOST"
                }
            }
        }

        // Phase 8.1: set initial mic-toggle label from the persisted preference.
        lifecycleScope.launch(Dispatchers.IO) {
            val enabled = GhostPrefs.isMicEnabled(applicationContext)
            withContext(Dispatchers.Main) { updateMicButton(enabled) }
        }
    }

    private fun applyState(state: InferenceEngine.State) {
        val modelBasename = modelPath?.let { File(it).name?.removeSuffix(".gguf") } ?: "none"
        statusTv.text = "${GhostService.isRunning.toRunLabel()} · ${state.label()} · $modelBasename"
        isModelReady = state.isModelLoaded
        userInputEt.isEnabled = isModelReady
        sendFab.isEnabled = isModelReady
        userInputEt.hint = getString(
            if (isModelReady) R.string.ghost_input_hint else R.string.ghost_input_hint_offline
        )
        updateButtons()
    }

    /**
     * The engine's State objects have no readable toString, so the status line used to render as
     * "com.arm.aichat.InferenceEngine${'$'}State${'$'}ProcessingSystemPrompt@a65f1f0". Map to words.
     */
    private fun InferenceEngine.State.label(): String = when {
        this::class.java.simpleName.contains("Ready", true) -> "ready"
        this::class.java.simpleName.contains("ProcessingSystemPrompt", true) -> "waking"
        this::class.java.simpleName.contains("Processing", true) -> "thinking"
        this::class.java.simpleName.contains("Loading", true) -> "loading"
        this::class.java.simpleName.contains("Error", true) -> "error"
        else -> this::class.java.simpleName.lowercase()
    }

    private fun updateButtons() {
        updateStatusDot()
        btnStart.isEnabled = (modelPath != null) && !GhostService.isRunning
        btnStop.isEnabled = GhostService.isRunning
        // On-demand camera capture (D2): available while the daemon is up + CAMERA is granted.
        btnCapture.isEnabled = GhostService.isRunning && VisionTrigger.hasCameraPermission(this)
        // The composer's camera button is the same action, so it must never disagree about
        // whether that action is available.
        btnCaptureChat.isEnabled = btnCapture.isEnabled
        btnCaptureChat.imageTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(
                this,
                if (btnCapture.isEnabled) R.color.on_surface_variant else R.color.on_surface_muted
            )
        )
        // Mic toggle: only meaningful while the daemon is up (it owns the listener).
        btnMic.isEnabled = GhostService.isRunning

        // The drawer's footer button is the same pair of actions as System's Start and Stop, so it
        // reads the same state rather than keeping its own idea of whether she is up.
        val running = GhostService.isRunning
        drawerPower.setText(if (running) R.string.ghost_stop else R.string.ghost_start)
        drawerPower.isEnabled = running || modelPath != null
        drawerPower.alpha = if (drawerPower.isEnabled) 1f else 0.4f
    }

    private enum class Tab { CHAT, LIVE, SYSTEM, SENSES, DEVICES }

    private var currentTab = Tab.CHAT

    /**
     * Screens are swapped by visibility. The orb's animator is tied to its own visibility, so
     * leaving Live mode stops it drawing without any extra bookkeeping here.
     */
    /**
     * Keeps the newest turn in view, which [LinearLayoutManager.setStackFromEnd] used to do before
     * the orb became item 0.
     *
     * A new turn always wins: you should see what you just said even if you were up at the orb
     * when you sent it. A rewrite does not, because a reply rewrites its bubble on every streamed
     * token, and following those would drag the reader back down mid-sentence every time they
     * scrolled up to check what was said earlier — including up to the orb, which is the one place
     * this change exists to make reachable.
     *
     * The launch replay is exempt from both via [replayingHistory]: twenty turns arriving at once
     * is not a turn being taken, and the screen should open on the orb rather than on the tail of
     * a conversation from yesterday.
     */
    private fun followTranscript(force: Boolean) {
        if (replayingHistory) return
        val lm = messagesRv.layoutManager as? LinearLayoutManager ?: return
        val last = (messagesRv.adapter?.itemCount ?: 0) - 1
        if (last < 0) return
        // -2 rather than -1: by the time this runs the new item exists but has not been laid out.
        if (force || lm.findLastVisibleItemPosition() >= last - 2) {
            messagesRv.post { messagesRv.scrollToPosition(last) }
        }
        updateScrollLatest()
    }

    /**
     * Show the way back to the end, but only when there is one.
     *
     * The rule above is deliberate — a new turn only pulls the view down if you were already near
     * the bottom, so reading back is not yanked away every time a token lands. The cost of that
     * rule is that a reply can finish several screens below where you are looking with nothing to
     * say so, which is what this answers.
     */
    private fun updateScrollLatest() {
        val lm = messagesRv.layoutManager as? LinearLayoutManager ?: return
        val last = (messagesRv.adapter?.itemCount ?: 0) - 1
        val away = last >= 0 && lm.findLastVisibleItemPosition() < last
        val show = away && currentTab == Tab.CHAT
        if (show == (scrollLatest.visibility == View.VISIBLE)) return
        if (show) {
            scrollLatest.alpha = 0f
            scrollLatest.visibility = View.VISIBLE
            scrollLatest.animate().alpha(1f).setDuration(140L).start()
        } else {
            scrollLatest.animate().alpha(0f).setDuration(140L)
                .withEndAction { scrollLatest.visibility = View.GONE }.start()
        }
    }

    /**
     * The drawer, moving the way the Claude app's does.
     *
     * A stock DrawerLayout slides a panel over a stationary screen and drops a black scrim on top of
     * it. This does the opposite: nothing is covered. The screen itself travels right by exactly the
     * drawer's width, shrinks a little as it goes, and rounds its corners on the way — so the drawer
     * is not laid over the app, the app is moved aside to show what was behind it. The screen stays
     * legible the whole time, which is the point; a scrim says "you have left", and this says "this
     * is still here, and here is where else you can go".
     *
     * Three details carry it, and all three matter:
     *
     *  - The scrim is cleared. With the screen still fully lit, a wash of black over it would be the
     *    only dimmed thing on a display that is otherwise showing two lit surfaces side by side.
     *  - The pivot is the screen's left edge, so shrinking opens a gap at the top and bottom without
     *    pulling the screen back from the drawer it is sitting against.
     *  - The corner radius is driven from the same slide fraction rather than switched on at the
     *    end, because a square screen that suddenly rounds when the drawer latches open is a jump
     *    exactly where the eye is already following movement.
     */
    /**
     * The appearance picker: light, dark, or whatever the phone is doing.
     *
     * Applied through AppCompatDelegate rather than by swapping colours by hand, so the whole
     * resource system switches with it — values-night, the WebView's prefers-color-scheme, and the
     * status bar polarity all follow from the one call. Choosing a mode that is already active is a
     * no-op inside AppCompat, so no guard is needed against re-applying it.
     */
    private fun setUpThemeChoice() {
        val choices = listOf(
            R.id.theme_light to androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO,
            R.id.theme_dark to androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES,
            R.id.theme_system to androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
        )

        fun paint(active: Int) {
            for ((id, mode) in choices) {
                val on = mode == active
                findViewById<TextView>(id).apply {
                    setBackgroundResource(if (on) R.drawable.bg_theme_choice else 0)
                    setTextColor(
                        ContextCompat.getColor(
                            this@MainActivity,
                            if (on) R.color.accent else R.color.on_surface_variant
                        )
                    )
                }
            }
        }

        paint(savedNightMode(this))

        for ((id, mode) in choices) {
            findViewById<View>(id).setOnClickListener {
                paint(mode)
                getSharedPreferences(THEME_PREFS, MODE_PRIVATE)
                    .edit().putInt(KEY_NIGHT_MODE, mode).apply()
                // Recreates the Activity. Everything on screen is rebuilt from state that outlives
                // it — the transcript comes back from the memory DB, the daemon and the model are
                // owned by the Service — so there is nothing here to preserve across the switch.
                androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(mode)
            }
        }
    }

    private fun setUpDrawerMotion() {
        val content = findViewById<View>(R.id.main)
        val drawer = findViewById<View>(R.id.nav_drawer)

        // Wide enough that the drawer is the screen while it is open, narrow enough that the app
        // behind it is still recognisably there. The Claude app leaves about a fifth showing.
        drawer.layoutParams = drawer.layoutParams.apply {
            width = (resources.displayMetrics.widthPixels * 0.80f).toInt()
        }

        drawerLayout.setScrimColor(android.graphics.Color.TRANSPARENT)
        content.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, CONTENT_CORNER * drawerSlide)
            }
        }
        content.clipToOutline = true

        drawerLayout.addDrawerListener(object : androidx.drawerlayout.widget.DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                drawerSlide = slideOffset
                content.pivotX = 0f
                content.pivotY = content.height / 2f
                content.translationX = drawerView.width * slideOffset
                val scale = 1f - CONTENT_SHRINK * slideOffset
                content.scaleX = scale
                content.scaleY = scale
                // The shadow is what stops the two surfaces reading as one flat plane once the
                // scrim is gone.
                content.elevation = CONTENT_LIFT * slideOffset
                content.invalidateOutline()
            }
        })
    }

    private fun showTab(tab: Tab) {
        currentTab = tab
        // Choosing a destination is the end of navigating, so the drawer gets out of the way
        // without being asked.
        if (::drawerLayout.isInitialized) {
            drawerLayout.closeDrawer(androidx.core.view.GravityCompat.START, true)
        }
        screenChat.visibility = if (tab == Tab.CHAT) View.VISIBLE else View.GONE
        screenLive.visibility = if (tab == Tab.LIVE) View.VISIBLE else View.GONE
        screenSystem.visibility = if (tab == Tab.SYSTEM) View.VISIBLE else View.GONE
        screenPerception.visibility = if (tab == Tab.SENSES) View.VISIBLE else View.GONE
        screenDevices.visibility = if (tab == Tab.DEVICES) View.VISIBLE else View.GONE
        // Walking out of Live stops her talking. Finishing a sentence to a screen he has already
        // left is the phone-assistant behaviour this project exists to not be.
        if (tab != Tab.LIVE) com.coxy.ghost.perception.GhostVoice.stop()

        // Live takes the whole screen.
        //
        // It is the only screen that does not follow the phone's light setting — the orb is a
        // thousand luminous points and reads against nothing but dark — so by day the top bar would
        // be a strip of paper sitting above a black field, and the status bar would be painting
        // dark icons onto it. Both follow the field instead: the bar goes away, and the system
        // icons flip to light for as long as Live is up.
        val live = tab == Tab.LIVE
        findViewById<View>(R.id.top_bar).visibility = if (live) View.GONE else View.VISIBLE
        // The root carries the colour of the strips behind the status and gesture bars. The layout
        // is inset by fitsSystemWindows, so those bands are painted by the root rather than by the
        // screen inside it, and Live's black otherwise stopped short of both ends of the phone.
        drawerLayout.setBackgroundColor(
            ContextCompat.getColor(this, if (live) R.color.live_field else R.color.abyss)
        )
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars =
                if (live) false else resources.getBoolean(R.bool.light_system_bars)
        // Redrawn on arrival, never scanned on arrival: a sweep wakes every device on the network
        // and should be something Coxy asks for, not something a glance triggers.
        if (tab == Tab.DEVICES) devicesPanel.refresh()

        val on = ContextCompat.getColor(this, R.color.accent)
        val off = ContextCompat.getColor(this, R.color.on_surface_muted)
        fun paint(iconId: Int, labelId: Int, navId: Int, active: Boolean) {
            findViewById<android.widget.ImageView>(iconId).imageTintList =
                android.content.res.ColorStateList.valueOf(if (active) on else off)
            findViewById<TextView>(labelId).setTextColor(if (active) on else off)
            // The row's background is touch feedback in both states now; being the current screen
            // is said by the tint above, the way it is in the Claude app's drawer.
            findViewById<View>(navId).setBackgroundResource(R.drawable.bg_nav_active)
        }
        paint(R.id.nav_chat_icon, R.id.nav_chat_label, R.id.nav_chat, tab == Tab.CHAT)
        paint(R.id.nav_live_icon, R.id.nav_live_label, R.id.nav_live, tab == Tab.LIVE)
        paint(R.id.nav_system_icon, R.id.nav_system_label, R.id.nav_system, tab == Tab.SYSTEM)
        paint(R.id.nav_senses_icon, R.id.nav_senses_label, R.id.nav_senses, tab == Tab.SENSES)
        paint(R.id.nav_devices_icon, R.id.nav_devices_label, R.id.nav_devices, tab == Tab.DEVICES)
    }

    /**
     * Renders perception into the chat screen's chip row, the compact top-bar label, and the orb.
     *
     * Chips follow the same rule as the [CONTEXT] prompt block: a channel that knows nothing shows
     * nothing, rather than a row of "unknown".
     */
    private fun renderPerception(cs: com.coxy.ghost.perception.ContextState) {
        val sensing = if (cs.hasSignal()) cs.activity.name.lowercase() else getString(R.string.ghost_perception_idle)
        perceptionTv.text = sensing
        // The drawer shows the same line. It is the only thing about her state visible while the
        // drawer covers the top bar that would otherwise be reporting it.
        drawerSubtitle.text = sensing
        // The Senses screen reads the same state object as the chips, so the two can never
        // disagree about what is being sensed.
        perceptionPanel.render(cs)

        data class Chip(val text: String, val live: Boolean)
        val chips = buildList {
            if (cs.activity != com.coxy.ghost.perception.Activity.UNKNOWN) add(Chip("motion: ${cs.activity.name.lowercase()}", false))
            if (cs.lightBucket != com.coxy.ghost.perception.LightBucket.UNKNOWN) add(Chip("light: ${cs.lightBucket.name.lowercase()}", false))
            if (cs.inPocket == true) add(Chip("phone: pocket", false))
            else if (cs.posture != com.coxy.ghost.perception.Posture.UNKNOWN) add(Chip("phone: ${cs.posture.name.lowercase().replace('_', ' ')}", false))
            if (cs.micState != MicState.UNKNOWN) {
                add(Chip("listening: ${cs.micState != MicState.OFF}", cs.micState == MicState.VOICE))
            }
            if (cs.nearbyCount > 0) add(Chip("nearby: ${cs.nearbyCount}", false))
            // Which brain is actually answering. Worth a permanent slot: GHOST ran on the local
            // 1.5B for a long time while the cloud was assumed to be primary, and nothing on
            // screen said so.
            val brain = com.coxy.ghost.cloud.CloudRouter.lastBrain
            if (brain != com.coxy.ghost.cloud.CloudRouter.Companion.Brain.UNKNOWN) {
                val model = com.coxy.ghost.cloud.CloudRouter.lastModel
                val cloud = brain == com.coxy.ghost.cloud.CloudRouter.Companion.Brain.CLOUD
                add(Chip("brain: ${brain.label}" + if (cloud && model != null) " · $model" else "", cloud))
            }
        }
        // An empty card reads as a stray blank pane; a channel with nothing to say shows nothing,
        // the same rule the chips themselves follow.
        sensorCard.visibility = if (chips.isEmpty()) View.GONE else View.VISIBLE
        sensorChips.removeAllViews()
        val pad = (10 * resources.displayMetrics.density).toInt()
        val padV = (6 * resources.displayMetrics.density).toInt()
        for (c in chips) {
            sensorChips.addView(TextView(this).apply {
                text = c.text
                setTextAppearance(R.style.TextAppearance_Ghost_Label)
                if (c.live) setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
                setBackgroundResource(if (c.live) R.drawable.bg_chip_active else R.drawable.bg_chip)
                setPadding(pad, padV, pad, padV)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (8 * resources.displayMetrics.density).toInt() }
            })
        }

        // Live mode mirrors the voice channel and drives the orb.
        cs.voiceCommand?.let { liveHeard.text = it }
        liveReply.text = com.coxy.ghost.ui.Markup.render(
            cs.voiceResponse ?: "",
            ContextCompat.getColor(this, R.color.on_live_field_muted)
        )
        val state = when {
            // Speaking outranks thinking: the engine can still be finishing a reply she has
            // already started saying, and what she is visibly doing is talking.
            com.coxy.ghost.perception.GhostVoice.speaking ->
                com.coxy.ghost.ui.OrbView.OrbState.SPEAKING
            EngineGate.busy -> com.coxy.ghost.ui.OrbView.OrbState.THINKING
            cs.micState == MicState.VOICE -> com.coxy.ghost.ui.OrbView.OrbState.LISTENING
            cs.micState == MicState.SILENT -> com.coxy.ghost.ui.OrbView.OrbState.IDLE
            else -> com.coxy.ghost.ui.OrbView.OrbState.IDLE
        }
        liveOrb.setOrbState(state)

        // Which brain answered, carried by the orb's temperature and the top-bar disc as well as
        // by the chip. It is the single most consequential thing about a reply and the easiest to
        // miss in a line of text.
        val brainNow = com.coxy.ghost.cloud.CloudRouter.lastBrain
        val onCloud = brainNow == com.coxy.ghost.cloud.CloudRouter.Companion.Brain.CLOUD
        liveOrb.setCloudBrain(onCloud)
        // UNKNOWN means no query has been answered yet, which is not the same as "local". The orb
        // has to pick a temperature and rests cool, but the disc must not claim a brain it has no
        // evidence for — it stays unlit until something has actually answered.
        btnTopBrain.imageTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(
                this,
                when (brainNow) {
                    com.coxy.ghost.cloud.CloudRouter.Companion.Brain.CLOUD -> R.color.accent_hot
                    com.coxy.ghost.cloud.CloudRouter.Companion.Brain.UNKNOWN -> R.color.on_surface_muted
                    else -> R.color.cool
                }
            )
        )
        btnTopBrain.setBackgroundResource(
            if (onCloud) R.drawable.bg_glass_circle_active else R.drawable.bg_glass_circle
        )
        // The composer says it in a word. UNKNOWN is not "local" — nothing has answered yet — so it
        // names the brain that would answer rather than one that has.
        brainPill.setText(
            when (brainNow) {
                com.coxy.ghost.cloud.CloudRouter.Companion.Brain.CLOUD -> R.string.ghost_brain_cloud
                else -> R.string.ghost_brain_local
            }
        )
        brainPill.setTextColor(
            ContextCompat.getColor(
                this,
                if (onCloud) R.color.accent else R.color.on_surface_variant
            )
        )

        // DORMANT has to mean dormant. Nothing listens ambiently any more, so the idle label
        // distinguishes "the mic is latched open, speak whenever" from "nothing is listening" —
        // otherwise the one screen whose job is to show whether GHOST can hear you would render
        // both states identically.
        liveState.setText(
            when {
                state == com.coxy.ghost.ui.OrbView.OrbState.THINKING -> R.string.ghost_live_thinking
                state == com.coxy.ghost.ui.OrbView.OrbState.LISTENING -> R.string.ghost_live_listening
                state == com.coxy.ghost.ui.OrbView.OrbState.SPEAKING -> R.string.ghost_live_speaking
                MicListener.mode == MicListener.MicMode.OPEN -> R.string.ghost_live_open
                else -> R.string.ghost_live_idle
            }
        )
    }

    /**
     * The dot is the one piece of chrome that is always truthful about whether GHOST is alive:
     * green and breathing while the daemon runs, flat grey when it does not. The pulse is stopped
     * rather than left running invisibly, so a stopped GHOST costs no frames.
     */
    private fun updateStatusDot() {
        val live = GhostService.isRunning
        val colour = ContextCompat.getColor(this, if (live) R.color.state_live else R.color.state_idle)
        statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(colour)
        if (live && pulse == null) {
            pulse = android.animation.ValueAnimator.ofFloat(0.35f, 1f).apply {
                duration = 1500L
                repeatMode = android.animation.ValueAnimator.REVERSE
                repeatCount = android.animation.ValueAnimator.INFINITE
                addUpdateListener { statusDot.alpha = it.animatedValue as Float }
                start()
            }
        } else if (!live) {
            pulse?.cancel()
            pulse = null
            statusDot.alpha = 1f
        }
    }

    /**
     * Flip the "leave the mic on" latch.
     *
     * The service owns the write. This used to set the preference here *and* fire
     * ACTION_TOGGLE_MIC, whose handler flipped it again — so the button relabelled itself and the
     * mic never changed state. The desired value now travels with the intent.
     */
    private fun toggleMic() {
        lifecycleScope.launch(Dispatchers.IO) {
            val next = !GhostPrefs.isMicEnabled(applicationContext)
            withContext(Dispatchers.Main) { updateMicButton(next) }
            startServiceWithIntent(
                Intent(applicationContext, GhostService::class.java)
                    .setAction(GhostService.ACTION_TOGGLE_MIC)
                    .putExtra(GhostService.EXTRA_ENABLED, next)
            )
        }
    }

    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun requestRecordAudio() {
        requestPerceptionPerms.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
    }

    /**
     * Hold-to-speak. The mic is open for exactly as long as the button is held — press opens the
     * recognizer, release closes it and sends whatever was said.
     *
     * ACTION_UP alone is not enough: if the finger slides off the button, or the gesture is stolen
     * by a parent view, only ACTION_CANCEL arrives. Missing it would leave the mic open with the
     * user believing they had let go, which is the failure mode this whole redesign exists to end.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun wirePushToTalk(button: View, hint: android.widget.TextView) {
        button.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!hasRecordAudioPermission()) {
                        hint.setText(R.string.ghost_live_mic_denied)
                        requestRecordAudio()
                        return@setOnTouchListener false
                    }
                    v.isPressed = true
                    hint.setText(R.string.ghost_ptt_listening)
                    startServiceWithAction(GhostService.ACTION_PTT_DOWN)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    hint.setText(R.string.ghost_ptt_hold)
                    startServiceWithAction(GhostService.ACTION_PTT_UP)
                    true
                }
                else -> false
            }
        }
    }

    private fun updateMicButton(enabled: Boolean) {
        btnMic.text = if (enabled) getString(R.string.ghost_mic_on) else getString(R.string.ghost_mic_off)
    }

    private fun startGhost() {
        if (modelPath == null) {
            Toast.makeText(this, "Pick a model first", Toast.LENGTH_SHORT).show()
            return
        }
        // Gate on perception permissions first. Fine location is required (location FGS type +
        // GPS); activity recognition is optional but requested together.
        val missing = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) missing += Manifest.permission.ACCESS_FINE_LOCATION
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) missing += Manifest.permission.ACTIVITY_RECOGNITION
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) missing += Manifest.permission.CAMERA
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) missing += Manifest.permission.RECORD_AUDIO
        if (missing.isNotEmpty()) {
            requestPerceptionPerms.launch(missing.toTypedArray())
            return
        }
        startGhostService()
    }

    /** Actually start the foreground service (called once permissions are in place). */
    private fun startGhostService() {
        lifecycleScope.launch {
            GhostPrefs.setEnabled(applicationContext, true)
            val svc = Intent(applicationContext, GhostService::class.java)
            startServiceWithIntent(svc)
            updateButtons()
        }
    }

    /** Start the service in the O+/pre-O correct mode for the given explicit intent. */
    private fun startServiceWithIntent(svc: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc)
        } else {
            startService(svc)
        }
    }

    /** Convenience: start the foreground service with a specific action (Phase 6.3 reload). */
    private fun startServiceWithAction(action: String) {
        startServiceWithIntent(Intent(applicationContext, GhostService::class.java).setAction(action))
    }

    /**
     * Tell a running service to restart perception with the now-granted permissions and re-run
     * startForegroundCompat (adds the location FGS type). No-op if the service isn't up.
     */
    private fun refreshPerception() {
        if (!GhostService.isRunning) return
        val svc = Intent(applicationContext, GhostService::class.java)
            .setAction(GhostService.ACTION_REFRESH_PERCEPTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc)
        else startService(svc)
    }

    private fun stopGhost() {
        lifecycleScope.launch {
            GhostPrefs.setEnabled(applicationContext, false)
            stopService(Intent(applicationContext, GhostService::class.java))
            updateButtons()
        }
    }

    private fun handleSelectedModel(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val name = queryDisplayName(uri) ?: "ghost-model.gguf"
            val dest = File(ensureModelsDirectory(), name)
            if (!dest.exists()) {
                withContext(Dispatchers.Main) { statusTv.text = "Copying model $name..." }
                Log.i(TAG, "Copying model to ${dest.path}")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(dest).use { input.copyTo(it) }
                }
            }
            GhostPrefs.setModelPath(applicationContext, dest.path)
            withContext(Dispatchers.Main) {
                modelPath = dest.path
                // Auto-start the daemon once a model is staged.
                startGhost()
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) return c.getString(idx)
        }
        return null
    }

    private fun handleUserInput() {
        userInputEt.text.toString().also { userMsg ->
            if (userMsg.isEmpty()) {
                Toast.makeText(this, "Input message is empty!", Toast.LENGTH_SHORT).show()
                return
            }
            // EngineGate: the engine is single-threaded; a voice command may already be generating
            // (driven by GhostService). Reject a typed send in that case rather than racing it.
            if (!EngineGate.tryAcquire()) {
                Toast.makeText(this, "GHOST is responding — wait a moment", Toast.LENGTH_SHORT).show()
                return
            }
            userInputEt.text = null
            userInputEt.isEnabled = false
            sendFab.isEnabled = false

            messages.add(Message(UUID.randomUUID().toString(), userMsg, true))
            lastAssistantMsg.clear()
            messages.add(Message(UUID.randomUUID().toString(), lastAssistantMsg.toString(), false))
            // Both rows must be announced. Adding two items and then only calling notifyItemChanged
            // for the last one leaves RecyclerView's cached count behind getItemCount(), which is
            // the "Inconsistency detected" crash — the voice mirror below already does this right.
            messageAdapter.notifyItemRangeInserted(messages.size - 2, 2)

            // Context injection (Phase 4 sensors + Phase 5 mic/camera): the only per-query context
            // channel is the sendUserPrompt string (setSystemPrompt is once-only). We prepend a
            // compact [CONTEXT ...] block; the UI still shows the clean user text above.
            generationJob = lifecycleScope.launch(Dispatchers.Default) {
                var finalReply: String? = null
                try {
                    // On-demand camera (D2): if the query seems to need vision, grab a fresh frame
                    // first so its metadata rides the [CONTEXT] block. The capture is async on the
                    // camera thread; wait briefly for it to land in ContextStore.
                    if (GhostService.isRunning && VisionTrigger.hasCameraPermission(this@MainActivity) &&
                        VisionTrigger.wantsVision(userMsg)
                    ) {
                        sendCaptureFrame(CameraCapture.FACING_BACK)
                        delay(VisionTrigger.CAPTURE_WAIT_MS)
                    }
                    // Phase 6.4: a correction about her manner ("less sarcastic", "be more
                    // direct") changes the trait before this turn's prompt is built, so the reply
                    // that acknowledges the correction is already the corrected voice.
                    val nudgeAck = com.coxy.ghost.personality.TraitNudger.applyFromText(
                        personalityRepo, userMsg, GhostService::refreshPersonalityPrompt
                    )

                    // Phase 6.5: if he did not *say* what to change, read how he reacted to the
                    // last exchange instead. Explicit wins — an inference must never override a
                    // request — and this one stays silent whatever it finds.
                    if (nudgeAck == null) {
                        val recent = runCatching { memoryStore.recent(2) }.getOrDefault(emptyList())
                        val prevUser = recent.lastOrNull { it.role == "user" }?.content
                        val prevAssistant = recent.lastOrNull { it.role == "assistant" }?.content
                        com.coxy.ghost.personality.ImplicitSignalDetector
                            .detect(prevUser, prevAssistant, userMsg)
                            ?.let {
                                com.coxy.ghost.personality.TraitNudger.applyImplicit(
                                    personalityRepo, it, GhostService::refreshPersonalityPrompt
                                )
                            }
                    }

                    // Naming a device is applied here rather than left to the model. Twice it
                    // replied "I've locked that in" and emitted no action; see [DeviceNameSignal].
                    val namingAck = com.coxy.ghost.net.DeviceNamer
                        .applyFromText(this@MainActivity, userMsg)

                    val cs = ContextStore.state.value
                    // Phase 6: recall semantically similar past turns and prepend a [MEMORY] block.
                    // recall runs off llamaDispatcher (TFLite + Room), engine idle. Assembly is
                    // shared with the voice path so the two cannot drift apart again.
                    val memBlock = memoryStore.recall(userMsg).toMemoryBlock()
                    val prompt = GhostPrompts.buildUserPrompt(cs, memBlock, userMsg)

                    suspend fun stream(p: String) =
                        cloudRouter.route(p, GhostService.currentSystemPrompt, predictLength = 512, userText = userMsg)
                            .collect { token ->
                                withContext(Dispatchers.Main) {
                                    val n = messages.size
                                    check(n > 0 && !messages[n - 1].isUser)
                                    messages.removeAt(n - 1).copy(
                                        content = lastAssistantMsg.append(token).toString()
                                    ).let { messages.add(it) }
                                    messageAdapter.notifyItemChanged(messages.size - 1)
                                }
                            }

                    stream(prompt)

                    // The two actions whose results are input rather than output. If she asked to
                    // look something up, the reply so far is the request, not the answer: make the
                    // call and generate again with what came back. Each earlier reply is dropped —
                    // showing it would mean Coxy reads "let me check" and watches it be replaced.
                    //
                    // A loop rather than a single extra pass, so search-then-read-the-best-result
                    // works, bounded by MAX_STEPS so a model that keeps asking runs out of steps.
                    var webPrompt = prompt
                    var webSteps = 0
                    while (webSteps < com.coxy.ghost.tool.LookupTurn.MAX_STEPS) {
                        val req = com.coxy.ghost.tool.LookupTurn
                            .requestIn(lastAssistantMsg.toString()) ?: break
                        withContext(Dispatchers.Main) {
                            val n = messages.size
                            check(n > 0 && !messages[n - 1].isUser)
                            messages.removeAt(n - 1)
                                .copy(content = com.coxy.ghost.tool.LookupTurn.placeholderFor(this@MainActivity, req))
                                .let { messages.add(it) }
                            messageAdapter.notifyItemChanged(messages.size - 1)
                        }
                        val block = com.coxy.ghost.tool.LookupTurn.blockFor(this@MainActivity, req)
                        webPrompt = com.coxy.ghost.tool.LookupTurn.withBlock(webPrompt, block)
                        // The placeholder is what he reads while the next pass spins up, but it
                        // must not become the head of the real reply.
                        lastAssistantMsg.clear()
                        stream(webPrompt)
                        webSteps++
                    }

                    // After the reply completes (engine idle): parse any actions, execute them, strip
                    // the deprecated "Ghost:" prefix, then persist the final reply for future recall.
                    val rawReply = lastAssistantMsg.toString()
                    val reply = stripGhostPrefix(ActionRunner.run(actionExecutor, rawReply))
                        .let { if (nudgeAck != null) "$it\n\n$nudgeAck" else it }
                        .let { if (namingAck != null) "$it\n$namingAck" else it }
                    finalReply = reply

                    // Update the assistant bubble if action results changed the text.
                    if (reply != rawReply) {
                        withContext(Dispatchers.Main) {
                            lastAssistantMsg.clear()
                            lastAssistantMsg.append(reply)
                            val n = messages.size
                            check(n > 0 && !messages[n - 1].isUser)
                            messages.removeAt(n - 1).copy(content = reply)
                                .let { messages.add(it) }
                            messageAdapter.notifyItemChanged(messages.size - 1)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The engine throws at collection time when no model is loaded
                    // (check(state is ModelReady) in InferenceEngineImpl), and CloudRouter only
                    // falls back to it — it does not catch what the fallback itself throws. Uncaught
                    // here the exception escapes lifecycleScope and takes the Activity down
                    // mid-conversation, which is how a missing GGUF turned into a crash.
                    Log.w(TAG, "typed inference failed", e)
                    showGenerationError(e)
                } finally {
                    // onStop() cancels this job, and everything below the collect used to live in
                    // the cancellable body — so backgrounding the app mid-reply dropped the whole
                    // exchange from memory, and GHOST had no recollection of a conversation the
                    // user had just had. NonCancellable persists whatever was generated, partial
                    // reply included.
                    withContext(NonCancellable + Dispatchers.IO) {
                        val reply = finalReply ?: stripGhostPrefix(lastAssistantMsg.toString())
                        if (reply.isNotBlank()) {
                            runCatching {
                                memoryStore.remember("user", userMsg, "typed")
                                memoryStore.remember("assistant", ActionRunner.withoutActionLines(reply), "typed")
                                memoryStore.prune()
                            }.onFailure { Log.w(TAG, "failed to persist typed turn", it) }
                            // Phase 6.2: durable-fact extraction runs on its own process-surviving
                            // scope and respects EngineGate, so it can't race the next query.
                            factExtractor.extract(userMsg, reply, "typed")
                        }
                    }
                }
            }
            // Fires even if the job is cancelled before its body starts (Activity stopped between
            // the acquire above and the first dispatch), so the gate cannot leak and wedge the
            // engine for the life of the process.
            generationJob?.invokeOnCompletion {
                EngineGate.release()
                runOnUiThread {
                    userInputEt.isEnabled = isModelReady
                    sendFab.isEnabled = isModelReady
                }
            }
        }
    }

    /**
     * Surface a failed generation in the assistant bubble instead of letting it crash the Activity.
     * Uses a guard rather than `check` so a failure while the bubble list is in an unexpected shape
     * can't throw a second time out of the error handler.
     */
    private suspend fun showGenerationError(e: Exception) {
        val text = "[GHOST couldn't answer: ${e.message ?: e.javaClass.simpleName}]"
        withContext(Dispatchers.Main) {
            lastAssistantMsg.clear()
            lastAssistantMsg.append(text)
            val n = messages.size
            if (n > 0 && !messages[n - 1].isUser) {
                messages.removeAt(n - 1).copy(content = text).let { messages.add(it) }
                messageAdapter.notifyItemChanged(messages.size - 1)
            }
        }
    }


    private fun ensureModelsDirectory() =
        File(filesDir, DIRECTORY_MODELS).also {
            if (it.exists() && !it.isDirectory) it.delete()
            if (!it.exists()) it.mkdir()
        }

    /** Trigger an on-demand camera capture (Phase 5, D2). Fire-and-forget; metadata lands async. */
    private fun captureFrame() {
        sendCaptureFrame(CameraCapture.FACING_BACK)
        Toast.makeText(this, "Capturing frame…", Toast.LENGTH_SHORT).show()
    }

    private fun sendCaptureFrame(facing: String) {
        if (!GhostService.isRunning) return
        val svc = Intent(applicationContext, GhostService::class.java)
            .setAction(GhostService.ACTION_CAPTURE_FRAME)
            .putExtra(GhostService.EXTRA_FACING, facing)
        // The service is already foreground while the Activity is open → plain startService.
        runCatching { startService(svc) }
    }

    /** Whether the hardware volume-key kill-switch accessibility service is enabled. */
    private fun isKillAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val flat = "$packageName/${GhostAccessibilityService::class.java.name}"
        return enabled.split(':').any { it.equals(flat, ignoreCase = true) }
    }

    /**
     * Phase 6.3: open the trait editor. 8 sliders (0-100), hard-floor clamping via
     * PersonalityTranslator, snapshot + rollback versioning, and a broadcast that tells
     * GhostService to reload the model so the new prompt block takes effect.
     */
    private fun showPersonalityDialog() =
        PersonalityDialog(this, lifecycleScope, personalityRepo, ::startServiceWithAction).show()

    private fun showCloudDialog() = CloudDialog(this, lifecycleScope).show()

    /**
     * Make GHOST the device's default assistant — the app the assist gesture summons.
     *
     * Two routes, because there is no third-party API to take the role: GHOST is a rooted system app
     * so it can write the secure setting itself ("Set GHOST" — immediate, no reboot), and the system
     * picker is offered as the ordinary fallback. The label reflects who holds the role right now.
     */
    private fun showAssistantDialog() {
        val isGhost = AssistantRole.isDefault(this)
        val current = AssistantRole.currentLabel(this)
        val message = if (isGhost) {
            "GHOST is your default assistant. The assist gesture will summon her over any screen.\n\n" +
                "You can hand the role back to the system, or open Android's assistant settings."
        } else {
            "Your assistant is currently $current. Set GHOST so the assist gesture summons her " +
                "instead — she is a rooted system app, so this applies immediately without a reboot.\n\n" +
                "Or open Android's settings to choose her by hand."
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.ghost_assistant)
            .setMessage(message)
            .setNeutralButton("Android settings") { _, _ ->
                AssistantRole.openSystemAssistantSettings(this)
            }
            .setNegativeButton("Close", null)

        if (isGhost) {
            builder.setPositiveButton("Hand back") { _, _ -> applyAssistant(makeDefault = false) }
        } else {
            builder.setPositiveButton("Set GHOST") { _, _ -> applyAssistant(makeDefault = true) }
        }
        builder.show()
    }

    private fun applyAssistant(makeDefault: Boolean) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                if (makeDefault) AssistantRole.setAsDefaultViaRoot() else AssistantRole.clearViaRoot()
            }
            val msg = result.fold(
                onSuccess = {
                    if (makeDefault) "GHOST is now your default assistant" else "Assistant handed back to the system"
                },
                onFailure = { "Couldn't change the assistant (root denied?): ${it.message}" },
            )
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        // Retry deferred capture access without interrupting an existing interaction.
        if (GhostService.isRunning) startServiceWithAction(GhostService.ACTION_REFRESH_FOREGROUND)
    }

    override fun onStop() {
        pulse?.cancel()
        pulse = null
        generationJob?.cancel()
        super.onStop()
    }

    // NOTE: deliberately NOT calling engine.destroy() here — the Service owns the engine lifecycle.

    companion object {
        private val TAG = MainActivity::class.java.simpleName
        private const val DIRECTORY_MODELS = "models"

        /**
         * Where the chosen appearance lives.
         *
         * SharedPreferences rather than the DataStore everything else uses, and the reason is
         * timing: the mode has to be applied before setContentView or the first frame is drawn in
         * the wrong palette and then repainted. DataStore is suspend-only by design and reading it
         * here would mean blocking the main thread at every launch to avoid a flash. This is the
         * one setting with that constraint.
         */
        private const val THEME_PREFS = "ghost_theme"
        private const val KEY_NIGHT_MODE = "night_mode"

        /** The saved appearance, or following the phone if nothing has been chosen. */
        fun savedNightMode(ctx: android.content.Context): Int =
            ctx.getSharedPreferences(THEME_PREFS, android.content.Context.MODE_PRIVATE)
                .getInt(
                    KEY_NIGHT_MODE,
                    androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                )

        /** How far the screen's corners round as it slides aside for the drawer, in px. */
        private const val CONTENT_CORNER = 34f
        /** How much the screen shrinks. Small on purpose — enough to read as depth, not as a card. */
        private const val CONTENT_SHRINK = 0.06f
        /** The shadow that separates the two lit surfaces once the scrim is gone, in px. */
        private const val CONTENT_LIFT = 26f

        private fun Boolean.toRunLabel() = if (this) "running" else "stopped"
    }
}